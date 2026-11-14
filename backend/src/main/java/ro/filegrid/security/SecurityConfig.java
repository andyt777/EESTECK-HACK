package ro.filegrid.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import ro.filegrid.text.Texts;

/**
 * Stage 11: every API request needs a Keycloak-issued bearer token (stateless,
 * no session, no CSRF: no cookie authenticates anything). Fine-grained checks
 * stay in {@link AccessPolicy}; the filter chain only requires authentication.
 *
 * <ul>
 *   <li>Actuator (management port 8081, internal network only) is open: Prometheus
 *       scrapes it and Docker probes health.</li>
 *   <li>Signed content links ({@link SignedContentLinkMatcher}) pass unauthenticated and
 *       are verified by the content controller.</li>
 *   <li>Error and async dispatches (SSE, streamed downloads) are not re-authorized: the
 *       initial request already was.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(FileGridSecurityProperties.class)
public class SecurityConfig {

    private static final int JWKS_TIMEOUT_MILLIS = 5_000;

    @Bean
    SecurityProblemHandler securityProblemHandler(ObjectMapper json, Texts texts) {
        return new SecurityProblemHandler(json, texts);
    }

    @Bean
    @Order(1)
    SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                               SecurityProblemHandler problems) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .requestMatchers(new SignedContentLinkMatcher()).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(new KeycloakJwtAuthenticationConverter()))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * Keys: {@code public-key-location} when set (tests, offline use), otherwise the
     * Keycloak JWK set (fetched lazily, cached, over the internal network). Validation:
     * signature, {@code exp}/{@code nbf}, issuer (public issuer plus the explicitly
     * configured additional ones) and audience.
     */
    @Bean
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties resourceServer, FileGridSecurityProperties security)
            throws IOException {
        OAuth2ResourceServerProperties.Jwt jwt = resourceServer.getJwt();
        NimbusJwtDecoder decoder;
        Resource publicKey = jwt.getPublicKeyLocation();
        if (publicKey != null) {
            try (InputStream in = publicKey.getInputStream()) {
                RSAPublicKey key = RsaKeyConverters.x509().convert(in);
                decoder = NimbusJwtDecoder.withPublicKey(key).build();
            }
        } else if (StringUtils.hasText(jwt.getJwkSetUri())) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(JWKS_TIMEOUT_MILLIS);
            requestFactory.setReadTimeout(JWKS_TIMEOUT_MILLIS);
            decoder = NimbusJwtDecoder.withJwkSetUri(jwt.getJwkSetUri())
                    .restOperations(new RestTemplate(requestFactory))
                    .build();
        } else {
            throw new IllegalStateException("Set spring.security.oauth2.resourceserver.jwt.jwk-set-uri "
                    + "(FILEGRID_JWK_SET_URI) or public-key-location");
        }
        decoder.setJwtValidator(tokenValidator(acceptedIssuers(jwt.getIssuerUri(), security.additionalIssuers()),
                jwt.getAudiences()));
        return decoder;
    }

    static Set<String> acceptedIssuers(String issuerUri, List<String> additional) {
        if (!StringUtils.hasText(issuerUri)) {
            throw new IllegalStateException("spring.security.oauth2.resourceserver.jwt.issuer-uri "
                    + "(FILEGRID_JWT_ISSUER) must be set");
        }
        Set<String> issuers = new LinkedHashSet<>();
        issuers.add(issuerUri.trim());
        if (additional != null) {
            additional.stream().filter(StringUtils::hasText).map(String::trim).forEach(issuers::add);
        }
        return issuers;
    }

    /** Timestamps, issuer (one of {@code issuers}) and audience (at least one of {@code audiences}). */
    static OAuth2TokenValidator<Jwt> tokenValidator(Set<String> issuers, List<String> audiences) {
        if (audiences == null || audiences.isEmpty()) {
            throw new IllegalStateException("spring.security.oauth2.resourceserver.jwt.audiences must be set");
        }
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());
        validators.add(new JwtClaimValidator<Object>(JwtClaimNames.ISS,
                iss -> iss != null && issuers.contains(iss.toString())));
        validators.add(new JwtClaimValidator<Object>(JwtClaimNames.AUD,
                aud -> aud instanceof java.util.Collection<?> values
                        ? values.stream().anyMatch(value -> audiences.contains(String.valueOf(value)))
                        : aud != null && audiences.contains(aud.toString())));
        return new DelegatingOAuth2TokenValidator<>(validators);
    }
}

package ro.filegrid.security;

import jakarta.servlet.DispatcherType;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stage 11 (docs/AUTH-DESIGN.md): the api is an OAuth2 resource server. Every request needs a
 * valid Keycloak access token (or a signed content link); fine-grained checks stay in
 * {@link AccessPolicy}. Actuator runs on the internal management port and stays open there
 * (Prometheus, container health checks); the gateway routes only {@code /api/actuator/health}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    public static final String USERNAME_CLAIM = "preferred_username";

    @Bean
    @Order(1)
    SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, JwtDecoder decoder, ContentLinkSigner signer,
                                 ProblemResponses problems) throws Exception {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return http
                // Bearer tokens only, no cookies: CSRF does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a
                        // Async (SSE, streamed downloads) and error dispatches of an already authorized request.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new SignedContentLinkFilter(signer, Clock.systemUTC(), problems),
                        BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(SecurityConfiguration::authentication))
                        .authenticationEntryPoint((request, response, e) -> {
                            bearer.commence(request, response, e);
                            problems.unauthorized(response, "unauthorized", "auth.error.unauthorized");
                        })
                        .accessDeniedHandler((request, response, e) -> problems.forbidden(response)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) ->
                                problems.unauthorized(response, "unauthorized", "auth.error.unauthorized"))
                        .accessDeniedHandler((request, response, ex) -> problems.forbidden(response)))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties) throws Exception {
        NimbusJwtDecoder decoder;
        if (properties.publicKey() != null && !properties.publicKey().isBlank()) {
            byte[] der = Base64.getDecoder().decode(properties.publicKey().trim());
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            decoder = NimbusJwtDecoder.withPublicKey(key).build();
        } else {
            decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        }
        decoder.setJwtValidator(validator(properties));
        return decoder;
    }

    /** Signature + expiry + one of the accepted issuers + audience {@code filegrid-api}. */
    static OAuth2TokenValidator<Jwt> validator(SecurityProperties properties) {
        List<String> issuers = properties.issuers();
        String audience = properties.audience();
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtClaimValidator<Object>(JwtClaimNames.ISS, iss -> iss != null && issuers.contains(iss.toString())),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
    }

    /** realm_access.roles become {@code ROLE_<role>}; the principal is {@code preferred_username}. */
    static AbstractAuthenticationToken authentication(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                authorities.add(new SimpleGrantedAuthority(RolePermissionAccessPolicy.ROLE_PREFIX + role));
            }
        }
        String name = jwt.getClaimAsString(USERNAME_CLAIM);
        return new JwtAuthenticationToken(jwt, authorities, name != null ? name : jwt.getSubject());
    }

    /** Lets other components use the same conversion (tests, {@code /me}). */
    public static Converter<Jwt, AbstractAuthenticationToken> converter() {
        return SecurityConfiguration::authentication;
    }
}

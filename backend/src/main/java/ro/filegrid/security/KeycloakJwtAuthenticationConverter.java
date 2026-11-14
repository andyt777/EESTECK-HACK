package ro.filegrid.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Keycloak access token → authentication: realm roles ({@code realm_access.roles})
 * become {@code ROLE_<role>} authorities, the principal name is
 * {@code preferred_username} (service accounts: {@code service-account-<client>}),
 * falling back to {@code sub}.
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String ROLE_PREFIX = "ROLE_";
    static final String REALM_ACCESS = "realm_access";
    static final String ROLES = "roles";
    static final String PREFERRED_USERNAME = "preferred_username";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, authorities(jwt), principalName(jwt));
    }

    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS);
        if (realmAccess != null && realmAccess.get(ROLES) instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String name && !name.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + name));
                }
            }
        }
        return authorities;
    }

    static String principalName(Jwt jwt) {
        String username = jwt.getClaimAsString(PREFERRED_USERNAME);
        return username == null || username.isBlank() ? jwt.getSubject() : username;
    }
}

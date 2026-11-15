package ro.filegrid.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a {@code GET /api/files/{id}/content} that carries a valid signed link
 * ({@link ContentLinkSigner}) instead of a bearer token. The request runs as the person who
 * asked for the link, with exactly the permissions that were checked when it was issued.
 */
public class SignedContentLinkFilter extends OncePerRequestFilter {

    private static final Pattern CONTENT_PATH = Pattern.compile("^/files/([0-9a-fA-F-]{36})/content$");

    private final ContentLinkSigner signer;
    private final Clock clock;
    private final ProblemResponses problems;
    private final RequestAttributeSecurityContextRepository repository = new RequestAttributeSecurityContextRepository();

    public SignedContentLinkFilter(ContentLinkSigner signer, Clock clock, ProblemResponses problems) {
        this.signer = signer;
        this.clock = clock;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getParameter("sig") == null || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                || !("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Matcher matcher = CONTENT_PATH.matcher(path);
        ContentLinkSigner.Grant grant = null;
        if (matcher.matches()) {
            grant = signer.verify(UUID.fromString(matcher.group(1)), request.getParameter("disposition"),
                    request.getParameter("exp"), request.getParameter("act"), request.getParameter("ack"),
                    request.getParameter("sig"), clock.instant());
        }
        if (grant == null) {
            problems.unauthorized(response, "link-invalid", "auth.error.linkInvalid");
            return;
        }
        List<GrantedAuthority> authorities = new ArrayList<>(List.of(
                new SimpleGrantedAuthority(RolePermissionAccessPolicy.PERMISSION_PREFIX + RolePermissions.FILE_READ),
                new SimpleGrantedAuthority(RolePermissionAccessPolicy.PERMISSION_PREFIX + RolePermissions.PII_READ)));
        if (grant.acknowledgeSuspect()) {
            authorities.add(new SimpleGrantedAuthority(
                    RolePermissionAccessPolicy.PERMISSION_PREFIX + RolePermissions.FILE_READ_SUSPECT));
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new PreAuthenticatedAuthenticationToken(grant.actor(), null, authorities));
        SecurityContextHolder.setContext(context);
        repository.saveContext(context, request, response);
        chain.doFilter(request, response);
    }
}

package ro.filegrid.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Matches a content request that carries a link signature and no bearer token:
 * {@code GET|HEAD /files/{id}/content?...&sig=...}. Such a request passes the
 * filter chain unauthenticated; the content controller then accepts it only if
 * the signature is valid ({@link ContentLinkSigner}).
 */
public class SignedContentLinkMatcher implements RequestMatcher {

    private static final Pattern CONTENT_PATH = Pattern.compile("^/files/[0-9a-fA-F-]{36}/content$");

    @Override
    public boolean matches(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        if (request.getParameter("sig") == null || request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            return false;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = contextPath != null && uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
        return CONTENT_PATH.matcher(path).matches();
    }
}

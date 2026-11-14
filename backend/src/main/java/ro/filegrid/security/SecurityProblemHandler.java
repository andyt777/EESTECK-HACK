package ro.filegrid.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import ro.filegrid.text.Texts;

/**
 * 401 and 403 decided by the filter chain, answered like every other API error:
 * RFC 9457 problem details ({@code urn:filegrid:<code>}, Romanian detail). The
 * {@code WWW-Authenticate} header is set by the standard bearer token handlers.
 */
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler bearerDenied = new BearerTokenAccessDeniedHandler();
    private final ObjectMapper json;
    private final Texts texts;

    public SecurityProblemHandler(ObjectMapper json, Texts texts) {
        this.json = json;
        this.texts = texts;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        bearerEntryPoint.commence(request, response, authException);
        write(request, response, HttpStatus.UNAUTHORIZED, "unauthenticated",
                texts.get("auth.error.unauthenticated"));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        bearerDenied.handle(request, response, accessDeniedException);
        write(request, response, HttpStatus.FORBIDDEN, "forbidden", texts.get("file.error.forbidden"));
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
                       String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:filegrid:" + code));
        try {
            problem.setInstance(URI.create(request.getRequestURI()));
        } catch (IllegalArgumentException malformed) {
            // the request path is not a valid URI: the problem is sent without an instance
        }
        problem.setProperty("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), problem);
    }
}

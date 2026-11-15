package ro.filegrid.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import ro.filegrid.text.Texts;

/** 401 / 403 answered by the security filters as RFC 9457 problem details, like every other API error. */
@Component
public class ProblemResponses {

    private final ObjectMapper json;
    private final Texts texts;

    public ProblemResponses(ObjectMapper json, Texts texts) {
        this.json = json;
        this.texts = texts;
    }

    public void unauthorized(HttpServletResponse response, String code, String textKey) throws IOException {
        if (response.getHeader("WWW-Authenticate") == null) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        write(response, HttpStatus.UNAUTHORIZED, code, textKey);
    }

    public void forbidden(HttpServletResponse response) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "forbidden", "auth.error.forbidden");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String textKey)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, texts.get(textKey));
        problem.setType(URI.create("urn:filegrid:" + code));
        problem.setProperty("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), problem);
    }
}

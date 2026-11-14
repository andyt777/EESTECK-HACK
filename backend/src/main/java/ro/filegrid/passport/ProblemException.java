package ro.filegrid.passport;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** An API error answered as RFC 9457 problem details, with the message in Romanian. */
public class ProblemException extends ErrorResponseException {

    public ProblemException(HttpStatus status, String code, String detail) {
        super(status, problem(status, code, detail), null);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:filegrid:" + code));
        problem.setProperty("code", code);
        return problem;
    }
}

package ro.filegrid.passport;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Renders {@link ProblemException} as {@code application/problem+json}. */
@RestControllerAdvice
public class ProblemAdvice {

    @ExceptionHandler(ProblemException.class)
    ResponseEntity<ProblemDetail> problem(ProblemException e) {
        return ResponseEntity.status(e.getStatusCode()).body(e.getBody());
    }
}

package com.lumix.template.adapter.in.rest;

import com.lumix.template.domain.exception.SampleAlreadyActiveException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Domain/uygulama hatalarını RFC 7807 ProblemDetail'e çevirir (bkz. 05-error-handling-rfc7807).
 * Bean Validation (@Valid) hataları Spring tarafından zaten 400 ProblemDetail'e dönüştürülür.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Geçersiz istek");
        return problem;
    }

    @ExceptionHandler(SampleAlreadyActiveException.class)
    public ProblemDetail handleAlreadyActive(SampleAlreadyActiveException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Durum çakışması");
        return problem;
    }
}

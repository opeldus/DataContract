package de.opeldus.datacontract.common;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptions {

    public static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    public static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    public static ResponseStatusException badGateway(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail optimisticLock(OptimisticLockingFailureException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Das Mapping wurde zwischenzeitlich von jemand anderem geändert. Bitte neu laden.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail uploadTooLarge(MaxUploadSizeExceededException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "Die Datei ist größer als erlaubt. Das Limit lässt sich mit UPLOAD_MAX_SIZE erhöhen.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail status(ResponseStatusException exception) {
        return ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
    }
}

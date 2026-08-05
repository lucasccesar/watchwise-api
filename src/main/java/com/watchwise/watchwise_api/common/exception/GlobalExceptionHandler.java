package com.watchwise.watchwise_api.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.LocalDateTime;
import java.util.List;

public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(
            NotFoundException ex,
            HttpServletRequest request
    ){
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(
                        LocalDateTime.now(),
                        HttpStatus.NOT_FOUND.value(),
                        "Not Found",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ApiError> handleBadRequest(
            BadRequestException ex,
            HttpServletRequest request
    ){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(
                        LocalDateTime.now(),
                        HttpStatus.BAD_REQUEST.value(),
                        "Bad Request",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> handleConflict(
            ConflictException ex,
            HttpServletRequest request
    ){
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(
                        LocalDateTime.now(),
                        HttpStatus.CONFLICT.value(),
                        "Conflict",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ){
        List<ValidationError> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fieldError -> new ValidationError(
                        fieldError.getField(),
                        fieldError.getDefaultMessage()
                ))
                .toList();

        HttpStatus httpStatus = HttpStatus.valueOf(status.value());

        String path = ((ServletWebRequest) request)
                .getRequest()
                .getRequestURI();

        ValidationApiError apiError = new ValidationApiError(
                LocalDateTime.now(),
                httpStatus.value(),
                httpStatus.getReasonPhrase(),
                "Validation Failed",
                path,
                errors
        );

        return ResponseEntity.status(httpStatus).body(apiError);
    }

}

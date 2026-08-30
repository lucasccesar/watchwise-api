package com.watchwise.watchwise_api.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.exc.InvalidFormatException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RestControllerAdvice
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

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ApiError> handleForbidden(
            ForbiddenException ex,
            HttpServletRequest request
    ){
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError(
                        LocalDateTime.now(),
                        HttpStatus.FORBIDDEN.value(),
                        "Forbidden",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<ApiError> handleUnauthorized(
            UnauthorizedException ex,
            HttpServletRequest request
    ){
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError(
                        LocalDateTime.now(),
                        HttpStatus.UNAUTHORIZED.value(),
                        "Unauthorized",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(TmdbUnavailableException.class)
    ResponseEntity<ApiError> handleTmdbUnavailable(
            TmdbUnavailableException ex,
            HttpServletRequest request
    ){
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError(
                        LocalDateTime.now(),
                        HttpStatus.BAD_GATEWAY.value(),
                        "Bad Gateway",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(TooManyRequestsException.class)
    ResponseEntity<ApiError> handleTooManyRequests(
            TooManyRequestsException ex,
            HttpServletRequest request
    ){
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ApiError(
                        LocalDateTime.now(),
                        HttpStatus.TOO_MANY_REQUESTS.value(),
                        "Too Many Requests",
                        ex.getMessage(),
                        request.getRequestURI()
                ));
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();

        ApiError apiError = new ApiError(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                "Required parameter '" + ex.getParameterName() + "' is missing",
                path
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(apiError);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ){
        List<ValidationError> errors = Stream.concat(
                        ex.getBindingResult().getFieldErrors().stream()
                                .map(fieldError -> new ValidationError(fieldError.getField(), fieldError.getDefaultMessage())),
                        ex.getBindingResult().getGlobalErrors().stream()
                                .map(globalError -> new ValidationError(null, globalError.getDefaultMessage()))
                )
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

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();

        ApiError apiError = new ApiError(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                buildMalformedRequestMessage(ex),
                path
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(apiError);
    }

    private String buildMalformedRequestMessage(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof InvalidFormatException ife
                && ife.getTargetType() != null
                && ife.getTargetType().isEnum()) {
            String field = ife.getPath().isEmpty() ? null : ife.getPath().getLast().getPropertyName();
            String acceptedValues = Arrays.stream(ife.getTargetType().getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            String location = field != null ? " for field '" + field + "'" : "";

            return "Invalid value '" + ife.getValue() + "'" + location + ". Accepted values: " + acceptedValues;
        }
        return "Malformed JSON request";
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        String supported = ex.getSupportedMediaTypes().stream()
                .map(MediaType::toString)
                .collect(Collectors.joining(", "));

        ApiError apiError = new ApiError(
                LocalDateTime.now(),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                "Unsupported Media Type",
                "Content-Type '" + ex.getContentType() + "' is not supported. Supported: " + supported,
                path
        );

        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(apiError);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        Class<?> requiredType = ex.getRequiredType();
        String requiredTypeName = requiredType != null ? requiredType.getSimpleName() : "the expected type";
        String acceptedValuesSuffix = requiredType != null && requiredType.isEnum()
                ? ". Accepted values: " + Arrays.stream(requiredType.getEnumConstants())
                        .map(Object::toString)
                        .collect(Collectors.joining(", "))
                : "";

        ApiError apiError = new ApiError(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getPropertyName() + "'. Expected type: " + requiredTypeName + acceptedValuesSuffix,
                path
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(apiError);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        String supported = ex.getSupportedMethods() != null
                ? String.join(", ", ex.getSupportedMethods())
                : "none";

        ApiError apiError = new ApiError(
                LocalDateTime.now(),
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                "Method Not Allowed",
                "Method '" + ex.getMethod() + "' is not supported for this path. Supported: " + supported,
                path
        );

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(apiError);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();

        ApiError apiError = new ApiError(
                LocalDateTime.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                "No endpoint found for " + ex.getHttpMethod() + " " + path,
                path
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(apiError);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpectedException(
            Exception ex,
            HttpServletRequest request
    ) {
        log.error("Unhandled exception", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(
                        LocalDateTime.now(),
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Internal Server Error",
                        "An unexpected error occurred",
                        request.getRequestURI()
                ));
    }

}

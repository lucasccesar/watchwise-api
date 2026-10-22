package com.watchwise.watchwise_api.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("[handleMethodArgumentNotValid] Should Include Both Field And Class-Level Errors - When Both Are Present")
    void shouldIncludeBothFieldAndClassLevelErrorsWhenBothArePresent() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "username", "must not be blank"));
        bindingResult.addError(new ObjectError("target", "passwords must match"));

        MethodParameter methodParameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyMethod", String.class), 0);
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(methodParameter, bindingResult);

        HttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/some/path");
        WebRequest request = new ServletWebRequest(servletRequest);

        Object body = handler.handleMethodArgumentNotValid(exception, null, HttpStatus.BAD_REQUEST, request).getBody();

        assertThat(body).isInstanceOf(ValidationApiError.class);
        ValidationApiError apiError = (ValidationApiError) body;
        assertThat(apiError.errors())
                .extracting(ValidationError::field, ValidationError::message)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("username", "must not be blank"),
                        org.assertj.core.groups.Tuple.tuple(null, "passwords must match")
                );
    }

    @Test
    @DisplayName("[handleUnexpectedException] Should Return Generic 500 ApiError Without Leaking The Exception Message - When An Unmapped Exception Is Thrown")
    void shouldReturnGeneric500ApiErrorWithoutLeakingTheExceptionMessageWhenAnUnmappedExceptionIsThrown() {
        HttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/some/path");
        RuntimeException exception = new RuntimeException("sensitive internal detail: connection string leaked here");

        ApiError apiError = handler.handleUnexpectedException(exception, servletRequest).getBody();

        assertThat(apiError).isNotNull();
        assertThat(apiError.status()).isEqualTo(500);
        assertThat(apiError.error()).isEqualTo("Internal Server Error");
        assertThat(apiError.message()).isEqualTo("An unexpected error occurred");
        assertThat(apiError.message()).doesNotContain("sensitive internal detail");
        assertThat(apiError.path()).isEqualTo("/some/path");
    }

    @Test
    @DisplayName("[handleDailyGamesUnavailable] Should Return A Generic 503 ApiError - When The Daily Challenge Set Is Unavailable")
    void shouldReturnAGeneric503ApiErrorWhenTheDailyChallengeSetIsUnavailable() {
        HttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/games/today");
        DailyGamesUnavailableException exception = new DailyGamesUnavailableException();

        ApiError apiError = handler.handleDailyGamesUnavailable(exception, servletRequest).getBody();

        assertThat(apiError).isNotNull();
        assertThat(apiError.status()).isEqualTo(503);
        assertThat(apiError.error()).isEqualTo("Service Unavailable");
        assertThat(apiError.message()).isEqualTo("Daily games are temporarily unavailable");
        assertThat(apiError.message()).doesNotContain("SQL");
        assertThat(apiError.message()).doesNotContain("TMDB");
        assertThat(apiError.path()).isEqualTo("/games/today");
    }

    @Test
    @DisplayName("[handleMaxUploadSizeExceeded] Should Return 413 ApiError - When Multipart Upload Exceeds Limit")
    void shouldReturn413ApiErrorWhenMultipartUploadExceedsLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/imports/uploads");
        WebRequest webRequest = new ServletWebRequest(request);

        var response = handler.handleMaxUploadSizeExceededException(
                new MaxUploadSizeExceededException(1024), new HttpHeaders(), HttpStatus.PAYLOAD_TOO_LARGE, webRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).isInstanceOf(ApiError.class);
        ApiError apiError = (ApiError) response.getBody();
        assertThat(apiError.error()).isEqualTo("Payload Too Large");
        assertThat(apiError.message()).isEqualTo("Uploaded file exceeds the configured size limit");
        assertThat(apiError.path()).isEqualTo("/imports/uploads");
    }

    @Test
    @DisplayName("[handleMaxUploadSizeExceededException] Should Register Without Ambiguity - When MVC Initializes Advice")
    void shouldRegisterMaxUploadHandlerWithoutAmbiguityWhenMvcInitializesAdvice() {
        try (AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext()) {
            applicationContext.registerBean(GlobalExceptionHandler.class);
            applicationContext.refresh();
            ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();
            resolver.setApplicationContext(applicationContext);

            assertThatCode(resolver::afterPropertiesSet).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("[handleHttpMediaTypeNotSupported] Should Return 415 ApiError - When Upload Uses Unsupported Content Type")
    void shouldReturn415ApiErrorWhenUploadUsesUnsupportedContentType() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/imports/uploads");
        WebRequest request = new ServletWebRequest(servletRequest);
        HttpMediaTypeNotSupportedException exception = new HttpMediaTypeNotSupportedException(
                MediaType.APPLICATION_JSON,
                List.of(MediaType.MULTIPART_FORM_DATA));

        var response = handler.handleHttpMediaTypeNotSupported(
                exception, new HttpHeaders(), HttpStatus.UNSUPPORTED_MEDIA_TYPE, request);

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody()).isInstanceOf(ApiError.class);
        ApiError apiError = (ApiError) response.getBody();
        assertThat(apiError.message()).contains("application/json", "multipart/form-data");
        assertThat(apiError.path()).isEqualTo("/imports/uploads");
    }

    private void dummyMethod(String argument) {
    }
}

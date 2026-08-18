package com.watchwise.watchwise_api.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import static org.assertj.core.api.Assertions.assertThat;

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

    private void dummyMethod(String argument) {
    }
}

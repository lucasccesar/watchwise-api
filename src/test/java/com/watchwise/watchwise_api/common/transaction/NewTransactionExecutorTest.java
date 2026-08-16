package com.watchwise.watchwise_api.common.transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class NewTransactionExecutorTest {

    private final NewTransactionExecutor newTransactionExecutor = new NewTransactionExecutor();

    @Test
    @DisplayName("[runInNewTransaction] Should Return The Value Produced By The Supplied Action - When Called")
    void shouldReturnTheValueProducedByTheSuppliedActionWhenCalled() {
        String result = newTransactionExecutor.runInNewTransaction(() -> "content-saved");

        assertThat(result).isEqualTo("content-saved");
    }

    @Test
    @DisplayName("[runInNewTransaction] Should Propagate The Exception - When The Supplied Action Throws")
    void shouldPropagateTheExceptionWhenTheSuppliedActionThrows() {
        RuntimeException exception = new RuntimeException("constraint violated");

        try {
            newTransactionExecutor.runInNewTransaction(() -> {
                throw exception;
            });
        } catch (RuntimeException caught) {
            assertThat(caught).isSameAs(exception);
            return;
        }

        throw new AssertionError("Expected exception was not thrown");
    }

    @Test
    @DisplayName("[runInNewTransaction] Should Be Annotated With Propagation REQUIRES_NEW - When Inspected")
    void shouldBeAnnotatedWithPropagationRequiresNewWhenInspected() throws NoSuchMethodException {
        Method method = NewTransactionExecutor.class.getMethod("runInNewTransaction", java.util.function.Supplier.class);

        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
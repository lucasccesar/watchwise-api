package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateOption;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FixedOptionPersistenceTest {

    @Mock
    private PicksTemplateOptionRepository repository;

    @Test
    @DisplayName("[saveAndFlush] Should Return Persisted Option - When Flush Succeeds")
    void shouldReturnPersistedOptionWhenFlushSucceeds() {
        PicksTemplateOption option = PicksTemplateOption.builder().personTmdbId("42").build();
        when(repository.saveAndFlush(option)).thenReturn(option);

        assertThat(FixedOptionPersistence.saveAndFlush(repository, option)).isSameAs(option);
        verify(repository).saveAndFlush(option);
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("[saveAndFlush] Should Throw ConflictException Without Recovery Query - When Fixed Target Is Duplicated")
    void shouldThrowConflictWhenFixedTargetIsDuplicated() {
        PicksTemplateOption option = PicksTemplateOption.builder().personTmdbId("42").build();
        when(repository.saveAndFlush(option)).thenThrow(violation("uq_picks_template_options_fixed_target"));

        assertThatThrownBy(() -> FixedOptionPersistence.saveAndFlush(repository, option))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This target is already a fixed option for this category");
        verify(repository).saveAndFlush(option);
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("[saveAndFlush] Should Propagate Integrity Failure - When Constraint Is Unknown")
    void shouldPropagateIntegrityFailureWhenConstraintIsUnknown() {
        PicksTemplateOption option = PicksTemplateOption.builder().build();
        DataIntegrityViolationException failure = violation("another_constraint");
        when(repository.saveAndFlush(option)).thenThrow(failure);

        assertThatThrownBy(() -> FixedOptionPersistence.saveAndFlush(repository, option)).isSameAs(failure);
        verify(repository).saveAndFlush(option);
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("[saveAndFlush] Should Propagate Integrity Failure - When Cause Has No Constraint")
    void shouldPropagateIntegrityFailureWhenCauseHasNoConstraint() {
        PicksTemplateOption option = PicksTemplateOption.builder().build();
        DataIntegrityViolationException failure = new DataIntegrityViolationException("failure");
        when(repository.saveAndFlush(option)).thenThrow(failure);

        assertThatThrownBy(() -> FixedOptionPersistence.saveAndFlush(repository, option)).isSameAs(failure);
        verify(repository).saveAndFlush(option);
        verifyNoMoreInteractions(repository);
    }

    private DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("failure",
                new ConstraintViolationException("duplicate", new SQLException("duplicate", "23505"), constraint));
    }
}

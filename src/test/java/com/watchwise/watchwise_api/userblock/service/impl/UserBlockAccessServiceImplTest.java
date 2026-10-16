package com.watchwise.watchwise_api.userblock.service.impl;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserBlockAccessServiceImplTest {
    @Mock UserBlockRepository userBlockRepository;
    @InjectMocks UserBlockAccessServiceImpl service;

    @Test void shouldUseEitherDirectionPredicate() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(userBlockRepository.existsEitherDirection(firstId, secondId)).thenReturn(true);
        assertThat(service.isBlockedEitherDirection(firstId, secondId)).isTrue();
        verify(userBlockRepository).existsEitherDirection(firstId, secondId);
    }

    @Test void shouldRejectInteractionWhenEitherUserBlockedTheOther() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(userBlockRepository.existsEitherDirection(firstId, secondId)).thenReturn(true);
        assertThatThrownBy(() -> service.assertInteractionAllowed(firstId, secondId))
                .isInstanceOf(ForbiddenException.class);
    }
}

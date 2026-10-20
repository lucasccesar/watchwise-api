package com.watchwise.watchwise_api.userblock.service.impl;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.userblock.entity.UserBlockRelationship;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.List;

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

    @Test void shouldClassifyAllDirectionalBlockRelationships() {
        UUID viewerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        assertThat(service.relationship(viewerId, targetId)).isEqualTo(UserBlockRelationship.NONE);

        UserBlockRepository.PairDirection outgoing = direction(viewerId, targetId);
        when(userBlockRepository.findPairDirections(viewerId, targetId)).thenReturn(List.of(outgoing));
        assertThat(service.relationship(viewerId, targetId)).isEqualTo(UserBlockRelationship.BLOCKED_BY_ME);

        UserBlockRepository.PairDirection incoming = direction(targetId, viewerId);
        when(userBlockRepository.findPairDirections(viewerId, targetId)).thenReturn(List.of(incoming));
        assertThat(service.relationship(viewerId, targetId)).isEqualTo(UserBlockRelationship.BLOCKED_ME);

        when(userBlockRepository.findPairDirections(viewerId, targetId)).thenReturn(List.of(outgoing, incoming));
        assertThat(service.relationship(viewerId, targetId)).isEqualTo(UserBlockRelationship.MUTUAL);
    }

    @Test void shouldHideBlockedContentInEitherDirectionButAllowSelf() {
        UUID viewerId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(userBlockRepository.existsEitherDirection(viewerId, ownerId)).thenReturn(true);
        when(userBlockRepository.existsEitherDirection(ownerId, viewerId)).thenReturn(true);
        assertThatThrownBy(() -> service.assertContentVisible(viewerId, ownerId))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.assertContentVisible(ownerId, viewerId))
                .isInstanceOf(NotFoundException.class);
        service.assertContentVisible(viewerId, viewerId);
    }

    private static UserBlockRepository.PairDirection direction(UUID blockerId, UUID blockedId) {
        return new Direction(blockerId, blockedId);
    }

    private record Direction(UUID blockerId, UUID blockedId) implements UserBlockRepository.PairDirection {
        @Override public UUID getBlockerId() { return blockerId; }
        @Override public UUID getBlockedId() { return blockedId; }
    }
}

package com.watchwise.watchwise_api.userblock.service.impl;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.common.transaction.UserPairLockIdentity;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserBlockAccessServiceImplTest {
    @Mock UserBlockRepository userBlockRepository;
    @Mock AdvisoryLock advisoryLock;
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

    @Test void shouldLockThePairBeforeAllowingAnInteraction() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userBlockRepository.existsEitherDirection(actorId, targetId)).thenReturn(true);

        assertThatThrownBy(() -> service.lockAndAssertInteractionAllowed(actorId, targetId))
                .isInstanceOf(ForbiddenException.class);

        verify(advisoryLock).lock(UserPairLockIdentity.of(actorId, targetId));
    }

    @Test void shouldLockBeforeCheckingContentVisibilityAndHideBlockedContent() {
        UUID actorId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        when(userBlockRepository.existsEitherDirection(actorId, creatorId)).thenReturn(true);

        assertThatThrownBy(() -> service.lockAndAssertContentVisible(actorId, creatorId))
                .isInstanceOf(NotFoundException.class);

        var order = inOrder(advisoryLock, userBlockRepository);
        order.verify(advisoryLock).lock(UserPairLockIdentity.of(actorId, creatorId));
        order.verify(userBlockRepository).existsEitherDirection(actorId, creatorId);
    }

    @Test void shouldAllowSelfWithoutTakingAPairLock() {
        UUID actorId = UUID.randomUUID();

        service.lockAndAssertContentVisible(actorId, actorId);

        verifyNoInteractions(advisoryLock, userBlockRepository);
    }

    private static UserBlockRepository.PairDirection direction(UUID blockerId, UUID blockedId) {
        return new Direction(blockerId, blockedId);
    }

    private record Direction(UUID blockerId, UUID blockedId) implements UserBlockRepository.PairDirection {
        @Override public UUID getBlockerId() { return blockerId; }
        @Override public UUID getBlockedId() { return blockedId; }
    }
}

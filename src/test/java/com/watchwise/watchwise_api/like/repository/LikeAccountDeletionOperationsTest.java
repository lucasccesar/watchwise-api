package com.watchwise.watchwise_api.like.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.*;
import static com.watchwise.watchwise_api.like.repository.LikeAccountDeletionOperations.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LikeAccountDeletionOperationsTest {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final LikeAccountDeletionOperationsImpl repository = new LikeAccountDeletionOperationsImpl(jdbc);
    final UUID actor = UUID.randomUUID();

    @Test
    void discardedUndercountedTargetDoesNotAbortSurvivorReconciliation() {
        Target discarded = new Target(TargetType.COMMENT, UUID.randomUUID());
        Target survivor = new Target(TargetType.LIST, UUID.randomUUID());
        Target actorCreatedTemplate = new Target(TargetType.PICKS_TEMPLATE, UUID.randomUUID());
        when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<Target>>any()))
                .thenReturn(List.of(discarded, survivor, actorCreatedTemplate));
        when(jdbc.update(anyString(), anyMap())).thenAnswer(call ->
                ((String) call.getArgument(0)).contains("UPDATE comments") ? 0 : 1);

        assertThatCode(() -> repository.deleteAccountLikesAndReconcile(actor, List.of(survivor, actorCreatedTemplate)))
                .doesNotThrowAnyException();

        verify(jdbc, never()).update(contains("UPDATE comments"), anyMap());
        verify(jdbc).update(contains("UPDATE user_lists"), argThat((Map<String, ?> values) -> values.containsValue(1L)));
        verify(jdbc).update(contains("UPDATE picks_templates"), argThat((Map<String, ?> values) -> values.containsValue(1L)));
    }

    @Test
    void discardedTargetDoesNotHideUndercountedSurvivor() {
        Target discarded = new Target(TargetType.COMMENT, UUID.randomUUID());
        Target survivor = new Target(TargetType.PICK, UUID.randomUUID());
        when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<Target>>any()))
                .thenReturn(List.of(discarded, survivor));
        when(jdbc.update(anyString(), anyMap())).thenReturn(0);

        assertThatThrownBy(() -> repository.deleteAccountLikesAndReconcile(actor, List.of(survivor)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("PICK");
        verify(jdbc, never()).update(contains("UPDATE comments"), anyMap());
    }

    @Test
    void creatorOwnedTemplateStillFailsClosedWhenItsSurvivingCounterIsInsufficient() {
        Target template = new Target(TargetType.PICKS_TEMPLATE, UUID.randomUUID());
        when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<Target>>any()))
                .thenReturn(List.of(template));
        when(jdbc.update(anyString(), anyMap())).thenReturn(0);

        assertThatThrownBy(() -> repository.deleteAccountLikesAndReconcile(actor, List.of(template)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("PICKS_TEMPLATE");
    }

    @Test
    void reconcilesAllSixStoredCounterKindsButLeavesContentReviewLiveCountAlone() {
        List<Target> deleted = Arrays.stream(TargetType.values()).map(type -> new Target(type, UUID.randomUUID())).toList();
        when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<Target>>any())).thenReturn(deleted);
        when(jdbc.update(anyString(), anyMap())).thenReturn(1);
        repository.deleteAccountLikesAndReconcile(actor, deleted);
        verify(jdbc).update(contains("UPDATE user_lists"), anyMap());
        verify(jdbc).update(contains("UPDATE comments"), anyMap());
        verify(jdbc).update(contains("UPDATE diary_entries"), anyMap());
        verify(jdbc).update(contains("UPDATE dropped_entries"), anyMap());
        verify(jdbc).update(contains("UPDATE picks t"), anyMap());
        verify(jdbc).update(contains("UPDATE picks_templates"), anyMap());
        verify(jdbc, times(6)).update(anyString(), anyMap());
    }

    @Test
    void boundsCounterUpdateStatementsForAnAccountWithManyTargets() {
        List<Target> deleted = java.util.stream.IntStream.range(0, 257)
                .mapToObj(index -> new Target(TargetType.COMMENT, UUID.randomUUID())).toList();
        when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<Target>>any())).thenReturn(deleted);
        when(jdbc.update(anyString(), anyMap())).thenAnswer(call -> ((Map<?, ?>) call.getArgument(1)).size() / 2);
        repository.deleteAccountLikesAndReconcile(actor, deleted);
        verify(jdbc).update(contains("UPDATE comments"), argThat((Map<String, ?> values) -> values.size() == 512));
        verify(jdbc).update(contains("UPDATE comments"), argThat((Map<String, ?> values) -> values.size() == 2));
        verify(jdbc, times(2)).update(anyString(), anyMap());
    }

    @Test
    void decrementsOnlyActualReturnedRowsAggregatedByTypeAndTarget() {
        UUID comment = UUID.randomUUID();
        UUID list = UUID.randomUUID();
        when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<Target>>any()))
                .thenReturn(List.of(new Target(TargetType.COMMENT, comment),
                        new Target(TargetType.COMMENT, comment), new Target(TargetType.LIST, list)));
        when(jdbc.update(anyString(), anyMap())).thenReturn(1);

        repository.deleteAccountLikesAndReconcile(actor, List.of(new Target(TargetType.COMMENT, comment), new Target(TargetType.LIST, list)));

        verify(jdbc).update(contains("UPDATE comments"), argThat((Map<String, ?> values) ->
                values.containsValue(comment) && values.containsValue(2L)));
        verify(jdbc).update(contains("UPDATE user_lists"), argThat((Map<String, ?> values) ->
                values.containsValue(list) && values.containsValue(1L)));
        verify(jdbc, times(2)).update(anyString(), anyMap());
        verify(jdbc).query(contains("DELETE FROM likes"), eq(Map.of("actorId", actor)),
                org.mockito.ArgumentMatchers.<RowMapper<Target>>any());
    }

    @Test
    void rejectsUncoveredDeltaSoTransactionRollsBack() {
        Target survivor = new Target(TargetType.PICK, UUID.randomUUID());
        when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<Target>>any()))
                .thenReturn(List.of(survivor));
        when(jdbc.update(anyString(), anyMap())).thenReturn(0);
        assertThatThrownBy(() -> repository.deleteAccountLikesAndReconcile(actor, List.of(survivor)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void doesNotDecrementWhenConcurrentTargetCascadeAlreadyRemovedLike() {
        when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<Target>>any()))
                .thenReturn(List.of());
        repository.deleteAccountLikesAndReconcile(actor, List.of(new Target(TargetType.PICK, UUID.randomUUID())));
        verify(jdbc, never()).update(anyString(), anyMap());
    }

    @Test
    void locksTypesThenUnsignedUuidOrderWithListsFirst() {
        UUID high = UUID.fromString("ffffffff-0000-0000-0000-000000000000");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        repository.lockAccountLikeTargets(List.of(new Target(TargetType.COMMENT, high),
                new Target(TargetType.COMMENT, low), new Target(TargetType.LIST, high)));
        var order = inOrder(jdbc);
        order.verify(jdbc).queryForList(contains("FROM user_lists"), eq(Map.of("ids", List.of(high))), eq(UUID.class));
        order.verify(jdbc).queryForList(contains("FROM comments"), eq(Map.of("ids", List.of(low, high))), eq(UUID.class));
    }
}

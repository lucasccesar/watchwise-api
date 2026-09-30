package com.watchwise.watchwise_api.comment.repository;

import com.watchwise.watchwise_api.comment.entity.Comment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommentRepositoryRecentCommentsRaceTest {

    @Test
    @DisplayName("[findRecentByPickIdIn] Should Skip Deleted Comments - When A Ranked Id Disappears Before Loading")
    void shouldSkipDeletedCommentsWhenARankedIdDisappearsBeforeLoading() {
        CommentRepository repository = mock(CommentRepository.class, CALLS_REAL_METHODS);
        UUID pickId = UUID.randomUUID();
        Comment survivor = Comment.builder().id(UUID.randomUUID()).text("kept").build();
        UUID deletedId = UUID.randomUUID();
        doReturnIds(repository, List.of(survivor.getId(), deletedId), pickId);
        when(repository.findByIdInWithUser(List.of(survivor.getId(), deletedId))).thenReturn(List.of(survivor));

        List<Comment> result = repository.findRecentByPickIdIn(List.of(pickId));

        assertThat(result).containsExactly(survivor);
    }

    private void doReturnIds(CommentRepository repository, List<UUID> ids, UUID pickId) {
        org.mockito.Mockito.doReturn(ids).when(repository).findRecentIdsByPickIdIn(List.of(pickId));
    }
}

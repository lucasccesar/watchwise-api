package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PickDetailViewDTOTest {

    @Test
    @DisplayName("[constructor] Should preserve answered and unanswered order - When answers are supplied")
    void shouldPreserveAnsweredAndUnansweredOrderWhenAnswersAreSupplied() {
        PickAnswerViewDTO unanswered = answer(2, false);
        PickAnswerViewDTO answered = answer(1, true);
        List<PickAnswerViewDTO> answers = new ArrayList<>(List.of(unanswered, answered));

        PickDetailViewDTO detail = new PickDetailViewDTO(UUID.randomUUID(), null, null, PickVisibility.PUBLIC,
                null, null, PickProgress.PARTIAL, 1, 2, answers, 3, 4, true);
        answers.clear();

        assertThat(detail.answers()).containsExactly(unanswered, answered);
        assertThat(detail.answers().get(0).isAnswered()).isFalse();
        assertThat(detail.answers().get(1).isAnswered()).isTrue();
        assertThatThrownBy(() -> detail.answers().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("[constructor] Should normalize null answers - When no answers are supplied")
    void shouldNormalizeNullAnswersWhenNoAnswersAreSupplied() {
        PickDetailViewDTO detail = new PickDetailViewDTO(UUID.randomUUID(), null, null, PickVisibility.PUBLIC,
                null, null, PickProgress.EMPTY, 0, 0, null, 0, 0, false);

        assertThat(detail.answers()).isEmpty();
        assertThatThrownBy(() -> detail.answers().add(answer(1, false)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("[constructor] Should normalize null answered categories - When template pick has no answers")
    void shouldNormalizeNullAnsweredCategoriesWhenTemplatePickHasNoAnswers() {
        PickTemplatePickViewDTO pick = new PickTemplatePickViewDTO(UUID.randomUUID(), null, PickVisibility.PUBLIC,
                null, PickProgress.EMPTY, 0, 0, null, 0, 0, false);

        assertThat(pick.answeredCategories()).isEmpty();
        assertThatThrownBy(() -> pick.answeredCategories().add(answer(1, false)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static PickAnswerViewDTO answer(int order, boolean isAnswered) {
        return new PickAnswerViewDTO(UUID.randomUUID(), "Category " + order, null, order, null, null,
                null, null, isAnswered, isAnswered);
    }
}

package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PickViewCardDTOTest {

    @Test
    @DisplayName("[PickViewCardDTO] Should Defensively Copy And Cap Answers - When More Than Five Are Supplied")
    void shouldDefensivelyCopyAndCapAnswersWhenMoreThanFiveAreSupplied() {
        List<PickAnswerViewDTO> answers = new ArrayList<>();
        for (int i = 0; i < 7; i++) answers.add(answer(i));

        PickViewCardDTO card = card(answers);
        answers.clear();

        assertThat(card.answeredCategories()).hasSize(5);
        assertThat(card.answeredCategories()).extracting(PickAnswerViewDTO::displayOrder)
                .containsExactly(0, 1, 2, 3, 4);
        assertThat(card.answeredCategories().getFirst().allowedType()).isEqualTo(PickAllowedType.MOVIE);
        assertThat(card.answeredCategories().getFirst().optionMode()).isEqualTo(PickCategoryOptionMode.OPEN);
        assertThatThrownBy(() -> card.answeredCategories().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("[PickViewCardDTO] Should Use An Empty Immutable List - When Answers Are Null")
    void shouldUseEmptyImmutableListWhenAnswersAreNull() {
        PickViewCardDTO card = card(null);

        assertThat(card.answeredCategories()).isEmpty();
        assertThatThrownBy(() -> card.answeredCategories().add(answer(1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static PickViewCardDTO card(List<PickAnswerViewDTO> answers) {
        return new PickViewCardDTO(null, null, null, PickVisibility.PUBLIC, null, null,
                0, 7, answers, null, 0, false);
    }

    private static PickAnswerViewDTO answer(int order) {
        return new PickAnswerViewDTO(null, "Category " + order, null, order,
                PickAllowedType.MOVIE, PickCategoryOptionMode.OPEN,
                null, null, true, true);
    }
}

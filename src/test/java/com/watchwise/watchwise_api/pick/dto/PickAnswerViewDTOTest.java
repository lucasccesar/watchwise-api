package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PickAnswerViewDTOTest {

    @Test
    @DisplayName("[constructor] Should omit target details - When category is unanswered")
    void shouldOmitTargetDetailsWhenCategoryIsUnanswered() {
        PickAnswerViewDTO answer = new PickAnswerViewDTO(UUID.randomUUID(), "Movie", PickCategoryGroup.PRIMARY,
                1, PickAllowedType.MOVIE, PickCategoryOptionMode.OPEN, null, null, false, true);

        assertThat(answer.target()).isNull();
        assertThat(answer.preview()).isNull();
        assertThat(answer.isAnswered()).isFalse();
        assertThat(answer.isValid()).isTrue();
    }
}

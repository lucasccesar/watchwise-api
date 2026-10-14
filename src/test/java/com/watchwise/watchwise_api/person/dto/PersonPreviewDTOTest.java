package com.watchwise.watchwise_api.person.dto;

import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonPreviewDTOTest {

    @Test
    @DisplayName("[PersonPreviewDTO] Should Expose Every Preview Field - When Record Is Constructed")
    void shouldExposeEveryPreviewFieldWhenRecordIsConstructed() {
        PersonPreviewDTO preview = new PersonPreviewDTO(
                "287",
                "Brad Pitt",
                "/brad-pitt.jpg",
                ContentPreviewStatus.AVAILABLE);

        assertThat(preview.personTmdbId()).isEqualTo("287");
        assertThat(preview.name()).isEqualTo("Brad Pitt");
        assertThat(preview.profileUrl()).isEqualTo("/brad-pitt.jpg");
        assertThat(preview.previewStatus()).isEqualTo(ContentPreviewStatus.AVAILABLE);
    }
}

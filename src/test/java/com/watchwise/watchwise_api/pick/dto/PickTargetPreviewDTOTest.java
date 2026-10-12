package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PickTargetPreviewDTOTest {

    @Test
    @DisplayName("[constructor] Should accept a content target - When person is absent")
    void shouldAcceptContentTargetWhenPersonIsAbsent() {
        ContentCardDTO content = content();

        PickTargetPreviewDTO preview = new PickTargetPreviewDTO(content, null, null);

        assertThat(preview.contentCard()).isSameAs(content);
        assertThat(preview.person()).isNull();
    }

    @Test
    @DisplayName("[constructor] Should accept an available person target - When content is absent")
    void shouldAcceptPersonTargetWhenContentIsAbsent() {
        PickPersonPreviewDTO person = person(ContentPreviewStatus.AVAILABLE);

        PickTargetPreviewDTO preview = new PickTargetPreviewDTO(null, person, null);

        assertThat(preview.person()).isSameAs(person);
    }

    @Test
    @DisplayName("[constructor] Should accept a person target with context - When context card is provided")
    void shouldAcceptPersonTargetWithContextWhenContextCardIsProvided() {
        ContentCardDTO context = content();

        PickTargetPreviewDTO preview = new PickTargetPreviewDTO(null, person(ContentPreviewStatus.AVAILABLE), context);

        assertThat(preview.contextCard()).isSameAs(context);
    }

    @Test
    @DisplayName("[constructor] Should accept unavailable person metadata - When person lookup fails")
    void shouldAcceptUnavailablePersonMetadataWhenPersonLookupFails() {
        PickPersonPreviewDTO person = person(ContentPreviewStatus.UNAVAILABLE);

        PickTargetPreviewDTO preview = new PickTargetPreviewDTO(null, person, null);

        assertThat(preview.person().tmdbId()).isEqualTo("42");
        assertThat(preview.person().previewStatus()).isEqualTo(ContentPreviewStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("[constructor] Should reject both primary targets - When content and person are present")
    void shouldRejectBothPrimaryTargetsWhenContentAndPersonArePresent() {
        assertThatThrownBy(() -> new PickTargetPreviewDTO(content(), person(ContentPreviewStatus.AVAILABLE), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[constructor] Should reject context without person - When content is the target")
    void shouldRejectContextWithoutPersonWhenContentIsTheTarget() {
        assertThatThrownBy(() -> new PickTargetPreviewDTO(content(), null, content()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[constructor] Should reject missing primary target - When both targets are absent")
    void shouldRejectMissingPrimaryTargetWhenBothTargetsAreAbsent() {
        assertThatThrownBy(() -> new PickTargetPreviewDTO(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PickPersonPreviewDTO person(ContentPreviewStatus status) {
        return new PickPersonPreviewDTO("42", "Person", "/profile.jpg", status);
    }

    private static ContentCardDTO content() {
        return new ContentCardDTO(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, ContentPreviewStatus.AVAILABLE);
    }
}

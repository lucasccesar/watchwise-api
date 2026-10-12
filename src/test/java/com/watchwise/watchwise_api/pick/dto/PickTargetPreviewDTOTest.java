package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PickTargetPreviewDTOTest {

    @Test
    @DisplayName("[PickTargetPreviewDTO] Should Accept A Content Target - When Person Is Absent")
    void shouldAcceptContentTargetWhenPersonIsAbsent() {
        ContentCardDTO content = content();

        PickTargetPreviewDTO preview = new PickTargetPreviewDTO(content, null, null);

        assertThat(preview.contentCard()).isSameAs(content);
        assertThat(preview.person()).isNull();
    }

    @Test
    @DisplayName("[PickTargetPreviewDTO] Should Accept An Available Person Target - When Content Is Absent")
    void shouldAcceptPersonTargetWhenContentIsAbsent() {
        PickPersonPreviewDTO person = person(ContentPreviewStatus.AVAILABLE);

        PickTargetPreviewDTO preview = new PickTargetPreviewDTO(null, person, null);

        assertThat(preview.person()).isSameAs(person);
    }

    @Test
    @DisplayName("[PickTargetPreviewDTO] Should Accept A Person Target With Context - When Context Card Is Provided")
    void shouldAcceptPersonTargetWithContextWhenContextCardIsProvided() {
        ContentCardDTO context = content();

        PickTargetPreviewDTO preview = new PickTargetPreviewDTO(null, person(ContentPreviewStatus.AVAILABLE), context);

        assertThat(preview.contextCard()).isSameAs(context);
    }

    @Test
    @DisplayName("[PickTargetPreviewDTO] Should Accept Unavailable Person Metadata - When Person Lookup Fails")
    void shouldAcceptUnavailablePersonMetadataWhenPersonLookupFails() {
        PickPersonPreviewDTO person = person(ContentPreviewStatus.UNAVAILABLE);

        PickTargetPreviewDTO preview = new PickTargetPreviewDTO(null, person, null);

        assertThat(preview.person().tmdbId()).isEqualTo("42");
        assertThat(preview.person().previewStatus()).isEqualTo(ContentPreviewStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("[PickTargetPreviewDTO] Should Reject Both Primary Targets - When Content And Person Are Present")
    void shouldRejectBothPrimaryTargetsWhenContentAndPersonArePresent() {
        assertThatThrownBy(() -> new PickTargetPreviewDTO(content(), person(ContentPreviewStatus.AVAILABLE), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[PickTargetPreviewDTO] Should Reject Context Without Person - When Content Is The Target")
    void shouldRejectContextWithoutPersonWhenContentIsTheTarget() {
        assertThatThrownBy(() -> new PickTargetPreviewDTO(content(), null, content()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[PickTargetPreviewDTO] Should Reject Missing Primary Target - When Both Targets Are Absent")
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

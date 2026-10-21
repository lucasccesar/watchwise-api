package com.watchwise.watchwise_api.content.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaReferenceTest {
    @Test
    @DisplayName("constructor Should Reject Invalid Type Coordinates - When Required Or Unused Fields Are Invalid")
    void constructorShouldRejectInvalidTypeCoordinatesWhenRequiredOrUnusedFieldsAreInvalid() {
        assertThatThrownBy(() -> new MediaReference(ContentType.EPISODE, null, null, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaReference(ContentType.EPISODE, null, "42", null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaReference(ContentType.MOVIE, "1", "42", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaReference(ContentType.SEASON, null, "42", 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factories Should Create Valid References - When Type Coordinates Are Supplied")
    void factoriesShouldCreateValidReferencesWhenTypeCoordinatesAreSupplied() {
        assertThat(MediaReference.movie("7").type()).isEqualTo(ContentType.MOVIE);
        assertThat(MediaReference.series("8").tmdbId()).isEqualTo("8");
        assertThat(MediaReference.season("9", 2).seasonNumber()).isEqualTo(2);
        assertThat(MediaReference.episode("9", 2, 3).episodeNumber()).isEqualTo(3);
    }
}

package com.watchwise.watchwise_api.platformimport.model;

import com.watchwise.watchwise_api.content.entity.ContentType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliasQueryTest {

    @Test
    void acceptsSupportedYearBoundaries() {
        assertThatCode(() -> new AliasQuery("Example", 1, ContentType.MOVIE)).doesNotThrowAnyException();
        assertThatCode(() -> new AliasQuery("Example", 9_999, ContentType.MOVIE)).doesNotThrowAnyException();
    }

    @Test
    void rejectsYearAboveSupportedMaximum() {
        assertThatThrownBy(() -> new AliasQuery("Example", 10_000, ContentType.MOVIE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Alias query year must be between 1 and 9999");
    }
}

package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.service.impl.ImportedReviewTextNormalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImportedReviewTextNormalizerTest {

    private final ImportedReviewTextNormalizer normalizer = new ImportedReviewTextNormalizer();

    @Test
    void convertsKnownLineBreaksAndUnescapesEntities() {
        assertThat(normalizer.normalize("First<br>Second &amp; third"))
                .isEqualTo("First\nSecond & third");
    }

    @Test
    void preservesParagraphBoundariesAndUnicode() {
        assertThat(normalizer.normalize("<p>Primeiro <em>ótimo</em></p><p>第二段 🎬</p>"))
                .isEqualTo("Primeiro ótimo\n\n第二段 🎬");
    }

    @Test
    void leavesUnknownTagsAndMathematicalTextLiteral() {
        assertThat(normalizer.normalize("A < 3 > 1; <custom>x</custom> &lt;script&gt;"))
                .isEqualTo("A < 3 > 1; <custom>x</custom> <script>");
    }

    @Test
    void normalizesNullAndBlankTextWithoutInventingContent() {
        assertThat(normalizer.normalize(null)).isNull();
        assertThat(normalizer.normalize(" \t\r\n ")).isEqualTo(" \t\r\n ");
    }

    @Test
    void preservesLineBreaksAlreadyPresentAtTheTextBoundaries() {
        assertThat(normalizer.normalize("\nFirst<br>Second\n"))
                .isEqualTo("\nFirst\nSecond\n");
    }
}

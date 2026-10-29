package com.watchwise.watchwise_api.platformimport.service.impl;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class LetterboxdSeedResourceFingerprintTest {
    private static final String RESOURCE = "import/curated-content-aliases.csv";
    private static final String EXPECTED_SHA256 =
            "9d8442f656d874ce1768ae44564a1e30efc95c6bf8b4cece483ab3885195c447";

    @Test
    void packagedCuratedCatalogHasStableLfFingerprint() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(input).as("packaged curated catalog resource").isNotNull();
            byte[] csv = input.readAllBytes();

            assertThat(new String(csv, StandardCharsets.UTF_8)).doesNotContain("\r");
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv)))
                    .isEqualTo(EXPECTED_SHA256);
        }
    }
}

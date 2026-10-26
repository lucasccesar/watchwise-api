package com.watchwise.watchwise_api.platformimport.parser;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class LetterboxdListHeader {
    public static final String METADATA = "Date,Name,Tags,URL,Description";

    private LetterboxdListHeader() {}

    public static boolean identifiesList(BufferedInputStream input) throws IOException {
        input.mark(256);
        byte[] line = new byte[256];
        int length = 0;
        int value;
        while (length < line.length && (value = input.read()) != -1 && value != '\n') {
            line[length++] = (byte) value;
        }
        input.reset();
        String first = new String(line, 0, length, StandardCharsets.UTF_8)
                .replaceFirst("^\uFEFF", "").stripTrailing();
        return METADATA.equals(first) || first.matches("[0-9]+");
    }
}

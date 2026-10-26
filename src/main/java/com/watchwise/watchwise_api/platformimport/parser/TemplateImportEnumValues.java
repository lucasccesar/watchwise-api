package com.watchwise.watchwise_api.platformimport.parser;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import tools.jackson.databind.JsonNode;

public final class TemplateImportEnumValues {
    private TemplateImportEnumValues() {
    }

    public static <E extends Enum<E>> E enumValue(JsonNode node, String key, Class<E> type, E fallback) {
        JsonNode value = node.get(key);
        if (value == null) {
            if (fallback != null) return fallback;
            throw new BadRequestException(key + " is required");
        }
        if (!value.isTextual()) throw invalid(key);
        String spelling = trimEcmaWhitespace(value.asString());
        StringBuilder asciiUppercase = new StringBuilder(spelling.length());
        for (int index = 0; index < spelling.length(); index++) {
            char character = spelling.charAt(index);
            if (character > 0x7F) throw invalid(key);
            asciiUppercase.append(character >= 'a' && character <= 'z'
                    ? (char) (character - 'a' + 'A') : character);
        }
        try {
            return Enum.valueOf(type, asciiUppercase.toString());
        } catch (IllegalArgumentException exception) {
            throw invalid(key);
        }
    }

    public static boolean isEcmaBlank(String value) {
        return value.codePoints().allMatch(TemplateImportEnumValues::isEcmaWhitespace);
    }

    private static String trimEcmaWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isEcmaWhitespace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isEcmaWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isEcmaWhitespace(int codePoint) {
        return codePoint >= 0x0009 && codePoint <= 0x000D || codePoint == 0x0020
                || codePoint == 0x00A0 || codePoint == 0x1680
                || codePoint >= 0x2000 && codePoint <= 0x200A
                || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F
                || codePoint == 0x205F || codePoint == 0x3000 || codePoint == 0xFEFF;
    }

    private static BadRequestException invalid(String key) {
        return new BadRequestException("Invalid " + key);
    }
}

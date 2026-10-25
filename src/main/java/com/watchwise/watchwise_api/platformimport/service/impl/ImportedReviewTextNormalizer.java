package com.watchwise.watchwise_api.platformimport.service.impl;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.Locale;
import java.util.Set;

@Component
public class ImportedReviewTextNormalizer {
    public static final int MAX_REVIEW_LENGTH = 20_000;
    public static final String REVIEW_TOO_LONG_ISSUE = "REVIEW_TEXT_TOO_LONG";
    public static final String REVIEW_TOO_LONG_MARKER = "_watchwiseReviewTextTooLong";

    private static final Set<String> INLINE_FORMATTING_TAGS = Set.of(
            "i", "em", "b", "strong", "u", "s", "del", "strike", "code", "span", "sup", "sub");

    public String normalize(String value) {
        if (value == null || value.isBlank()) return value;

        String decoded = HtmlUtils.htmlUnescape(value);
        StringBuilder result = new StringBuilder(decoded.length());
        for (int index = 0; index < decoded.length();) {
            if (decoded.charAt(index) != '<') {
                result.append(decoded.charAt(index++));
                continue;
            }

            int closingBracket = decoded.indexOf('>', index + 1);
            if (closingBracket < 0) {
                result.append(decoded.charAt(index++));
                continue;
            }

            String originalTag = decoded.substring(index + 1, closingBracket);
            String tag = originalTag.trim().toLowerCase(Locale.ROOT);
            boolean closing = tag.startsWith("/");
            if (closing) tag = tag.substring(1).trim();
            if (tag.endsWith("/")) tag = tag.substring(0, tag.length() - 1).trim();

            if (tag.equals("br")) {
                result.append('\n');
                index = closingBracket + 1;
            } else if (tag.equals("p")) {
                if (!closing) appendBoundary(result, 2);
                index = closingBracket + 1;
            } else if (INLINE_FORMATTING_TAGS.contains(tag)) {
                index = closingBracket + 1;
            } else {
                result.append(decoded, index, closingBracket + 1);
                index = closingBracket + 1;
            }
        }

        return result.toString();
    }

    private void appendBoundary(StringBuilder result, int lineBreaks) {
        if (result.length() == 0) return;
        int existing = 0;
        for (int index = result.length() - 1; index >= 0 && result.charAt(index) == '\n'; index--) existing++;
        for (int index = existing; index < lineBreaks; index++) result.append('\n');
    }

}

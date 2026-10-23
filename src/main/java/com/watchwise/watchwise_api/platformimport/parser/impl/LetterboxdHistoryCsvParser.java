package com.watchwise.watchwise_api.platformimport.parser.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.parser.CsvRecordReader;
import com.watchwise.watchwise_api.platformimport.parser.LetterboxdItemCsvParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Consumer;

@Component
public class LetterboxdHistoryCsvParser {
    private final LetterboxdItemCsvParser items;

    public LetterboxdHistoryCsvParser(CsvRecordReader csv) { this(new LetterboxdItemCsvParserImpl(csv)); }

    @Autowired
    public LetterboxdHistoryCsvParser(LetterboxdItemCsvParser items) { this.items = items; }

    public void parse(Reader input, ImportReadContext context, String sourcePath, ImportRecordKind kind,
                      Consumer<NormalizedImportRecord> sink) {
        items.parse(input, context, sourcePath, kind, sink);
    }

    public static Integer parseScore(String raw) {
        return parseScore(raw, "Letterboxd CSV", 1);
    }

    static Integer parseScore(String raw, String path, long row) {
        if (raw == null || raw.isBlank()) return null;
        final BigDecimal stars;
        try { stars = new BigDecimal(raw); }
        catch (NumberFormatException ex) { throw failure(path, row, "Rating", "Invalid decimal rating"); }
        final int score;
        try { score = stars.multiply(BigDecimal.valueOf(2)).setScale(0, RoundingMode.UNNECESSARY).intValueExact(); }
        catch (ArithmeticException ex) { throw failure(path, row, "Rating", "Rating must use half-star increments"); }
        if (score < 1 || score > 10) throw failure(path, row, "Rating", "Rating must be between 0.5 and 5");
        return score;
    }

    private static BadRequestException failure(String path, long row, String field, String message) {
        return new BadRequestException(path + " record " + row + " field " + field + ": " + message);
    }
}

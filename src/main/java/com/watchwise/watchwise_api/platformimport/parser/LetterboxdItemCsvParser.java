package com.watchwise.watchwise_api.platformimport.parser;

import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.parser.ParsedListMetadata;

import java.io.Reader;
import java.util.List;
import java.util.function.Consumer;

public interface LetterboxdItemCsvParser {
    void parse(Reader input, ImportReadContext context, String sourcePath, ImportRecordKind kind,
               Consumer<NormalizedImportRecord> sink);

    List<ParsedListMetadata> parseList(Reader input, ImportReadContext context, String sourcePath,
                                       Consumer<NormalizedImportRecord> sink);

    void parseListItems(Reader input, ImportReadContext context, String sourcePath,
                        Consumer<NormalizedImportRecord> sink);
}

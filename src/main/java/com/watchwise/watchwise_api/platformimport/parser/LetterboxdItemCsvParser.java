package com.watchwise.watchwise_api.platformimport.parser;

import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;

import java.io.Reader;
import java.util.function.Consumer;

public interface LetterboxdItemCsvParser {
    void parse(Reader input, ImportReadContext context, String sourcePath, ImportRecordKind kind,
               Consumer<NormalizedImportRecord> sink);
}

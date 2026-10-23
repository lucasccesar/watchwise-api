package com.watchwise.watchwise_api.platformimport.parser;

import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;

import java.io.InputStream;
import java.util.function.Consumer;

public interface TraktItemParser {
    void parse(InputStream json, ImportReadContext context, String sourcePath, ImportRecordKind kind,
               Consumer<NormalizedImportRecord> sink);
}

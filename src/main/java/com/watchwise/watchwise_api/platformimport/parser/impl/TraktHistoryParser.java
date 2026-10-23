package com.watchwise.watchwise_api.platformimport.parser.impl;

import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.parser.TraktItemParser;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.function.Consumer;

@Component
public class TraktHistoryParser {
    private final TraktItemParser items;
    public TraktHistoryParser(ObjectMapper mapper) { this(new TraktItemParserImpl(mapper)); }
    @Autowired
    public TraktHistoryParser(TraktItemParser items) { this.items = items; }
    public void parse(InputStream json, ImportReadContext context, String sourcePath, Consumer<NormalizedImportRecord> sink) {
        items.parse(json, context, sourcePath, ImportRecordKind.DIARY_EVENT, sink);
    }
}

package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizedImportRecordTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("[NormalizedImportRecord] Should Allow Missing Target - When Payload Contains A Typed Alias Query")
    void shouldAllowMissingTargetWhenPayloadContainsTypedAliasQuery() throws Exception {
        var payload = mapper.readTree("""
                {"sourceQuery":{"kind":"CONTENT","title":"Arrival","contentType":"MOVIE"}}
                """);

        var record = new NormalizedImportRecord("item", ImportRecordKind.DIARY_EVENT,
                new ImportSourceKey("namespace", "event", "1"), null, null, payload, 0);

        assertThat(record.target()).isNull();
    }

    @Test
    @DisplayName("[NormalizedImportRecord] Should Reject Missing Target - When Payload Has No Alias Query")
    void shouldRejectMissingTargetWhenPayloadHasNoAliasQuery() throws Exception {
        var payload = mapper.readTree("{\"title\":\"Arrival\"}");

        assertThatThrownBy(() -> new NormalizedImportRecord("item", ImportRecordKind.DIARY_EVENT,
                new ImportSourceKey("namespace", "event", "1"), null, null, payload, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("An unresolved import record requires an alias query");
    }

    @Test
    @DisplayName("[NormalizedImportRecord] Should Reject Missing Target - When Alias Query Is Malformed")
    void shouldRejectMissingTargetWhenAliasQueryIsMalformed() throws Exception {
        var payload = mapper.readTree("{\"query\":{\"kind\":\"UNKNOWN\",\"title\":\"Arrival\"}}");

        assertThatThrownBy(() -> new NormalizedImportRecord("item", ImportRecordKind.DIARY_EVENT,
                new ImportSourceKey("namespace", "event", "1"), null, null, payload, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("An unresolved import record requires an alias query");
    }
}

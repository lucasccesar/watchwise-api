package com.watchwise.watchwise_api.platformimport.parser;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CsvRecordReaderTest {
    private final CsvRecordReader reader = new CsvRecordReader(new ImportLimits());

    private List<List<String>> read(String csv) {
        List<List<String>> records = new ArrayList<>();
        reader.read(new StringReader(csv), records::add);
        return records;
    }

    @Test
    @DisplayName("[read] Should Preserve Quoted Newline And Escaped Quotes - When Parsing RFC4180 Records")
    void shouldPreserveQuotedNewlineAndEscapedQuotesWhenParsingRfc4180Records() {
        assertThat(read("a,b\r\n\"one\r\ntwo\",\"say \"\"hi\"\"\"\r\n"))
                .containsExactly(List.of("a", "b"), List.of("one\r\ntwo", "say \"hi\""));
    }

    @Test
    @DisplayName("[read] Should Remove Only Initial BOM - When Later BOM Occurs In Data")
    void shouldRemoveOnlyInitialBomWhenLaterBomOccursInData() {
        assertThat(read("\uFEFFname\r\na\uFEFFb\r\n"))
                .containsExactly(List.of("name"), List.of("a\uFEFFb"));
    }

    @Test
    @DisplayName("[read] Should Enforce UTF8 Field Boundary - When Field Is Quoted Or Unicode")
    void shouldEnforceUtf8FieldBoundaryWhenFieldIsQuotedOrUnicode() {
        ImportLimits tiny = new ImportLimits(1024, 4096, 2048, 20, 10, 4, 32, 3, 1024, 4096, 2);
        CsvRecordReader bounded = new CsvRecordReader(tiny);
        List<List<String>> records = new ArrayList<>();
        bounded.read(new StringReader("\"éé\""), records::add);
        assertThat(records).containsExactly(List.of("éé"));
        assertThatThrownBy(() -> bounded.read(new StringReader("\"ééa\""), records::add))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("[read] Should Reject Malformed Quotes And Excess Records - When Input Exceeds Contract")
    void shouldRejectMalformedQuotesAndExcessRecordsWhenInputExceedsContract() {
        assertThatThrownBy(() -> read("\"unterminated"))
                .isInstanceOf(BadRequestException.class);
        ImportLimits tiny = new ImportLimits(1024, 4096, 2048, 20, 1, 1024, 32, 3, 1024, 4096, 2);
        assertThatThrownBy(() -> new CsvRecordReader(tiny).read(new StringReader("a\r\nb\r\n"), ignored -> {}))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("[read] Should Propagate Callback UncheckedIOException - When Consumer Fails")
    void shouldPropagateCallbackUncheckedIOExceptionWhenConsumerFails() {
        UncheckedIOException callbackFailure = new UncheckedIOException(new IOException("callback failed"));

        assertThatThrownBy(() -> reader.read(new StringReader("value"), ignored -> {
            throw callbackFailure;
        })).isSameAs(callbackFailure);
    }

    @Test
    @DisplayName("[read] Should Bound Columns And Aggregate Record Bytes - When A CSV Row Is Excessive")
    void shouldBoundColumnsAndAggregateRecordBytesWhenCsvRowIsExcessive() {
        ImportLimits limits = new ImportLimits();
        limits.setMaxCsvColumnsPerRecord(2);
        CsvRecordReader bounded = new CsvRecordReader(limits);
        List<List<String>> records = new ArrayList<>();

        bounded.read(new StringReader("first,\"second, value\"\r\n"), records::add);
        assertThat(records).containsExactly(List.of("first", "second, value"));
        assertThatThrownBy(() -> bounded.read(new StringReader("first,second,third"), ignored -> {}))
                .isInstanceOf(BadRequestException.class);

        limits.setMaxCsvColumnsPerRecord(10);
        limits.setMaxCsvRecordBytes(8);
        bounded.read(new StringReader("12345678"), records::add);
        assertThatThrownBy(() -> bounded.read(new StringReader("123456789"), ignored -> {}))
                .isInstanceOf(BadRequestException.class);
    }
}

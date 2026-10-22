package com.watchwise.watchwise_api.platformimport.parser;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

@Component
public class CsvRecordReader {
    private final ImportLimits limits;

    public CsvRecordReader(ImportLimits limits) { this.limits = limits; }

    public void read(Reader input, Consumer<List<String>> consumer) {
        try {
            PushbackReader bom = new PushbackReader(input, 1);
            int first = bom.read();
            if (first != -1 && first != '\uFEFF') bom.unread(first);
            try (CSVParser parser = CSVFormat.RFC4180.parse(new CsvFieldLimitReader(
                    bom,
                    limits.maxFieldBytes(),
                    limits.maxCsvColumnsPerRecord(),
                    limits.maxCsvRecordBytes()))) {
                Iterator<CSVRecord> records = parser.iterator();
                long count = 0;
                while (true) {
                    CSVRecord record;
                    try {
                        if (!records.hasNext()) break;
                        record = records.next();
                    } catch (UncheckedIOException ex) {
                        throw new BadRequestException("Malformed CSV input");
                    }
                    if (++count > limits.maxRecords()) throw new BadRequestException("CSV record limit exceeded");
                    if (record.size() > limits.maxCsvColumnsPerRecord()) {
                        throw new BadRequestException("CSV record has too many columns");
                    }
                    List<String> cells = new ArrayList<>(record.size());
                    for (String cell : record) {
                        if (cell.getBytes(StandardCharsets.UTF_8).length > limits.maxFieldBytes()) {
                            throw new BadRequestException("CSV field exceeds byte limit");
                        }
                        cells.add(cell);
                    }
                    consumer.accept(List.copyOf(cells));
                }
            }
        } catch (IOException ex) {
            throw new BadRequestException("Malformed CSV input");
        }
    }
}

package com.watchwise.watchwise_api.platformimport.parser;

import com.watchwise.watchwise_api.common.exception.BadRequestException;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;

public class CsvFieldLimitReader extends FilterReader {
    private enum State { START, UNQUOTED, QUOTED, AFTER_QUOTE }
    private final long fieldLimit;
    private final long recordLimit;
    private final int columnLimit;
    private State state = State.START;
    private long fieldBytes;
    private char highSurrogate;
    private char recordHighSurrogate;
    private long recordBytes;
    private int columns = 1;

    public CsvFieldLimitReader(Reader input, long limit) {
        this(input, limit, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    public CsvFieldLimitReader(Reader input, long fieldLimit, int columnLimit, long recordLimit) {
        super(input);
        if (fieldLimit < 1) throw new IllegalArgumentException("Field byte limit must be positive");
        if (columnLimit < 1) throw new IllegalArgumentException("CSV column limit must be positive");
        if (recordLimit < 1) throw new IllegalArgumentException("CSV record byte limit must be positive");
        this.fieldLimit = fieldLimit;
        this.columnLimit = columnLimit;
        this.recordLimit = recordLimit;
    }

    @Override
    public int read() throws IOException {
        int value = in.read();
        if (value == -1) return endOfInput();
        process((char) value);
        return value;
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > buffer.length - length) throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        int count = in.read(buffer, offset, length);
        if (count == -1) return endOfInput();
        for (int i = offset; i < offset + count; i++) process(buffer[i]);
        return count;
    }

    private int endOfInput() {
        if (highSurrogate != 0 || recordHighSurrogate != 0 || state == State.QUOTED) {
            throw new BadRequestException("Malformed CSV field");
        }
        return -1;
    }

    private void process(char character) {
        boolean recordTerminator = (character == '\r' || character == '\n') && state != State.QUOTED;
        if (!recordTerminator) countRecord(character);
        switch (state) {
            case START -> {
                if (character == '"') state = State.QUOTED;
                else if (character == ',') nextColumn();
                else if (character == '\r' || character == '\n') resetRecord();
                else {
                    state = State.UNQUOTED;
                    count(character);
                }
            }
            case UNQUOTED -> {
                if (character == '"') throw new BadRequestException("Malformed CSV quote");
                if (character == ',') {
                    resetField();
                    nextColumn();
                } else if (character == '\r' || character == '\n') resetRecord();
                else count(character);
            }
            case QUOTED -> {
                if (character == '"') state = State.AFTER_QUOTE;
                else count(character);
            }
            case AFTER_QUOTE -> {
                if (character == '"') {
                    count(character);
                    state = State.QUOTED;
                } else if (character == ',') {
                    resetField();
                    nextColumn();
                } else if (character == '\r' || character == '\n') resetRecord();
                else throw new BadRequestException("Malformed CSV quote");
            }
        }
    }

    private void resetField() {
        if (highSurrogate != 0) throw new BadRequestException("Malformed UTF-8 field");
        state = State.START;
        fieldBytes = 0;
    }

    private void count(char character) {
        if (highSurrogate != 0) {
            if (!Character.isLowSurrogate(character)) throw new BadRequestException("Malformed Unicode field");
            highSurrogate = 0;
            fieldBytes += 4;
        } else if (Character.isHighSurrogate(character)) {
            highSurrogate = character;
        } else if (Character.isLowSurrogate(character)) {
            throw new BadRequestException("Malformed Unicode field");
        } else if (character <= 0x7f) fieldBytes++;
        else if (character <= 0x7ff) fieldBytes += 2;
        else fieldBytes += 3;
        if (fieldBytes > fieldLimit) throw new BadRequestException("CSV field exceeds byte limit");
    }

    private void countRecord(char character) {
        long addedBytes;
        if (recordHighSurrogate != 0) {
            if (!Character.isLowSurrogate(character)) throw new BadRequestException("Malformed Unicode record");
            recordHighSurrogate = 0;
            addedBytes = 4;
        } else if (Character.isHighSurrogate(character)) {
            recordHighSurrogate = character;
            return;
        } else if (Character.isLowSurrogate(character)) {
            throw new BadRequestException("Malformed Unicode record");
        } else if (character <= 0x7f) addedBytes = 1;
        else if (character <= 0x7ff) addedBytes = 2;
        else addedBytes = 3;

        if (addedBytes > recordLimit - recordBytes) throw new BadRequestException("CSV record exceeds byte limit");
        recordBytes += addedBytes;
    }

    private void nextColumn() {
        if (columns >= columnLimit) throw new BadRequestException("CSV record has too many columns");
        columns++;
    }

    private void resetRecord() {
        resetField();
        recordBytes = 0;
        columns = 1;
    }
}

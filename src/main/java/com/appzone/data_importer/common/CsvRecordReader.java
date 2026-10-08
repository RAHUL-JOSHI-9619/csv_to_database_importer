package com.appzone.data_importer.common;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming CSV reader used everywhere a CSV is read (header, pre-check scan, import), so all
 * three always agree on how many values a row has.
 *
 * It is deliberately lenient about double quotes, which is where database import wizards break:
 *  - "a, b"                     -> one value  a, b              (quoted field, comma inside)
 *  - "He said ""hello"""        -> He said "hello"              (standard escaped quote)
 *  - He said "hello" to me      -> He said "hello" to me        (quote in the middle of an
 *                                                                unquoted value = plain character)
 *  - "He said "hello" to me"    -> He said "hello" to me        (stray quote inside a quoted
 *                                                                value that is not followed by a
 *                                                                comma/line end = plain character)
 *  - a quoted value may contain line breaks.
 *
 * A UTF-8 byte order mark (Excel adds one) is skipped. Completely blank lines are skipped.
 */
public class CsvRecordReader implements Closeable {

    private final Reader in;
    private int pushedBack = -2;
    private long line = 1;
    private long recordStartLine;
    private boolean lastRecordHadOpenQuote;

    public CsvRecordReader(Reader in) {
        this.in = in;
    }

    public static CsvRecordReader open(Path path) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        Reader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(path), decoder), 1 << 16);
        CsvRecordReader csv = new CsvRecordReader(reader);
        int first = csv.read();
        if (first != '﻿' && first != -1) {
            csv.pushedBack = first;
        }
        return csv;
    }

    /** Line number (1-based, in the file) where the record returned last started. */
    public long getRecordStartLine() {
        return recordStartLine;
    }

    /** True if the last record reached the end of the file inside an unclosed quoted value. */
    public boolean lastRecordHadOpenQuote() {
        return lastRecordHadOpenQuote;
    }

    /** @return the next record's values, or null at the end of the file. */
    public String[] next() throws IOException {
        while (true) {
            String[] record = readRecord();
            if (record == null) {
                return null;
            }
            boolean blankLine = record.length == 1 && record[0].isEmpty();
            if (!blankLine) {
                return record;
            }
        }
    }

    private String[] readRecord() throws IOException {
        int c = read();
        if (c == -1) {
            return null;
        }
        recordStartLine = line;
        lastRecordHadOpenQuote = false;

        List<String> fields = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean inQuotes = false;
        boolean atFieldStart = true;

        while (true) {
            if (c == -1) {
                lastRecordHadOpenQuote = inQuotes;
                fields.add(value.toString());
                return fields.toArray(new String[0]);
            }
            if (inQuotes) {
                if (c == '"') {
                    int next = peek();
                    if (next == '"') {
                        read();
                        value.append('"');
                    } else if (next == ',' || next == '\n' || next == '\r' || next == -1) {
                        inQuotes = false;
                    } else {
                        value.append('"');
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    value.append((char) c);
                }
            } else if (c == ',') {
                fields.add(value.toString());
                value.setLength(0);
                atFieldStart = true;
                c = read();
                continue;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && peek() == '\n') {
                    read();
                }
                line++;
                fields.add(value.toString());
                return fields.toArray(new String[0]);
            } else if (c == '"' && atFieldStart) {
                inQuotes = true;
            } else {
                value.append((char) c);
            }
            atFieldStart = false;
            c = read();
        }
    }

    private int read() throws IOException {
        if (pushedBack != -2) {
            int c = pushedBack;
            pushedBack = -2;
            return c;
        }
        return in.read();
    }

    private int peek() throws IOException {
        if (pushedBack == -2) {
            pushedBack = in.read();
        }
        return pushedBack;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}

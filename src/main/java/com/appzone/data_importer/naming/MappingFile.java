package com.appzone.data_importer.naming;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.appzone.data_importer.common.CsvRecordReader;
import com.appzone.data_importer.common.UserFacingException;

/**
 * The column mapping file: written on page 1, reviewed/edited by the user in Excel, read on page 2.
 *
 *   column_no | raw_column_name | cleaned_column_name | method | needs_review | notes
 *
 * column_no is what page 2 uses to match a mapping row to a data column, so the user may sort or
 * filter the sheet in Excel (e.g. by needs_review) without breaking anything. Only
 * cleaned_column_name is meant to be edited.
 */
public final class MappingFile {

    public static final String COL_NO = "column_no";
    public static final String COL_RAW = "raw_column_name";
    public static final String COL_CLEANED = "cleaned_column_name";
    public static final String COL_METHOD = "method";
    public static final String COL_REVIEW = "needs_review";
    public static final String COL_NOTES = "notes";

    public static final String REVIEW_FLAG = "YES - please check";

    public record Row(int columnNo, String rawName, String cleanedName, String method, boolean needsReview,
                      String notes) {
    }

    private MappingFile() {
    }

    public static void write(Path file, List<Row> rows) throws IOException {
        Files.createDirectories(file.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write('﻿');   // byte order mark: makes Excel open the file as UTF-8
            w.write(String.join(",", COL_NO, COL_RAW, COL_CLEANED, COL_METHOD, COL_REVIEW, COL_NOTES));
            w.write("\r\n");
            for (Row r : rows) {
                w.write(r.columnNo() + "," + escape(r.rawName()) + "," + escape(r.cleanedName()) + ","
                        + escape(r.method()) + "," + (r.needsReview() ? REVIEW_FLAG : "") + ","
                        + escape(r.notes()));
                w.write("\r\n");
            }
        }
    }

    /** Reads the (possibly Excel-edited) file back. Columns are found by their header names. */
    public static List<Row> read(Path file) throws IOException {
        try (CsvRecordReader reader = CsvRecordReader.open(file)) {
            String[] header = reader.next();
            if (header == null) {
                throw new UserFacingException("The mapping file is empty.");
            }
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                index.put(header[i].trim().toLowerCase(), i);
            }
            for (String required : List.of(COL_NO, COL_RAW, COL_CLEANED)) {
                if (!index.containsKey(required)) {
                    throw new UserFacingException("The mapping file has no '" + required + "' column. "
                            + "Use the file created on page 1 (step 1) - keep its header row as it is.");
                }
            }

            List<Row> rows = new ArrayList<>();
            String[] rec;
            while ((rec = reader.next()) != null) {
                String noText = get(rec, index.get(COL_NO)).trim();
                if (noText.isEmpty()) {
                    continue;   // a row the user blanked out in Excel
                }
                int columnNo;
                try {
                    columnNo = (int) Double.parseDouble(noText);   // Excel may save 3 as 3.0
                } catch (NumberFormatException e) {
                    throw new UserFacingException("Mapping file line " + reader.getRecordStartLine()
                            + ": column_no '" + noText + "' is not a number.");
                }
                rows.add(new Row(columnNo,
                        get(rec, index.get(COL_RAW)),
                        get(rec, index.get(COL_CLEANED)).trim(),
                        index.containsKey(COL_METHOD) ? get(rec, index.get(COL_METHOD)) : "",
                        index.containsKey(COL_REVIEW) && !get(rec, index.get(COL_REVIEW)).isBlank(),
                        index.containsKey(COL_NOTES) ? get(rec, index.get(COL_NOTES)) : ""));
            }
            return rows;
        }
    }

    private static String get(String[] rec, int i) {
        return i < rec.length && rec[i] != null ? rec[i] : "";
    }

    /**
     * Quotes when needed. A value starting with = + - @ is prefixed with ' so Excel shows it as text
     * instead of evaluating it as a formula (a raw header like "-amount" or "=total").
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /** Undo the ' added by escape(), so raw names read back compare equal. */
    public static String unescapeRaw(String value) {
        if (value != null && value.length() > 1 && value.charAt(0) == '\''
                && "=+-@".indexOf(value.charAt(1)) >= 0) {
            return value.substring(1);
        }
        return value;
    }
}

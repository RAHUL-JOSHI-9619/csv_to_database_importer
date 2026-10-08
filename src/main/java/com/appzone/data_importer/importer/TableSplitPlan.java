package com.appzone.data_importer.importer;

/**
 * How a very wide CSV (many columns) is split into several smaller tables, so no single CREATE
 * TABLE / INSERT carries hundreds of columns (MySQL "Row size too large", Oracle driver stack
 * overflow on a very long INSERT, heap pressure).
 *
 *  - MAX_COLUMNS_PER_TABLE columns or fewer -> ONE table.
 *  - more -> <name>_part1, <name>_part2, ... each with an extra key column csv_row_id; afterwards
 *    TableMergeTasklet joins them into one table (or a view on MySQL).
 */
public final class TableSplitPlan {

    public static final int MAX_COLUMNS_PER_TABLE = 150;
    public static final String ROW_ID_COLUMN = "csv_row_id";

    private TableSplitPlan() {
    }

    public static boolean needsSplit(int columnCount) {
        return columnCount > MAX_COLUMNS_PER_TABLE;
    }

    public static int partCount(int columnCount) {
        return needsSplit(columnCount) ? (columnCount + MAX_COLUMNS_PER_TABLE - 1) / MAX_COLUMNS_PER_TABLE : 1;
    }

    /** Columns per part (the last part may get a few less). */
    public static int partSize(int columnCount) {
        int parts = partCount(columnCount);
        return (columnCount + parts - 1) / parts;
    }

    /** First column (0-based, included) of a part. */
    public static int startIndex(int columnCount, int part) {
        return part * partSize(columnCount);
    }

    /** Last column (0-based, NOT included) of a part. */
    public static int endIndex(int columnCount, int part) {
        return Math.min(columnCount, (part + 1) * partSize(columnCount));
    }

    public static String partTableName(String baseTableName, int part) {
        return baseTableName + "_part" + (part + 1);
    }

    /** How many characters the "_partN" suffix adds, so the base name can be kept short enough. */
    public static int partSuffixLength(int columnCount) {
        return needsSplit(columnCount) ? ("_part" + partCount(columnCount)).length() : 0;
    }
}

package com.appzone.data_importer.importer;

import java.util.List;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.appzone.data_importer.common.Task;
import com.appzone.data_importer.common.TaskRegistry;
import com.appzone.data_importer.db.Dialect;

/**
 * Runs after a wide CSV was loaded into part tables. Joins them back into ONE table with the name
 * the user gave, listing every column explicitly (works on every supported database):
 *
 *   CREATE TABLE t AS SELECT t1.csv_row_id, t1.a, t1.b, t2.c ... FROM t_part1 t1
 *                     JOIN t_part2 t2 ON t2.csv_row_id = t1.csv_row_id ...
 *   (SQL Server: SELECT ... INTO t FROM ...)
 *
 * If the database cannot hold that many columns in one table (typically MySQL: "Row size too
 * large"), a VIEW with the same name is created instead - a view stores no rows itself, so the
 * limit does not apply. The part tables are then KEPT, because the view reads from them.
 * If neither works the data is still safe in the part tables.
 */
public class TableMergeTasklet implements Tasklet {

    private final String tableName;
    private final List<String> columnNames;
    private final JdbcTemplate jdbc;
    private final Dialect dialect;
    private final Task task;

    public TableMergeTasklet(String tableName, List<String> columnNames, JdbcTemplate jdbc, Dialect dialect,
                             Task task) {
        this.tableName = tableName;
        this.columnNames = columnNames;
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.task = task;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        int columnCount = columnNames.size();
        int parts = TableSplitPlan.partCount(columnCount);
        String rowId = dialect.quote(TableSplitPlan.ROW_ID_COLUMN);

        StringBuilder select = new StringBuilder("SELECT t1.").append(rowId);
        for (int p = 0; p < parts; p++) {
            for (int i = TableSplitPlan.startIndex(columnCount, p); i < TableSplitPlan.endIndex(columnCount, p); i++) {
                select.append(", t").append(p + 1).append('.').append(dialect.quote(columnNames.get(i)));
            }
        }
        StringBuilder from = new StringBuilder(" FROM ")
                .append(dialect.quote(TableSplitPlan.partTableName(tableName, 0))).append(" t1");
        for (int p = 1; p < parts; p++) {
            from.append(" JOIN ").append(dialect.quote(TableSplitPlan.partTableName(tableName, p)))
                .append(" t").append(p + 1)
                .append(" ON t").append(p + 1).append('.').append(rowId).append(" = t1.").append(rowId);
        }

        String target = dialect.quote(tableName);
        String createTable = dialect.isSqlServer()
                ? select + " INTO " + target + from
                : "CREATE TABLE " + target + " AS " + select + from;

        task.info("Joining the " + parts + " part tables into one table '" + tableName + "' (" + columnCount
                + " columns)...");
        long start = System.currentTimeMillis();
        try {
            jdbc.execute(createTable);
            finish(parts, true, start);
            return RepeatStatus.FINISHED;
        } catch (Exception tableError) {
            task.warn("The database could not create one table with " + columnCount + " columns: "
                    + TaskRegistry.rootMessage(tableError));
            dropQuietly("DROP TABLE " + target);
        }

        task.info("Creating a VIEW named '" + tableName + "' instead (a view has no row-size limit)...");
        try {
            jdbc.execute("CREATE VIEW " + target + " AS " + select + from);
            finish(parts, false, start);
        } catch (Exception viewError) {
            task.warn("Could not create a view either: " + TaskRegistry.rootMessage(viewError));
            task.warn("Your data is safe in the part tables " + TableSplitPlan.partTableName(tableName, 0) + " ... "
                    + TableSplitPlan.partTableName(tableName, parts - 1) + ". Join them on the column '"
                    + TableSplitPlan.ROW_ID_COLUMN + "'.");
        }
        return RepeatStatus.FINISHED;
    }

    private void finish(int parts, boolean realTable, long start) {
        Long partRows = jdbc.queryForObject("SELECT COUNT(*) FROM "
                + dialect.quote(TableSplitPlan.partTableName(tableName, 0)), Long.class);
        Long mergedRows = jdbc.queryForObject("SELECT COUNT(*) FROM " + dialect.quote(tableName), Long.class);
        String kind = realTable ? "table" : "view";
        task.info("'" + tableName + "' (" + kind + ") is ready in "
                + String.format("%.1f", (System.currentTimeMillis() - start) / 1000.0) + " s with " + mergedRows + " rows.");

        if (partRows == null || !partRows.equals(mergedRows)) {
            task.warn("'" + tableName + "' has " + mergedRows + " rows but the part tables have " + partRows
                    + ". The part tables are KEPT so you can check.");
            return;
        }
        if (!realTable) {
            task.warn("'" + tableName + "' is a VIEW over the " + parts + " part tables. Query it like a normal "
                    + "table, but do NOT delete the part tables - the view reads from them.");
            return;
        }
        for (int p = 0; p < parts; p++) {
            dropQuietly("DROP TABLE " + dialect.quote(TableSplitPlan.partTableName(tableName, p)));
        }
        task.info("The " + parts + " temporary part tables were deleted. '" + tableName + "' has all columns plus '"
                + TableSplitPlan.ROW_ID_COLUMN + "' (the original row order).");
    }

    private void dropQuietly(String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception ignore) {
            // nothing to drop
        }
    }
}

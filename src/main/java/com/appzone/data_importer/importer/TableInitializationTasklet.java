package com.appzone.data_importer.importer;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import com.appzone.data_importer.common.Task;
import com.appzone.data_importer.db.Dialect;

/**
 * Drops an old table/view with the same name (the user confirmed "replace" in the page) and
 * creates the new table - or, for a wide CSV, the part tables.
 *
 * Existence is checked through DatabaseMetaData instead of "try DROP and ignore the error":
 * on PostgreSQL a failed statement aborts the surrounding transaction, so the CREATE after a
 * swallowed error would fail too.
 */
public class TableInitializationTasklet implements Tasklet {

    private final String tableName;
    private final List<String> columnNames;
    private final List<String> columnTypes;
    private final JdbcTemplate jdbc;
    private final Dialect dialect;
    private final Task task;

    public TableInitializationTasklet(String tableName, List<String> columnNames, List<String> columnTypes,
                                      JdbcTemplate jdbc, Dialect dialect, Task task) {
        this.tableName = tableName;
        this.columnNames = columnNames;
        this.columnTypes = columnTypes;
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.task = task;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        dropIfExists(tableName);
        // Part tables left over from an earlier wide import with the same name.
        for (int p = 0; p < 1000 && dropIfExists(TableSplitPlan.partTableName(tableName, p)); p++) {
            // keep dropping _part1, _part2, ... until one does not exist
        }

        int columnCount = columnNames.size();
        if (!TableSplitPlan.needsSplit(columnCount)) {
            jdbc.execute(createTableSql(tableName, 0, columnCount, false));
            task.info("Created table " + tableName + " with " + columnCount + " columns.");
            return RepeatStatus.FINISHED;
        }

        int parts = TableSplitPlan.partCount(columnCount);
        task.info("The CSV has " + columnCount + " columns (more than " + TableSplitPlan.MAX_COLUMNS_PER_TABLE
                + "), so the data is first loaded into " + parts + " part tables.");
        for (int p = 0; p < parts; p++) {
            int start = TableSplitPlan.startIndex(columnCount, p);
            int end = TableSplitPlan.endIndex(columnCount, p);
            String part = TableSplitPlan.partTableName(tableName, p);
            jdbc.execute(createTableSql(part, start, end, true));
            task.info("Created part table " + part + " (CSV columns " + (start + 1) + " to " + end + ").");
        }
        return RepeatStatus.FINISHED;
    }

    private String createTableSql(String name, int start, int end, boolean withRowId) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(dialect.quote(name)).append(" (");
        if (withRowId) {
            sql.append(dialect.quote(TableSplitPlan.ROW_ID_COLUMN)).append(' ')
               .append(dialect.bigintType()).append(" PRIMARY KEY");
            if (end > start) {
                sql.append(", ");
            }
        }
        for (int i = start; i < end; i++) {
            sql.append(dialect.quote(columnNames.get(i))).append(' ').append(columnTypes.get(i));
            if (i < end - 1) {
                sql.append(", ");
            }
        }
        return sql.append(')').toString();
    }

    /** @return true if a table or view with this name existed (and was dropped). */
    private boolean dropIfExists(String name) {
        String type = objectType(jdbc, name);
        if (type == null) {
            return false;
        }
        jdbc.execute(("VIEW".equals(type) ? "DROP VIEW " : "DROP TABLE ") + dialect.quote(name));
        task.info("Dropped the existing " + type.toLowerCase() + " " + name + ".");
        return true;
    }

    /** "TABLE", "VIEW" or null, trying the name as given, upper case (Oracle) and lower case. */
    static String objectType(JdbcTemplate jdbc, String name) {
        return jdbc.execute((ConnectionCallback<String>) con -> {
            DatabaseMetaData meta = con.getMetaData();
            for (String candidate : new String[] { name, name.toUpperCase(), name.toLowerCase() }) {
                try (ResultSet rs = meta.getTables(con.getCatalog(), con.getSchema(), candidate,
                        new String[] { "TABLE", "VIEW" })) {
                    if (rs.next()) {
                        return "VIEW".equalsIgnoreCase(rs.getString("TABLE_TYPE")) ? "VIEW" : "TABLE";
                    }
                }
            }
            return null;
        });
    }
}

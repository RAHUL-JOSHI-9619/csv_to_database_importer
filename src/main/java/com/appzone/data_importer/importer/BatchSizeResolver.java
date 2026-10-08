package com.appzone.data_importer.importer;

import java.util.Collections;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.appzone.data_importer.common.Task;
import com.appzone.data_importer.db.Dialect;

/**
 * Rows per batch (= per INSERT round trip and per transaction), worked out from the data instead
 * of hard-coded:
 *
 *   - how big one row is (average bytes per row, measured while pre-checking the whole file)
 *   - how much the database accepts in one go (MySQL: max_allowed_packet; others: 64 MB budget)
 *   - MySQL only: its driver rewrites the whole batch into ONE multi-row INSERT string whose size
 *     grows with rows x columns, so wide rows get fewer rows per batch (TARGET_CELLS_PER_BATCH).
 *     Oracle / PostgreSQL / SQL Server send each row as its own bound parameter set, so shrinking
 *     there would only add round trips.
 */
public final class BatchSizeResolver {

    private static final long TARGET_CELLS_PER_BATCH = 300_000;
    private static final int MIN_BATCH_ROWS = 200;
    private static final int MAX_BATCH_ROWS = 10_000;
    private static final long DEFAULT_PACKET_BYTES = 64L * 1024 * 1024;

    private BatchSizeResolver() {
    }

    /**
     * @param columnsPerInsert values ONE row carries in the INSERT really executed: the CSV column
     *                         count, or for a split table the columns of one part + the key column
     */
    public static int resolve(NamedParameterJdbcTemplate jdbc, Dialect dialect, long avgRowBytes,
                              int columnsPerInsert, Task task) {
        long packetBytes = DEFAULT_PACKET_BYTES;
        if (dialect.isMysql()) {
            try {
                Long value = jdbc.queryForObject("SELECT @@max_allowed_packet", Collections.emptyMap(), Long.class);
                if (value != null && value > 0) {
                    packetBytes = value;
                }
            } catch (Exception e) {
                task.warn("Could not read max_allowed_packet, assuming 64 MB.");
            }
        }

        long rowBytes = Math.max(128, avgRowBytes);
        int packetBased = (int) Math.min(Integer.MAX_VALUE, (long) (packetBytes * 0.5) / rowBytes);
        int size;
        if (dialect.rewritesBatchIntoOneStatement()) {
            int cellCap = (int) Math.max(MIN_BATCH_ROWS,
                    Math.min(MAX_BATCH_ROWS, TARGET_CELLS_PER_BATCH / Math.max(1, columnsPerInsert)));
            size = Math.max(MIN_BATCH_ROWS, Math.min(packetBased, cellCap));
        } else {
            size = Math.max(MIN_BATCH_ROWS, Math.min(packetBased, MAX_BATCH_ROWS));
        }

        task.info("Batch size: " + size + " rows per INSERT batch (average row " + rowBytes + " bytes, "
                + columnsPerInsert + " values per row, database packet limit " + packetBytes / (1024 * 1024) + " MB).");
        return size;
    }
}

package com.appzone.data_importer.importer;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;

import com.appzone.data_importer.common.CsvRecordReader;
import com.appzone.data_importer.common.FilePaths;
import com.appzone.data_importer.common.Task;
import com.appzone.data_importer.common.TaskRegistry;
import com.appzone.data_importer.common.UserFacingException;
import com.appzone.data_importer.db.ConnectionRequest;
import com.appzone.data_importer.db.Dialect;
import com.appzone.data_importer.naming.MappingFile;
import com.appzone.data_importer.naming.NameRules;
import com.appzone.data_importer.naming.ReservedSqlKeywords;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Page 2 (VPN ON, no internet needed): creates the table with the names from the (reviewed)
 * mapping file and loads the data CSV with JDBC batch inserts.
 *
 * Everything that can be checked is checked BEFORE the database is touched:
 *   mapping file matches the data file -> names are valid for the REAL database limit ->
 *   every data row has exactly as many values as there are columns.
 * Only then is the old table dropped and the new one created.
 */
@Service
public class ImportService {

    public record Request(String driverClassName, String url, String username, String password,
                          String dataCsvPath, String mappingCsvPath, String tableName) {

        ConnectionRequest toConnection() {
            return new ConnectionRequest(driverClassName, url, username, password, tableName);
        }
    }

    public record Result(String tableName, long rowsImported, int columns, boolean splitIntoParts,
                         String database, double seconds) {
    }

    /** What the pre-check learned from reading the whole data file once. */
    private record ScanResult(long rows, long[] maxBytesPerColumn, long avgRowBytes) {
    }

    private static final int MAX_REPORTED_PROBLEMS = 15;

    public void run(Request request, Task task) throws Exception {
        long started = System.currentTimeMillis();

        Path dataCsv = FilePaths.requireCsvFile(request.dataCsvPath(), "Data CSV file");
        Path mappingCsv = FilePaths.requireCsvFile(request.mappingCsvPath(), "Mapping file");
        if (dataCsv.toAbsolutePath().normalize().equals(mappingCsv.toAbsolutePath().normalize())) {
            throw new UserFacingException("The data CSV and the mapping file are the same file. "
                    + "Paste the data file in the first box and the column mapping file in the second.");
        }

        // ---- 1. Mapping file vs data file ----
        String[] header;
        try (CsvRecordReader reader = CsvRecordReader.open(dataCsv)) {
            header = reader.next();
        }
        if (header == null) {
            throw new UserFacingException("The data CSV file is empty - there is no header row.");
        }
        List<MappingFile.Row> mapping = MappingFile.read(mappingCsv);
        List<MappingFile.Row> ordered = matchMappingToHeader(mapping, header, task);
        task.info("Mapping file matches the data file: " + header.length + " columns.");

        // ---- 2. Connect and learn the real limits ----
        task.info("Connecting to the database...");
        request.toConnection().validate();
        try (HikariDataSource dataSource = createDataSource(request)) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Dialect dialect;
            try (Connection connection = dataSource.getConnection()) {
                dialect = Dialect.detect(connection, jdbc);
            }
            task.info("Connected to " + dialect.productName() + " " + dialect.majorVersion() + "."
                    + dialect.minorVersion() + ". " + dialect.maxLengthReason() + ".");

            int columnCount = header.length;
            boolean split = TableSplitPlan.needsSplit(columnCount);
            String tableName = NameRules.tableName(request.tableName(),
                    dialect.maxColumnNameLength() - TableSplitPlan.partSuffixLength(columnCount));
            if (!tableName.equals(request.tableName().trim())) {
                task.info("Table name will be '" + tableName + "'.");
            }

            // ---- 3. Column names, re-checked against the REAL database ----
            List<String> names = finalColumnNames(ordered, dialect.maxColumnNameLength(), split, task);

            // ---- 4. Every row has the right number of values? ----
            task.info("Checking every row of the data file...");
            ScanResult scan = scan(dataCsv, columnCount, task);
            task.info("Data file OK: " + scan.rows() + " rows, all with " + columnCount + " values.");
            task.setProgressTotal(scan.rows());

            List<String> types = new ArrayList<>();
            for (int i = 0; i < columnCount; i++) {
                types.add(dialect.textType(scan.maxBytesPerColumn()[i]));
            }

            // ---- 5. The batch job ----
            JobExecution execution = runJob(dataSource, jdbc, dialect, dataCsv, tableName, names, types, scan, task);
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                String reason = execution.getAllFailureExceptions().stream()
                        .map(TaskRegistry::rootMessage).distinct().reduce((a, b) -> a + " | " + b)
                        .orElse(execution.getExitStatus().getExitDescription());
                if (reason != null && reason.contains("ORA-00972")) {
                    reason += " -> This Oracle database only allows 30-byte names (its COMPATIBLE parameter is "
                            + "below 12.2). Go back to step 1, choose Oracle 12.1, and create the mapping again.";
                }
                throw new UserFacingException("Import failed: " + reason);
            }

            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + dialect.quote(tableName), Long.class);
            double seconds = (System.currentTimeMillis() - started) / 1000.0;
            if (count == null || count != scan.rows()) {
                task.warn("The CSV has " + scan.rows() + " rows but '" + tableName + "' has " + count + ".");
            }
            task.success("Done: " + count + " rows imported into '" + tableName + "' in "
                    + String.format("%.1f", seconds) + " s.");
            task.succeed(new Result(tableName, count == null ? 0 : count, columnCount, split,
                    dialect.productName(), seconds));
        }
    }

    // ------------------------------------------------------------------------------------------

    /**
     * Puts the mapping rows in data-column order (by column_no, so sorting the sheet in Excel is
     * fine) and checks that the mapping really belongs to this data file.
     */
    private List<MappingFile.Row> matchMappingToHeader(List<MappingFile.Row> mapping, String[] header, Task task) {
        int n = header.length;
        if (mapping.size() != n) {
            throw new UserFacingException("The data file has " + n + " columns but the mapping file has "
                    + mapping.size() + " rows. Did you paste the right files? (Deleting rows from the mapping "
                    + "file is not allowed - every data column needs a name.)");
        }
        MappingFile.Row[] byColumn = new MappingFile.Row[n];
        for (MappingFile.Row row : mapping) {
            int no = row.columnNo();
            if (no < 1 || no > n) {
                throw new UserFacingException("Mapping file: column_no " + no + " is outside 1.." + n + ".");
            }
            if (byColumn[no - 1] != null) {
                throw new UserFacingException("Mapping file: column_no " + no + " appears more than once.");
            }
            byColumn[no - 1] = row;
        }

        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String expected = header[i].trim();
            String inMapping = MappingFile.unescapeRaw(byColumn[i].rawName()).trim();
            if (!expected.equals(inMapping)) {
                mismatches.add("column " + (i + 1) + ": data file has '" + expected + "', mapping file has '"
                        + inMapping + "'");
            }
        }
        if (mismatches.size() > n / 2) {
            throw new UserFacingException("The mapping file does not belong to this data file - "
                    + mismatches.size() + " of " + n + " raw column names are different. First one: "
                    + mismatches.get(0));
        }
        if (!mismatches.isEmpty()) {
            task.warn(mismatches.size() + " raw column name(s) differ between the files (Excel sometimes changes "
                    + "text). Matching by column_no instead:");
            mismatches.stream().limit(5).forEach(m -> task.warn("  " + m));
        }
        return Arrays.asList(byColumn);
    }

    /**
     * The user may have typed new names in Excel. Fixes what can be fixed safely (letters/digits/_,
     * leading digit, reserved word, duplicates) and reports what cannot (empty, too long).
     */
    private List<String> finalColumnNames(List<MappingFile.Row> rows, int maxLen, boolean split, Task task) {
        List<String> problems = new ArrayList<>();
        List<String> adjusted = new ArrayList<>();
        List<String> names = new ArrayList<>();
        NameRules.UniqueNamer namer = new NameRules.UniqueNamer(maxLen);
        if (split) {
            namer.reserve(TableSplitPlan.ROW_ID_COLUMN);
        }

        for (MappingFile.Row row : rows) {
            String typed = row.cleanedName();
            String label = "column " + row.columnNo() + " ('" + MappingFile.unescapeRaw(row.rawName()) + "')";
            String name = NameRules.sanitize(typed);
            if (name.isEmpty()) {
                problems.add(label + ": cleaned_column_name is empty");
                names.add("");
                continue;
            }
            name = NameRules.withLetterStart(name);
            if (ReservedSqlKeywords.isReserved(name)) {
                name = NameRules.withoutReservedWord(name, maxLen);
            }
            if (name.length() > maxLen) {
                problems.add(label + ": '" + name + "' is " + name.length() + " characters, this database allows "
                        + maxLen);
                names.add(name);
                continue;
            }
            String unique = namer.claim(name);
            if (!unique.equals(typed)) {
                adjusted.add(label + ": '" + typed + "' -> '" + unique + "'");
            }
            names.add(unique);
        }

        if (!problems.isEmpty()) {
            StringBuilder msg = new StringBuilder("Please fix these names in the mapping file (cleaned_column_name), "
                    + "save it, and start again:");
            problems.stream().limit(MAX_REPORTED_PROBLEMS).forEach(p -> msg.append("\n  - ").append(p));
            if (problems.size() > MAX_REPORTED_PROBLEMS) {
                msg.append("\n  ... and ").append(problems.size() - MAX_REPORTED_PROBLEMS).append(" more");
            }
            if (problems.stream().anyMatch(p -> p.contains("characters"))) {
                msg.append("\n(Names longer than the limit usually mean a different database version was chosen in "
                        + "step 1. Choose the right one and create the mapping file again.)");
            }
            throw new UserFacingException(msg.toString());
        }
        if (!adjusted.isEmpty()) {
            task.warn(adjusted.size() + " name(s) from the mapping file were adjusted to be valid and unique:");
            adjusted.stream().limit(MAX_REPORTED_PROBLEMS).forEach(a -> task.warn("  " + a));
        }
        return names;
    }

    /** Reads the whole file once: row count, longest value per column, and wrong-width rows. */
    private ScanResult scan(Path dataCsv, int columnCount, Task task) throws IOException {
        long[] maxBytes = new long[columnCount];
        long rows = 0;
        long totalBytes = 0;
        List<String> problems = new ArrayList<>();
        long problemCount = 0;

        try (CsvRecordReader reader = CsvRecordReader.open(dataCsv)) {
            reader.next();   // header
            String[] rec;
            while ((rec = reader.next()) != null) {
                rows++;
                if (rec.length != columnCount) {
                    problemCount++;
                    if (problems.size() < MAX_REPORTED_PROBLEMS) {
                        problems.add("line " + reader.getRecordStartLine() + ": " + rec.length + " values");
                    }
                    continue;
                }
                long rowBytes = rec.length;   // separators
                for (int i = 0; i < columnCount; i++) {
                    long bytes = utf8Length(rec[i].trim());
                    rowBytes += bytes;
                    if (bytes > maxBytes[i]) {
                        maxBytes[i] = bytes;
                    }
                }
                totalBytes += rowBytes;
                if (rows % 1_000_000 == 0) {
                    task.info("  ... " + rows + " rows checked");
                }
            }
            if (reader.lastRecordHadOpenQuote()) {
                throw new UserFacingException("The data file ends inside a quoted value: a double quote opened on "
                        + "line " + reader.getRecordStartLine() + " is never closed. Fix that line and try again.");
            }
        }

        if (problemCount > 0) {
            StringBuilder msg = new StringBuilder(problemCount + " row(s) do not have exactly " + columnCount
                    + " values (the number of columns in the header). Nothing was imported. First ones:");
            problems.forEach(p -> msg.append("\n  - ").append(p));
            msg.append("\n(A value that contains a comma must be inside double quotes.)");
            throw new UserFacingException(msg.toString());
        }
        return new ScanResult(rows, maxBytes, rows == 0 ? 256 : totalBytes / rows);
    }

    private JobExecution runJob(HikariDataSource dataSource, JdbcTemplate jdbc, Dialect dialect, Path dataCsv,
                                String tableName, List<String> names, List<String> types, ScanResult scan,
                                Task task) throws Exception {
        // In-memory job repository: no BATCH_* bookkeeping tables are created in the user's database.
        JobRepository jobRepository = new ResourcelessJobRepository();
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);

        int columnCount = names.size();
        boolean split = TableSplitPlan.needsSplit(columnCount);
        int columnsPerInsert = split ? TableSplitPlan.partSize(columnCount) + 1 : columnCount;
        int batchSize = BatchSizeResolver.resolve(namedJdbc, dialect, scan.avgRowBytes(), columnsPerInsert, task);

        // DDL runs without a surrounding transaction (Oracle/MySQL commit DDL anyway; PostgreSQL
        // would otherwise roll back a CREATE after any failed statement).
        Step createTable = new StepBuilder("createTable", jobRepository)
                .tasklet(new TableInitializationTasklet(tableName, names, types, jdbc, dialect, task))
                .build();

        ItemWriter<String[]> writer = split
                ? splitWriter(jdbc, dialect, tableName, names, task)
                : singleTableWriter(jdbc, dialect, tableName, names, task);

        Step load = new StepBuilder("loadRows", jobRepository)
                .<String[], String[]>chunk(batchSize)
                .reader(new CsvRowItemReader(dataCsv))
                .writer(writer)
                .transactionManager(txManager)
                .build();

        var flow = new JobBuilder("csvImport", jobRepository).start(createTable).next(load);
        if (split) {
            flow = flow.next(new StepBuilder("mergeParts", jobRepository)
                    .tasklet(new TableMergeTasklet(tableName, names, jdbc, dialect, task))
                    .build());
        }
        Job job = flow.build();

        MapJobRegistry registry = new MapJobRegistry();
        registry.register(job);
        TaskExecutorJobOperator operator = new TaskExecutorJobOperator();
        operator.setJobRepository(jobRepository);
        operator.setJobRegistry(registry);
        operator.afterPropertiesSet();

        task.info("Creating the table, then inserting " + scan.rows() + " rows in batches of " + batchSize + "...");
        return operator.start(job, new JobParametersBuilder()
                .addLong("time", System.currentTimeMillis()).toJobParameters());
    }

    /** INSERT INTO t (c1, c2, ...) VALUES (?, ?, ...), one JDBC batch per chunk. */
    private ItemWriter<String[]> singleTableWriter(JdbcTemplate jdbc, Dialect dialect, String tableName,
                                                   List<String> names, Task task) {
        String sql = insertSql(dialect, tableName, names, false);
        AtomicLong written = new AtomicLong();
        return chunk -> {
            List<Object[]> batch = new ArrayList<>(chunk.size());
            for (String[] rec : chunk.getItems()) {
                batch.add(values(rec, 0, rec.length, null));
            }
            jdbc.batchUpdate(sql, batch);
            task.addProgress(batch.size());
            logEvery(written.addAndGet(batch.size()), batch.size(), task);
        };
    }

    /** Wide CSV: every row is cut into pieces, one per part table, all sharing the same csv_row_id. */
    private ItemWriter<String[]> splitWriter(JdbcTemplate jdbc, Dialect dialect, String tableName,
                                             List<String> names, Task task) {
        int columnCount = names.size();
        int parts = TableSplitPlan.partCount(columnCount);
        List<String> sqls = new ArrayList<>();
        for (int p = 0; p < parts; p++) {
            List<String> partNames = names.subList(TableSplitPlan.startIndex(columnCount, p),
                    TableSplitPlan.endIndex(columnCount, p));
            sqls.add(insertSql(dialect, TableSplitPlan.partTableName(tableName, p), partNames, true));
        }
        AtomicLong rowId = new AtomicLong();
        AtomicLong written = new AtomicLong();
        return chunk -> {
            List<? extends String[]> items = chunk.getItems();
            long firstId = rowId.getAndAdd(items.size()) + 1;
            for (int p = 0; p < parts; p++) {
                int start = TableSplitPlan.startIndex(columnCount, p);
                int end = TableSplitPlan.endIndex(columnCount, p);
                List<Object[]> batch = new ArrayList<>(items.size());
                for (int r = 0; r < items.size(); r++) {
                    batch.add(values(items.get(r), start, end, firstId + r));
                }
                jdbc.batchUpdate(sqls.get(p), batch);
            }
            task.addProgress(items.size());
            logEvery(written.addAndGet(items.size()), items.size(), task);
        };
    }

    private static String insertSql(Dialect dialect, String table, List<String> names, boolean withRowId) {
        List<String> cols = new ArrayList<>();
        if (withRowId) {
            cols.add(dialect.quote(TableSplitPlan.ROW_ID_COLUMN));
        }
        names.forEach(n -> cols.add(dialect.quote(n)));
        return "INSERT INTO " + dialect.quote(table) + " (" + String.join(", ", cols) + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")";
    }

    /** Values of one row (trimmed; empty -> NULL), optionally preceded by the row id. */
    private static Object[] values(String[] rec, int start, int end, Long rowId) {
        int offset = rowId == null ? 0 : 1;
        Object[] out = new Object[end - start + offset];
        if (rowId != null) {
            out[0] = rowId;
        }
        for (int i = start; i < end; i++) {
            String v = rec[i].trim();
            out[i - start + offset] = v.isEmpty() ? null : v;
        }
        return out;
    }

    /** One log line per 100,000 rows; the progress bar shows the rest. */
    private static void logEvery(long total, int batchSize, Task task) {
        if (total / 100_000 != (total - batchSize) / 100_000) {
            task.info("  ... " + total + " rows inserted");
        }
    }

    private HikariDataSource createDataSource(Request request) {
        HikariDataSource ds = new HikariDataSource();
        if (request.driverClassName() != null && !request.driverClassName().isBlank()) {
            ds.setDriverClassName(request.driverClassName().trim());
        }
        ds.setJdbcUrl(Dialect.withBatchFlags(request.url().trim()));
        ds.setUsername(request.username().trim());
        ds.setPassword(request.password());
        ds.setMaximumPoolSize(3);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(30_000);
        ds.setPoolName("import-pool");
        return ds;
    }

    private static long utf8Length(String s) {
        long bytes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}

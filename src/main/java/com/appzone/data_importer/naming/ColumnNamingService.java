package com.appzone.data_importer.naming;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.appzone.data_importer.common.CsvRecordReader;
import com.appzone.data_importer.common.FilePaths;
import com.appzone.data_importer.common.Task;
import com.appzone.data_importer.common.UserFacingException;
import com.appzone.data_importer.db.DatabaseType;
import com.appzone.data_importer.importer.TableSplitPlan;

/**
 * Page 1 (internet ON): reads the CSV header, builds a clean name for every column and writes the
 * mapping file. No database connection is needed - the maximum name length comes from the
 * database + version the user picked.
 *
 * Each run is kept as a Session (keyed by the task id), so the page can then edit names, send
 * chosen rows back to AI, and save - the mapping file is rewritten on every save.
 */
@Service
public class ColumnNamingService {

    public record Request(String databaseId, String versionId, String csvPath) {
    }

    public record Result(String sessionId, String mappingFilePath, String dataCsvPath, String databaseLabel,
                         String versionLabel, int maxColumnNameLength, int totalColumns, int flaggedColumns,
                         Map<String, Integer> methodCounts, String aiUnavailableReason,
                         List<MappingFile.Row> rows) {
    }

    /** A name the user changed on the page. source: "ai" (an AI suggestion) or "user" (typed). */
    public record Change(int columnNo, String name, String source) {
    }

    public record Suggestion(int columnNo, String name, String reason) {
    }

    /** Thrown by save() when names are not valid; errors are per column_no. */
    public static class InvalidNamesException extends UserFacingException {
        private final Map<Integer, String> errors;

        InvalidNamesException(Map<Integer, String> errors) {
            super(errors.size() + " name(s) are not valid. Fix the rows marked in red.");
            this.errors = errors;
        }

        public Map<Integer, String> getErrors() {
            return errors;
        }
    }

    /** Working state for one CSV column. */
    private static final class Column {
        final int columnNo;
        final String raw;
        /** The cleaned-up full name that is sent to AI (null when the header was empty). */
        String aiSource;
        String name;
        ShortenMethod method = ShortenMethod.NOT_NEEDED;
        boolean needsReview;
        final List<String> notes = new ArrayList<>();

        Column(int columnNo, String raw) {
            this.columnNo = columnNo;
            this.raw = raw;
        }
    }

    private static final class Session {
        final String id;
        final DatabaseType db;
        final DatabaseType.Version version;
        final int maxLen;
        final Path csv;
        final List<Column> columns;
        Path mappingFile;
        String aiUnavailableReason;

        Session(String id, DatabaseType db, DatabaseType.Version version, Path csv, List<Column> columns) {
            this.id = id;
            this.db = db;
            this.version = version;
            this.maxLen = version.maxColumnNameLength();
            this.csv = csv;
            this.columns = columns;
        }
    }

    private final AiColumnNameShortener aiShortener;
    private final NlpColumnNameShortener nlpShortener;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Value("${app.mapping.folder-name:Data_Importer}")
    private String mappingFolderName;

    public ColumnNamingService(AiColumnNameShortener aiShortener, NlpColumnNameShortener nlpShortener) {
        this.aiShortener = aiShortener;
        this.nlpShortener = nlpShortener;
    }

    // ================================================================================================
    // First run: header -> names -> mapping file
    // ================================================================================================

    public void run(Request request, Task task) throws IOException {
        DatabaseType db = DatabaseType.byId(request.databaseId())
                .orElseThrow(() -> new UserFacingException("Choose a database."));
        DatabaseType.Version version = db.version(request.versionId())
                .orElseThrow(() -> new UserFacingException("Choose the " + db.getLabel() + " version."));
        int maxLen = version.maxColumnNameLength();
        Path csv = FilePaths.requireCsvFile(request.csvPath(), "Data CSV file");

        task.info("Database: " + db.getLabel() + " " + version.label() + " -> maximum column name length is "
                + maxLen + " " + version.unit() + ".");
        task.info("Reading the header row of " + csv + " ...");

        String[] header;
        try (CsvRecordReader reader = CsvRecordReader.open(csv)) {
            header = reader.next();
        }
        if (header == null) {
            throw new UserFacingException("The CSV file is empty - there is no header row.");
        }
        task.info("Found " + header.length + " columns.");

        // ---- 1. Basic cleaning: allowed characters, empty names, names starting with a digit ----
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < header.length; i++) {
            Column c = new Column(i + 1, header[i]);
            String name = NameRules.sanitize(header[i]);
            if (name.isEmpty()) {
                name = NameRules.EMPTY_NAME;
                c.needsReview = true;
                c.notes.add("Empty header: named " + NameRules.EMPTY_NAME + " - please give it a real name");
            } else {
                c.aiSource = name;
                if (!NameRules.startsWithLetter(name)) {
                    name = NameRules.withLetterStart(name);
                    c.notes.add("Started with a digit: '" + NameRules.DIGIT_PREFIX + "' added in front");
                }
            }
            c.name = name;
            columns.add(c);
        }
        Session session = new Session(task.getId(), db, version, csv, columns);

        // ---- 2. Which names are too long? ----
        Set<String> longNames = new LinkedHashSet<>();
        Set<String> fittingNames = new HashSet<>();
        for (Column c : columns) {
            if (c.name.length() > maxLen) {
                longNames.add(c.name);
            } else {
                fittingNames.add(NameRules.withoutReservedWord(c.name, maxLen));
            }
        }
        task.info(longNames.isEmpty()
                ? "No column name is longer than " + maxLen + " characters - no shortening needed."
                : longNames.size() + " column name(s) are longer than " + maxLen + " characters and will be shortened.");

        // ---- 3. AI in rounds, then NLP -> vowel removal -> hashcode for the stubborn ones ----
        Map<String, String> shortNames = new HashMap<>();
        Map<String, ShortenMethod> shortMethods = new HashMap<>();
        Map<String, String> aiFailures = new HashMap<>();
        if (!longNames.isEmpty()) {
            AiColumnNameShortener.Outcome ai = aiShortener.shorten(new ArrayList<>(longNames), maxLen, fittingNames, task);
            session.aiUnavailableReason = ai.unavailableReason();
            aiFailures.putAll(ai.failures());
            ai.shortened().forEach((longName, shortName) -> {
                shortNames.put(longName, shortName);
                shortMethods.put(longName, ShortenMethod.AI);
            });

            List<String> stubborn = longNames.stream().filter(n -> !shortNames.containsKey(n)).toList();
            if (!stubborn.isEmpty()) {
                task.warn(stubborn.size() + " name(s) could not be shortened by AI. Using NLP / vowel removal / "
                        + "hashcode for them - they are FLAGGED. You can rename them in the table below, or send "
                        + "them to AI again with one click.");
            }
            for (String name : stubborn) {
                NlpColumnNameShortener.Attempt attempt = nlpShortener.shorten(name, maxLen);
                if (attempt.name().length() <= maxLen && NameRules.containsLetter(attempt.name())) {
                    shortNames.put(name, attempt.name());
                    shortMethods.put(name, attempt.method());
                } else {
                    shortNames.put(name, NameRules.hashFallback(attempt.name(), name, maxLen));
                    shortMethods.put(name, ShortenMethod.HASHCODE);
                }
            }
        }

        // ---- 4. Final checks in column order: reserved words, duplicates ----
        NameRules.UniqueNamer namer = newNamer(session);
        for (Column c : columns) {
            String name = c.name;
            if (shortNames.containsKey(name)) {
                c.method = shortMethods.get(name);
                c.needsReview |= c.method.needsReview();
                if (aiFailures.containsKey(name)) {
                    c.notes.add(aiFailures.get(name));
                }
                name = shortNames.get(name);
            }
            String notReserved = NameRules.withoutReservedWord(name, maxLen);
            if (!notReserved.equals(name)) {
                c.notes.add("'" + name + "' is a reserved SQL word: '" + NameRules.RESERVED_SUFFIX + "' added");
            }
            String unique = namer.claim(notReserved);
            if (!unique.equals(notReserved)) {
                c.notes.add("Duplicate of another column's name: number added");
            }
            c.name = unique;
        }

        // ---- 5. Mapping file ----
        session.mappingFile = mappingFilePath(csv);
        session.mappingFile = writeMappingFile(session, task);
        sessions.put(session.id, session);

        Result result = toResult(session);
        result.methodCounts().forEach((method, count) -> task.info("  " + method + ": " + count + " column(s)"));
        if (result.flaggedColumns() > 0) {
            task.warn(result.flaggedColumns() + " column name(s) are flagged for review. Check them in the table "
                    + "below (or in the mapping file) before continuing.");
        }
        task.success("Mapping file saved: " + session.mappingFile);
        task.succeed(result);
    }

    // ================================================================================================
    // Editing on the page
    // ================================================================================================

    /**
     * Sends the chosen columns to AI again (same batched rounds as the first run). Only returns
     * suggestions - nothing is saved until the user clicks save.
     *
     * @param currentNames the names currently shown on the page (they may be unsaved edits); AI
     *                     answers that clash with any of them are sent back for another try
     */
    public void aiRename(String sessionId, List<Integer> columnNos, Map<Integer, String> currentNames, Task task) {
        Session session = session(sessionId);
        Set<Integer> chosen = new HashSet<>(columnNos);

        Set<String> taken = new HashSet<>();
        for (Column c : session.columns) {
            if (!chosen.contains(c.columnNo)) {
                taken.add(NameRules.sanitize(currentNames.getOrDefault(c.columnNo, c.name)));
            }
        }
        if (TableSplitPlan.needsSplit(session.columns.size())) {
            taken.add(TableSplitPlan.ROW_ID_COLUMN);
        }

        List<Suggestion> suggestions = new ArrayList<>();
        Map<String, List<Column>> bySource = new LinkedHashMap<>();
        for (Column c : session.columns) {
            if (!chosen.contains(c.columnNo)) {
                continue;
            }
            if (c.aiSource == null) {
                suggestions.add(new Suggestion(c.columnNo, null,
                        "The header is empty, so AI has nothing to work with. Type a name yourself."));
            } else {
                bySource.computeIfAbsent(c.aiSource, k -> new ArrayList<>()).add(c);
            }
        }

        if (!bySource.isEmpty()) {
            task.info("Sending " + bySource.size() + " name(s) to AI (maximum " + session.maxLen + " characters)...");
            AiColumnNameShortener.Outcome ai = aiShortener.shorten(new ArrayList<>(bySource.keySet()), session.maxLen,
                    taken, task);
            bySource.forEach((source, cols) -> {
                String name = ai.shortened().get(source);
                String reason = name != null ? null
                        : ai.failures().getOrDefault(source, ai.unavailableReason() != null
                                ? ai.unavailableReason() : "AI could not shorten it");
                cols.forEach(c -> suggestions.add(new Suggestion(c.columnNo, name, reason)));
            });
            long ok = suggestions.stream().filter(s -> s.name() != null).count();
            task.success("AI suggested " + ok + " of " + suggestions.size() + " name(s). Review them in the table "
                    + "and click Save.");
        } else {
            task.warn("None of the chosen columns can be sent to AI.");
        }
        task.succeed(Map.of("suggestions", suggestions));
    }

    /**
     * Applies the page's edits: every name is checked (format, length, reserved word, duplicates).
     * Nothing is changed unless ALL names are valid; then the mapping file is rewritten.
     */
    public synchronized Result save(String sessionId, List<Change> changes) throws IOException {
        Session session = session(sessionId);
        Map<Integer, Change> byColumn = new HashMap<>();
        for (Change change : changes) {
            byColumn.put(change.columnNo(), change);
        }

        Map<Integer, String> proposed = new LinkedHashMap<>();
        for (Column c : session.columns) {
            Change change = byColumn.get(c.columnNo);
            proposed.put(c.columnNo, change == null ? c.name : change.name() == null ? "" : change.name().trim());
        }
        Map<Integer, String> errors = validate(session, proposed);
        if (!errors.isEmpty()) {
            throw new InvalidNamesException(errors);
        }

        for (Column c : session.columns) {
            Change change = byColumn.get(c.columnNo);
            if (change == null || proposed.get(c.columnNo).equals(c.name)) {
                continue;
            }
            boolean fromAi = "ai".equalsIgnoreCase(change.source());
            c.name = proposed.get(c.columnNo);
            c.method = fromAi ? ShortenMethod.AI : ShortenMethod.EDITED;
            c.needsReview = false;
            c.notes.clear();
            c.notes.add(fromAi ? "Renamed by AI on the page" : "Typed by the user on the page");
        }

        try {
            MappingFile.write(session.mappingFile, rows(session));
        } catch (FileSystemException e) {
            throw new UserFacingException("Could not save " + session.mappingFile.getFileName()
                    + " - it is probably open in Excel. Close it there and click Save again.");
        }
        return toResult(session);
    }

    /** column_no -> problem, for every name that is not valid or not unique. */
    private Map<Integer, String> validate(Session session, Map<Integer, String> names) {
        Map<Integer, String> errors = new LinkedHashMap<>();
        Map<String, List<Integer>> usedBy = new HashMap<>();
        boolean wide = TableSplitPlan.needsSplit(session.columns.size());

        names.forEach((columnNo, name) -> {
            String problem = NameRules.problem(name, session.maxLen);
            if (problem == null && wide && TableSplitPlan.ROW_ID_COLUMN.equals(name)) {
                problem = "'" + name + "' is reserved for wide files";
            }
            if (problem != null) {
                errors.put(columnNo, problem);
            } else {
                usedBy.computeIfAbsent(name, k -> new ArrayList<>()).add(columnNo);
            }
        });
        usedBy.forEach((name, cols) -> {
            if (cols.size() > 1) {
                cols.forEach(no -> errors.put(no, "Duplicate: '" + name + "' is also used by column(s) "
                        + cols.stream().filter(o -> !o.equals(no)).map(String::valueOf)
                                .reduce((a, b) -> a + ", " + b).orElse("")));
            }
        });
        return errors;
    }

    public Path mappingFile(String sessionId) {
        return session(sessionId).mappingFile;
    }

    // ================================================================================================

    private Session session(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new UserFacingException("This result is no longer available (the app was restarted?). "
                    + "Click 'Create column names' again.");
        }
        return session;
    }

    private NameRules.UniqueNamer newNamer(Session session) {
        NameRules.UniqueNamer namer = new NameRules.UniqueNamer(session.maxLen);
        if (TableSplitPlan.needsSplit(session.columns.size())) {
            namer.reserve(TableSplitPlan.ROW_ID_COLUMN);
        }
        return namer;
    }

    private static List<MappingFile.Row> rows(Session session) {
        List<MappingFile.Row> rows = new ArrayList<>();
        for (Column c : session.columns) {
            rows.add(new MappingFile.Row(c.columnNo, c.raw, c.name, c.method.getLabel(), c.needsReview,
                    String.join("; ", c.notes)));
        }
        return rows;
    }

    private static Result toResult(Session s) {
        List<MappingFile.Row> rows = rows(s);
        Map<String, Integer> methodCounts = new LinkedHashMap<>();
        s.columns.forEach(c -> methodCounts.merge(c.method.getLabel(), 1, Integer::sum));
        int flagged = (int) rows.stream().filter(MappingFile.Row::needsReview).count();
        return new Result(s.id, s.mappingFile.toString().replace('\\', '/'), s.csv.toString().replace('\\', '/'),
                s.db.getLabel(), s.version.label(), s.maxLen, rows.size(), flagged, methodCounts,
                s.aiUnavailableReason, rows);
    }

    /** <home>/Downloads/Data_Importer/<csv name>/column_mapping_<csv name>.csv */
    private Path mappingFilePath(Path csv) {
        String fileName = csv.getFileName().toString();
        String base = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        return Paths.get(System.getProperty("user.home"), "Downloads", mappingFolderName, base)
                .resolve("column_mapping_" + base + ".csv");
    }

    private Path writeMappingFile(Session session, Task task) throws IOException {
        Path target = session.mappingFile;
        try {
            MappingFile.write(target, rows(session));
            return target;
        } catch (FileSystemException e) {
            // Usually: the previous mapping file is still open in Excel, which locks it.
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String name = target.getFileName().toString().replace(".csv", "_" + stamp + ".csv");
            Path alternative = target.resolveSibling(name);
            task.warn("Could not overwrite " + target + " (is it open in Excel?). Saving as " + name + " instead.");
            MappingFile.write(alternative, rows(session));
            return alternative;
        }
    }
}

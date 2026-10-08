package com.appzone.data_importer.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the importer needs to know about the connected database: how to quote names, which column
 * types to use and the real maximum column name length. Detected from the live connection on
 * page 2, never from what the user picked in a drop-down.
 */
public record Dialect(DatabaseType type, String productName, int majorVersion, int minorVersion,
                      int maxColumnNameLength, String maxLengthReason) {

    /** Used only when the database is not one of the known ones. The safest (smallest) value. */
    private static final int UNKNOWN_DB_MAX_LENGTH = 30;

    public static Dialect detect(Connection connection, JdbcTemplate jdbc) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String product = meta.getDatabaseProductName();
        String lower = product.toLowerCase();
        int major = meta.getDatabaseMajorVersion();
        int minor = meta.getDatabaseMinorVersion();

        if (lower.contains("oracle")) {
            boolean versionAllows128 = major > 12 || (major == 12 && minor >= 2);
            if (!versionAllows128) {
                return new Dialect(DatabaseType.ORACLE, product, major, minor, 30, "Oracle 12.1 or older: 30 bytes");
            }
            // 12.2+ allows 128 bytes ONLY when COMPATIBLE >= 12.2 (needs SELECT on V$PARAMETER).
            try {
                String compatible = jdbc.queryForObject(
                        "SELECT value FROM v$parameter WHERE name = 'compatible'", String.class);
                String[] parts = compatible.trim().split("\\.");
                int cMajor = Integer.parseInt(parts[0]);
                int cMinor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                boolean compatAllows128 = cMajor > 12 || (cMajor == 12 && cMinor >= 2);
                return compatAllows128
                        ? new Dialect(DatabaseType.ORACLE, product, major, minor, 128,
                                "Oracle " + major + "." + minor + " with COMPATIBLE " + compatible + ": 128 bytes")
                        : new Dialect(DatabaseType.ORACLE, product, major, minor, 30,
                                "Oracle " + major + "." + minor + " but COMPATIBLE is " + compatible + ": 30 bytes");
            } catch (Exception e) {
                // Most users cannot read V$PARAMETER. Oracle 12.2+ databases almost always run with
                // COMPATIBLE >= 12.2, so assume 128 - if it is wrong, CREATE TABLE reports
                // ORA-00972 and the log below explains what to do.
                return new Dialect(DatabaseType.ORACLE, product, major, minor, 128,
                        "Oracle " + major + "." + minor + ": 128 bytes (could not read the COMPATIBLE parameter, "
                                + "assumed 12.2 or higher)");
            }
        }
        if (lower.contains("mysql") || lower.contains("mariadb")) {
            return new Dialect(DatabaseType.MYSQL, product, major, minor, 64, "MySQL: 64 characters");
        }
        if (lower.contains("postgresql")) {
            return new Dialect(DatabaseType.POSTGRESQL, product, major, minor, 63, "PostgreSQL: 63 bytes");
        }
        if (lower.contains("microsoft") || lower.contains("sql server")) {
            return new Dialect(DatabaseType.SQLSERVER, product, major, minor, 128, "SQL Server: 128 characters");
        }
        return new Dialect(null, product, major, minor, UNKNOWN_DB_MAX_LENGTH,
                "Unknown database: using the safe length " + UNKNOWN_DB_MAX_LENGTH);
    }

    public boolean isOracle() {
        return type == DatabaseType.ORACLE;
    }

    public boolean isMysql() {
        return type == DatabaseType.MYSQL;
    }

    public boolean isPostgres() {
        return type == DatabaseType.POSTGRESQL;
    }

    public boolean isSqlServer() {
        return type == DatabaseType.SQLSERVER;
    }

    /**
     * Oracle: left unquoted, so Oracle stores it in upper case and the user can write
     * SELECT customer_name FROM my_table without quotes. Every name we create is a-z/0-9/_, starts
     * with a letter and is not a reserved word, so that is always valid.
     * The others: quoted, and kept lower case, which is also how they are queried without quotes.
     */
    public String quote(String identifier) {
        if (isOracle()) {
            return identifier.toUpperCase();
        }
        if (isSqlServer()) {
            return "[" + identifier + "]";
        }
        if (isMysql()) {
            return "`" + identifier + "`";
        }
        return "\"" + identifier + "\"";
    }

    /** Column type for a text column whose longest value is maxBytes (UTF-8) long. */
    public String textType(long maxBytes) {
        if (isOracle()) {
            return maxBytes <= 4000 ? "VARCHAR2(4000)" : "CLOB";
        }
        if (isSqlServer()) {
            return "NVARCHAR(MAX)";
        }
        if (isMysql()) {
            if (maxBytes <= 65_535) {
                return "TEXT";
            }
            return maxBytes <= 16_777_215 ? "MEDIUMTEXT" : "LONGTEXT";
        }
        return "TEXT";
    }

    public String bigintType() {
        return isOracle() ? "NUMBER(19)" : "BIGINT";
    }

    /** MySQL's driver rewrites a whole batch into ONE multi-row INSERT string (see BatchSizeResolver). */
    public boolean rewritesBatchIntoOneStatement() {
        return isMysql();
    }

    /** Adds the driver flags that make JDBC batching fast. Never overrides a flag the user set. */
    public static String withBatchFlags(String url) {
        String lower = url.toLowerCase();
        StringBuilder out = new StringBuilder(url);
        if (lower.startsWith("jdbc:mysql") || lower.startsWith("jdbc:mariadb")) {
            // rewriteBatchedStatements: many INSERTs -> one multi-row INSERT (the big speed-up).
            // useServerPrepStmts=false: the rewrite only works reliably with client-side statements.
            appendParam(out, lower, "rewriteBatchedStatements", "true", "?", "&");
            appendParam(out, lower, "useServerPrepStmts", "false", "?", "&");
            appendParam(out, lower, "cachePrepStmts", "true", "?", "&");
        } else if (lower.startsWith("jdbc:postgresql")) {
            appendParam(out, lower, "reWriteBatchedInserts", "true", "?", "&");
        } else if (lower.startsWith("jdbc:sqlserver")) {
            // Uses the bulk-copy API for executeBatch() - much faster than one RPC per row.
            appendParam(out, lower, "useBulkCopyForBatchInsert", "true", ";", ";");
        }
        return out.toString();
    }

    private static void appendParam(StringBuilder url, String lowerOriginal, String name, String value,
                                    String firstSeparator, String separator) {
        if (lowerOriginal.contains(name.toLowerCase() + "=")) {
            return;
        }
        String current = url.toString();
        boolean hasParams = ";".equals(firstSeparator)
                ? current.indexOf(';') >= 0
                : current.indexOf('?') >= 0;
        if (!hasParams) {
            url.append(firstSeparator);
        } else if (!current.endsWith(separator)) {
            url.append(separator);
        }
        url.append(name).append('=').append(value);
    }
}

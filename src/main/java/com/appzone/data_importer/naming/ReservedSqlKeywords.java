package com.appzone.data_importer.naming;

import java.util.Set;

/**
 * A column called ORDER, NUMBER, DATE, USER ... makes CREATE TABLE fail on some databases (Oracle
 * names are left unquoted) and is painful in every later SQL query. Such names get a "_col" suffix.
 *
 * This is the union of the reserved words of every supported database (Oracle, MySQL, PostgreSQL,
 * SQL Server) plus ANSI SQL and common type names. Being over-cautious only costs a suffix.
 */
public final class ReservedSqlKeywords {

    private ReservedSqlKeywords() {
    }

    private static final Set<String> RESERVED_WORDS = Set.of(
            // ---- DML / DQL ----
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "FROM", "WHERE", "INTO",
            "VALUES", "SET", "JOIN", "INNER", "OUTER", "LEFT", "RIGHT", "FULL", "CROSS",
            "ON", "USING", "GROUP", "ORDER", "BY", "HAVING", "UNION", "INTERSECT",
            "EXCEPT", "MINUS", "ALL", "DISTINCT", "AS", "AND", "OR", "NOT", "NULL",
            "IS", "IN", "LIKE", "BETWEEN", "EXISTS", "ANY", "SOME", "CASE", "WHEN",
            "THEN", "ELSE", "END", "WITH", "RECURSIVE", "LIMIT", "OFFSET", "TOP",
            "FETCH", "FIRST", "NEXT", "ROWS", "ROW", "OVER", "PARTITION", "MATCHED",
            "NATURAL", "LATERAL", "WINDOW", "QUALIFY", "PIVOT", "UNPIVOT", "DESC", "ASC",

            // ---- DDL ----
            "CREATE", "DROP", "ALTER", "TABLE", "VIEW", "INDEX", "SEQUENCE", "SYNONYM",
            "DATABASE", "SCHEMA", "TABLESPACE", "COLUMN", "COLUMNS", "CONSTRAINT",
            "PRIMARY", "FOREIGN", "KEY", "KEYS", "REFERENCES", "UNIQUE", "CHECK", "DEFAULT",
            "CASCADE", "RESTRICT", "TRUNCATE", "RENAME", "COMMENT", "ADD", "MODIFY",

            // ---- Transactions / procedural / security ----
            "COMMIT", "ROLLBACK", "TRANSACTION", "SAVEPOINT", "BEGIN", "DECLARE",
            "PROCEDURE", "FUNCTION", "TRIGGER", "RETURN", "RETURNS", "CALL", "EXEC",
            "EXECUTE", "GRANT", "REVOKE", "USE", "PRINT", "RAISERROR", "WAITFOR",
            "OUTPUT", "IDENTITY", "AUTOINCREMENT", "AUTO_INCREMENT", "PUBLIC", "PRIVILEGES",
            "LOOP", "WHILE", "REPEAT", "LEAVE", "ITERATE", "SIGNAL", "IF", "GOTO", "BREAK",
            "CONTINUE", "OPEN", "CLOSE", "CURSOR", "HOLDLOCK", "READ", "WRITE", "REPLACE",

            // ---- Data types (very commonly mistaken for column names) ----
            "NUMBER", "NUMERIC", "DECIMAL", "INT", "INTEGER", "SMALLINT", "BIGINT",
            "TINYINT", "MEDIUMINT", "FLOAT", "DOUBLE", "REAL", "CHAR", "NCHAR", "VARCHAR",
            "VARCHAR2", "NVARCHAR", "NVARCHAR2", "TEXT", "NTEXT", "CLOB", "BLOB", "LONG", "RAW",
            "BINARY", "VARBINARY", "BOOLEAN", "BOOL", "BIT", "DATE", "TIME",
            "TIMESTAMP", "DATETIME", "DATETIME2", "INTERVAL", "YEAR", "MONTH", "DAY",
            "HOUR", "MINUTE", "SECOND", "ZONE", "CHARACTER", "VARYING", "UNSIGNED",

            // ---- Oracle-specific reserved words ----
            "ACCESS", "AUDIT", "CLUSTER", "COMPRESS", "EXCLUSIVE", "FILE", "IDENTIFIED",
            "IMMEDIATE", "INCREMENT", "INITIAL", "MAXEXTENTS", "MLSLABEL", "NOAUDIT",
            "NOCOMPRESS", "NOWAIT", "OFFLINE", "ONLINE", "OPTION", "PCTFREE", "PRIOR",
            "RESOURCE", "ROWLABEL", "SHARE", "SIZE", "SUCCESSFUL", "UID", "VALIDATE",

            // ---- MySQL-specific reserved words ----
            "DIV", "MOD", "XOR", "RANGE", "RELEASE", "REQUIRE", "SPATIAL", "SQL",
            "STARTING", "TERMINATED", "TRAILING", "LEADING", "UNDO", "UNLOCK", "USAGE",
            "ZEROFILL", "CONDITION", "DATABASES", "SCHEMAS", "SHOW", "STATUS", "OPTIMIZE",
            "LOAD", "LINES", "FIELDS", "OUTFILE", "INFILE", "FORCE", "IGNORE", "LOW_PRIORITY",
            "HIGH_PRIORITY", "DELAYED", "STRAIGHT_JOIN", "REGEXP", "RLIKE", "SEPARATOR",

            // ---- Functions / misc identifiers reserved on at least one DB ----
            "CAST", "CONVERT", "COALESCE", "NULLIF", "EXTRACT", "DUAL", "ROWID",
            "ROWNUM", "LEVEL", "CONNECT", "START", "USER", "SESSION", "CURRENT",
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER",
            "SESSION_USER", "SYSTEM_USER", "SYSDATE", "TRUE", "FALSE", "UNKNOWN", "LOCK",
            "MODE", "EXPLAIN", "ANALYZE", "VACUUM", "TO", "FOR", "OF", "GO", "BACKUP",
            "RESTORE", "BROWSE", "BULK", "CHECKPOINT", "DBCC", "DENY", "DISK", "DUMP",
            "ERRLVL", "FILLFACTOR", "FREETEXT", "KILL", "LINENO", "NOCHECK", "OFF",
            "OPENQUERY", "PERCENT", "PLAN", "PROC", "READTEXT", "RECONFIGURE", "REPLICATION",
            "RULE", "SAVE", "SETUSER", "SHUTDOWN", "STATISTICS", "TEXTSIZE", "TRAN",
            "TSEQUAL", "UPDATETEXT", "WRITETEXT", "ESCAPE", "COLLATE", "ARRAY", "ONLY",
            "SIMILAR", "VARIADIC", "VERBOSE", "ANALYSE", "SYMMETRIC", "ASYMMETRIC", "BOTH",
            "PLACING", "RETURNING", "TABLESAMPLE", "FREEZE", "ILIKE", "ISNULL", "NOTNULL",
            "OVERLAPS", "AUTHORIZATION", "CONCURRENTLY", "DEFERRABLE", "INITIALLY",
            "LOCALTIME", "LOCALTIMESTAMP", "CURRENT_ROLE", "CURRENT_SCHEMA", "CURRENT_CATALOG"
    );

    /** All reserved words, lower case (sent to the page so it can check names while the user types). */
    public static java.util.List<String> allLowerCase() {
        return RESERVED_WORDS.stream().map(String::toLowerCase).sorted().toList();
    }

    public static boolean isReserved(String identifier) {
        return identifier != null && RESERVED_WORDS.contains(identifier.toUpperCase());
    }
}

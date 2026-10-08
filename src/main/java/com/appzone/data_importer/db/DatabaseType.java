package com.appzone.data_importer.db;

import java.util.List;
import java.util.Optional;

/**
 * Every database the app supports, with the versions the user can pick on page 1 and the maximum
 * column name length of each. Page 1 has no database connection (the VPN is off), so this table
 * is the only way to know the limit there. Page 2 then reads the real limit from the connected
 * database and refuses to import if the names do not fit (e.g. the wrong version was picked).
 *
 * Column names produced by this app only contain a-z, 0-9 and _, so 1 character = 1 byte and the
 * "bytes" limits of Oracle/PostgreSQL can be compared with the name length directly.
 */
public enum DatabaseType {

    MYSQL("mysql", "MySQL", "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://HOST:3306/DATABASE_NAME",
            List.of(
                    new Version("mysql-5.7", "5.7 and older (ended)", 64, "characters", null),
                    new Version("mysql-8.0", "8.0 (ended)", 64, "characters", null),
                    new Version("mysql-8.4", "8.4 LTS", 64, "characters", null),
                    new Version("mysql-9.0-9.6", "9.0 to 9.6 (Innovation)", 64, "characters", null),
                    new Version("mysql-9.7", "9.7 LTS", 64, "characters", null))),

    ORACLE("oracle", "Oracle", "oracle.jdbc.OracleDriver",
            "jdbc:oracle:thin:@//HOST:1521/SERVICE_NAME",
            List.of(
                    new Version("oracle-12.1", "12.1 and earlier (7, 8i, 9i, 10g, 11g, 12.1)", 30, "bytes",
                            "Applies to tables, columns, indexes and constraints."),
                    new Version("oracle-12.2", "12.2, 18c, 19c, 21c, 23ai and newer", 128, "bytes",
                            "Only if the COMPATIBLE parameter is 12.2 or higher. If it is lower, choose 12.1."))),

    POSTGRESQL("postgresql", "PostgreSQL", "org.postgresql.Driver",
            "jdbc:postgresql://HOST:5432/DATABASE_NAME",
            List.of(
                    new Version("postgresql-all", "All versions", 63, "bytes", null))),

    SQLSERVER("sqlserver", "SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "jdbc:sqlserver://HOST:1433;databaseName=DATABASE_NAME;encrypt=true;trustServerCertificate=true",
            List.of(
                    new Version("sqlserver-all", "All versions", 128, "characters", null)));

    public record Version(String id, String label, int maxColumnNameLength, String unit, String note) {
    }

    private final String id;
    private final String label;
    private final String driverClassName;
    private final String urlTemplate;
    private final List<Version> versions;

    DatabaseType(String id, String label, String driverClassName, String urlTemplate, List<Version> versions) {
        this.id = id;
        this.label = label;
        this.driverClassName = driverClassName;
        this.urlTemplate = urlTemplate;
        this.versions = versions;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public String getUrlTemplate() {
        return urlTemplate;
    }

    public List<Version> getVersions() {
        return versions;
    }

    public static Optional<DatabaseType> byId(String id) {
        for (DatabaseType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    public Optional<Version> version(String versionId) {
        return versions.stream().filter(v -> v.id().equalsIgnoreCase(versionId)).findFirst();
    }
}

package com.appzone.data_importer.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import com.appzone.data_importer.common.UserFacingException;

/** Connection details typed in on page 2. tableName is only used by the "table exists" check. */
public record ConnectionRequest(String driverClassName, String url, String username, String password,
                                String tableName) {

    private static final int LOGIN_TIMEOUT_SECONDS = 20;

    public void validate() {
        if (url == null || url.isBlank()) {
            throw new UserFacingException("Database URL is required.");
        }
        if (username == null || username.isBlank()) {
            throw new UserFacingException("Username is required.");
        }
        if (password == null) {
            throw new UserFacingException("Password is required.");
        }
    }

    public Connection open() throws SQLException {
        validate();
        if (driverClassName != null && !driverClassName.isBlank()) {
            try {
                Class.forName(driverClassName.trim());
            } catch (ClassNotFoundException e) {
                throw new UserFacingException("JDBC driver class not found: " + driverClassName.trim());
            }
        }
        // A VPN that silently drops packets would otherwise make this hang for minutes.
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        return DriverManager.getConnection(url.trim(), username.trim(), password);
    }
}

package com.appzone.data_importer.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.appzone.data_importer.common.TaskRegistry;
import com.appzone.data_importer.common.UserFacingException;
import com.appzone.data_importer.importer.TableSplitPlan;

@RestController
@RequestMapping("/api/database")
public class DatabaseController {

    /** Drop-down contents for both pages (databases, versions, limits, drivers, sample URLs). */
    @GetMapping("/catalog")
    public List<Map<String, Object>> catalog() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DatabaseType type : DatabaseType.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", type.getId());
            item.put("label", type.getLabel());
            item.put("driverClassName", type.getDriverClassName());
            item.put("urlTemplate", type.getUrlTemplate());
            item.put("versions", type.getVersions());
            list.add(item);
        }
        return list;
    }

    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody ConnectionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        try (Connection connection = request.open()) {
            if (!connection.isValid(10)) {
                body.put("message", "Connected, but the database did not answer the validation check.");
                return ResponseEntity.status(502).body(body);
            }
            Dialect dialect = Dialect.detect(connection,
                    new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
            body.put("message", "Connected to " + dialect.productName() + " " + dialect.majorVersion() + "."
                    + dialect.minorVersion() + ". Maximum column name length: " + dialect.maxColumnNameLength() + ".");
            body.put("database", dialect.productName());
            body.put("version", dialect.majorVersion() + "." + dialect.minorVersion());
            body.put("maxColumnNameLength", dialect.maxColumnNameLength());
            return ResponseEntity.ok(body);
        } catch (UserFacingException e) {
            body.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(body);
        } catch (Exception e) {
            body.put("message", "Connection failed: " + TaskRegistry.rootMessage(e));
            return ResponseEntity.badRequest().body(body);
        }
    }

    /**
     * Used before an import starts, only to decide whether to ask "replace the existing table?".
     * Checks tables and views (a wide MySQL import leaves a VIEW), as typed, upper and lower case.
     */
    @PostMapping("/check-table")
    public ResponseEntity<Map<String, Object>> checkTable(@RequestBody ConnectionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (request.tableName() == null || request.tableName().isBlank()) {
            body.put("message", "Table name is required.");
            return ResponseEntity.badRequest().body(body);
        }
        try (Connection connection = request.open()) {
            String name = request.tableName().trim();
            body.put("exists", objectExists(connection, name));
            body.put("partTablesExist", objectExists(connection, TableSplitPlan.partTableName(name, 0)));
            body.put("tableName", name);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            body.put("message", "Could not check for an existing table: " + TaskRegistry.rootMessage(e));
            return ResponseEntity.badRequest().body(body);
        }
    }

    public static boolean objectExists(Connection connection, String name) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String[] types = { "TABLE", "VIEW" };
        for (String candidate : new String[] { name, name.toUpperCase(), name.toLowerCase() }) {
            try (ResultSet rs = meta.getTables(connection.getCatalog(), connection.getSchema(), candidate, types)) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }
}

package com.appzone.data_importer.naming;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.appzone.data_importer.common.Task;
import com.appzone.data_importer.common.TaskRegistry;
import com.appzone.data_importer.common.UserFacingException;
import com.appzone.data_importer.importer.TableSplitPlan;

@RestController
@RequestMapping("/api/naming")
public class NamingController {

    public record AiRenameRequest(List<Integer> columnNos, Map<Integer, String> currentNames) {
    }

    public record SaveRequest(List<ColumnNamingService.Change> changes) {
    }

    private final TaskRegistry taskRegistry;
    private final ColumnNamingService namingService;
    private final AiColumnNameShortener aiShortener;

    public NamingController(TaskRegistry taskRegistry, ColumnNamingService namingService,
                            AiColumnNameShortener aiShortener) {
        this.taskRegistry = taskRegistry;
        this.namingService = namingService;
        this.aiShortener = aiShortener;
    }

    /** Whether AI can be used at all (key present, not switched off) - shown on page 1. */
    @GetMapping("/ai-status")
    public Map<String, Object> aiStatus() {
        String reason = aiShortener.unavailableReason();
        return reason == null ? Map.of("available", true) : Map.of("available", false, "reason", reason);
    }

    /** The rules the page checks names against while the user types (the server checks again on save). */
    @GetMapping("/rules")
    public Map<String, Object> rules() {
        return Map.of("reservedWords", ReservedSqlKeywords.allLowerCase(),
                "rowIdColumn", TableSplitPlan.ROW_ID_COLUMN,
                "maxColumnsPerTable", TableSplitPlan.MAX_COLUMNS_PER_TABLE);
    }

    @PostMapping("/start")
    public Map<String, String> start(@RequestBody ColumnNamingService.Request request) {
        Task task = taskRegistry.start("naming", t -> {
            try {
                namingService.run(request, t);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        return Map.of("taskId", task.getId());
    }

    /** Sends chosen rows to AI again; poll the returned task for the suggestions. */
    @PostMapping("/{sessionId}/ai-rename")
    public Map<String, String> aiRename(@PathVariable String sessionId, @RequestBody AiRenameRequest request) {
        Map<Integer, String> current = request.currentNames() == null ? new HashMap<>() : request.currentNames();
        Task task = taskRegistry.start("ai-rename",
                t -> namingService.aiRename(sessionId, request.columnNos(), current, t));
        return Map.of("taskId", task.getId());
    }

    @PostMapping("/{sessionId}/save")
    public ResponseEntity<Object> save(@PathVariable String sessionId, @RequestBody SaveRequest request) {
        try {
            return ResponseEntity.ok(namingService.save(sessionId,
                    request.changes() == null ? List.of() : request.changes()));
        } catch (ColumnNamingService.InvalidNamesException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", e.getMessage());
            body.put("errors", e.getErrors());
            return ResponseEntity.badRequest().body(body);
        } catch (UserFacingException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Could not save: " + TaskRegistry.rootMessage(e)));
        }
    }

    /** The mapping file as a browser download (it is also saved in the Downloads folder). */
    @GetMapping("/{sessionId}/mapping-file")
    public ResponseEntity<FileSystemResource> download(@PathVariable String sessionId) {
        Path file;
        try {
            file = namingService.mappingFile(sessionId);
        } catch (UserFacingException e) {
            return ResponseEntity.notFound().build();
        }
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFileName() + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(new FileSystemResource(file));
    }
}

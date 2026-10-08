package com.appzone.data_importer.importer;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.appzone.data_importer.common.Task;
import com.appzone.data_importer.common.TaskRegistry;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final TaskRegistry taskRegistry;
    private final ImportService importService;

    public ImportController(TaskRegistry taskRegistry, ImportService importService) {
        this.taskRegistry = taskRegistry;
        this.importService = importService;
    }

    @PostMapping("/start")
    public Map<String, String> start(@RequestBody ImportService.Request request) {
        Task task = taskRegistry.start("import", t -> {
            try {
                importService.run(request, t);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return Map.of("taskId", task.getId());
    }
}

package com.appzone.data_importer.common;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PreDestroy;

/**
 * Runs long jobs in the background and lets the browser poll them (GET /api/tasks/{id}).
 * Polling instead of one long HTTP request gives a live log and cannot hit a browser timeout.
 */
@Component
@RestController
public class TaskRegistry {

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "task-runner");
        t.setDaemon(true);
        return t;
    });

    /** Starts the work in the background; any exception becomes a FAILED task with its message. */
    public Task start(String kind, Consumer<Task> work) {
        Task task = new Task(UUID.randomUUID().toString(), kind);
        tasks.put(task.getId(), task);
        executor.submit(() -> {
            try {
                work.accept(task);
                if (task.getStatus() == Task.Status.RUNNING) {
                    task.succeed(task.getResult());
                }
            } catch (UserFacingException e) {
                task.fail(e.getMessage());
            } catch (Throwable e) {
                e.printStackTrace();
                task.fail("Unexpected error: " + rootMessage(e));
            }
        });
        return task;
    }

    public Optional<Task> get(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public boolean hasRunningTasks() {
        return tasks.values().stream().anyMatch(t -> t.getStatus() == Task.Status.RUNNING);
    }

    @GetMapping("/api/tasks/{id}")
    public ResponseEntity<Map<String, Object>> poll(@PathVariable String id,
                                                    @RequestParam(defaultValue = "0") int from) {
        return get(id).map(t -> ResponseEntity.ok(t.snapshot(from)))
                .orElse(ResponseEntity.notFound().build());
    }

    public static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.toString();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}

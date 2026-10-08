package com.appzone.data_importer.common;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One long-running job (page 1 naming, or page 2 import). The browser polls it for new log lines,
 * progress and, at the end, the result.
 */
public class Task {

    public enum Status { RUNNING, SUCCEEDED, FAILED }

    public record LogLine(String time, String level, String message) {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String id;
    private final String kind;
    private final List<LogLine> logs = new ArrayList<>();
    private final AtomicLong progressDone = new AtomicLong();
    private final AtomicLong progressTotal = new AtomicLong();
    private volatile Status status = Status.RUNNING;
    private volatile Object result;
    private volatile String error;

    Task(String id, String kind) {
        this.id = id;
        this.kind = kind;
    }

    public String getId() {
        return id;
    }

    public String getKind() {
        return kind;
    }

    public Status getStatus() {
        return status;
    }

    public Object getResult() {
        return result;
    }

    public void info(String message) {
        log("INFO", message);
    }

    public void success(String message) {
        log("SUCCESS", message);
    }

    public void warn(String message) {
        log("WARN", message);
    }

    public void error(String message) {
        log("ERROR", message);
    }

    private void log(String level, String message) {
        System.out.println("[" + kind + "] [" + level + "] " + message);
        synchronized (logs) {
            logs.add(new LogLine(LocalTime.now().format(TIME), level, message));
        }
    }

    public void setProgressTotal(long total) {
        progressTotal.set(total);
    }

    public void addProgress(long delta) {
        progressDone.addAndGet(delta);
    }

    public void succeed(Object result) {
        this.result = result;
        this.status = Status.SUCCEEDED;
    }

    public void fail(String error) {
        this.error = error;
        error(error);
        this.status = Status.FAILED;
    }

    /** Everything the browser needs, with only the log lines it has not seen yet. */
    public Map<String, Object> snapshot(int fromLine) {
        List<LogLine> newLines;
        int next;
        synchronized (logs) {
            int from = Math.max(0, Math.min(fromLine, logs.size()));
            newLines = new ArrayList<>(logs.subList(from, logs.size()));
            next = logs.size();
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("id", id);
        body.put("status", status);
        body.put("logs", newLines);
        body.put("nextFrom", next);
        body.put("progressDone", progressDone.get());
        body.put("progressTotal", progressTotal.get());
        body.put("error", error);
        body.put("result", status == Status.SUCCEEDED ? result : null);
        return body;
    }
}

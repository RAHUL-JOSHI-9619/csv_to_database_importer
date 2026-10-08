package com.appzone.data_importer.launcher;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.appzone.data_importer.common.TaskRegistry;

/**
 * Shuts the app down (and frees the port) once the browser tab is gone.
 *
 * Two signals:
 *  - /api/ping every few seconds from a Web Worker in the page. A Worker is used because browsers
 *    throttle timers in hidden tabs (Chrome: down to once per minute after 5 minutes), and on
 *    page 1 -> page 2 the user spends minutes in Excel and in the VPN client with the tab hidden.
 *  - /api/closing, sent with navigator.sendBeacon when the tab is closed. That shortens the wait
 *    to a few seconds; a ping arriving afterwards (a page reload) cancels it.
 *
 * The app never shuts down while a task (AI naming or an import) is still running.
 */
@RestController
public class HeartbeatController {

    private static final long CLOSING_GRACE_MS = 8_000;

    private final ConfigurableApplicationContext context;
    private final TaskRegistry taskRegistry;
    private final AtomicLong lastPing = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong closingAt = new AtomicLong(0);

    @Value("${app.heartbeat.timeout-seconds:90}")
    private long timeoutSeconds;

    public HeartbeatController(ConfigurableApplicationContext context, TaskRegistry taskRegistry) {
        this.context = context;
        this.taskRegistry = taskRegistry;
    }

    @PostMapping("/api/ping")
    public void ping() {
        lastPing.set(System.currentTimeMillis());
        closingAt.set(0);
    }

    @PostMapping("/api/closing")
    public void closing() {
        closingAt.set(System.currentTimeMillis());
    }

    @Scheduled(fixedRate = 5000)
    public void checkHeartbeat() {
        if (taskRegistry.hasRunningTasks()) {
            return;
        }
        long now = System.currentTimeMillis();
        long closedAt = closingAt.get();
        boolean tabClosed = closedAt > 0 && closedAt >= lastPing.get() && now - closedAt > CLOSING_GRACE_MS;
        boolean silent = now - lastPing.get() > timeoutSeconds * 1000;

        if (tabClosed || silent) {
            System.out.println("No browser tab detected. Shutting down application...");
            context.close();
            System.exit(0);
        }
    }
}

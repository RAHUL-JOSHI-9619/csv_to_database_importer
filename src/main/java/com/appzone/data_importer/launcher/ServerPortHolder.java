package com.appzone.data_importer.launcher;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Holds the port the embedded server actually bound to. When the preferred port is busy the
 * server binds to port 0 ("any free port"), so the real port is only known once it has started.
 * WebServerInitializedEvent fires before ApplicationReadyEvent, so the browser launcher always
 * sees the real port.
 */
@Component
public class ServerPortHolder {

    private final AtomicInteger port = new AtomicInteger(-1);

    @EventListener
    public void onWebServerInitialized(WebServerInitializedEvent event) {
        port.set(event.getWebServer().getPort());
        System.out.println("Application is running on port: " + port.get());
    }

    public int getPort() {
        return port.get();
    }
}

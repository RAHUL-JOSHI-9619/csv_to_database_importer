package com.appzone.data_importer.launcher;

import java.io.IOException;
import java.net.ServerSocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs before the embedded server starts. If the preferred port (server.port, 8080 by default)
 * is already taken, the server binds to port 0 instead, which means "let the OS pick any free port".
 */
@Component
public class PortAvailabilityCustomizer implements WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> {

    @Value("${server.port:8080}")
    private int preferredPort;

    @Override
    public void customize(ConfigurableServletWebServerFactory factory) {
        if (isPortAvailable(preferredPort)) {
            factory.setPort(preferredPort);
        } else {
            System.out.println("Port " + preferredPort + " is already in use. Selecting a free port automatically...");
            factory.setPort(0);
        }
    }

    private boolean isPortAvailable(int port) {
        if (port <= 0) {
            return false;
        }
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

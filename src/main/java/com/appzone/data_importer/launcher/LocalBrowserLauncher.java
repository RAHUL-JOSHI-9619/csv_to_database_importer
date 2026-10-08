package com.appzone.data_importer.launcher;

import java.awt.Desktop;
import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Opens the default browser on the page as soon as the app is ready. */
@Component
public class LocalBrowserLauncher {

    private final ServerPortHolder serverPortHolder;

    @Value("${app.browser.open:true}")
    private boolean openBrowser;

    public LocalBrowserLauncher(ServerPortHolder serverPortHolder) {
        this.serverPortHolder = serverPortHolder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowserOnStartup() {
        if (!openBrowser) {
            return;
        }
        System.setProperty("java.awt.headless", "false");
        String url = "http://localhost:" + serverPortHolder.getPort();

        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(new URI(url));
                return;
            } catch (Exception e) {
                System.out.println("Could not open the browser with the Desktop API: " + e.getMessage());
            }
        }

        String os = System.getProperty("os.name").toLowerCase();
        ProcessBuilder processBuilder = new ProcessBuilder();
        if (os.contains("win")) {
            processBuilder.command("rundll32", "url.dll,FileProtocolHandler", url);
        } else if (os.contains("mac")) {
            processBuilder.command("open", url);
        } else {
            processBuilder.command("xdg-open", url);
        }
        try {
            processBuilder.start();
        } catch (Exception e) {
            System.out.println("Could not open the browser. Open " + url + " manually.");
        }
    }
}

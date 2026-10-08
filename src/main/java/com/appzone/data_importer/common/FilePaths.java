package com.appzone.data_importer.common;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Turns whatever the user pasted into a readable file path.
 *
 * Windows "Copy as path" gives  "C:\Users\me\Downloads\data file.csv"  - with the surrounding
 * double quotes. The page already strips the quotes and turns \ into / (same as the old
 * project's formatPath in script.js); this does it again on the server, so a path sent any
 * other way (or typed with stray spaces) still works.
 */
public final class FilePaths {

    private FilePaths() {
    }

    public static String normalize(String pasted) {
        if (pasted == null) {
            return "";
        }
        return pasted.trim()
                .replace("\"", "")
                .replace("\u202A", "")   // invisible "left-to-right" mark some Windows dialogs add
                .replace('\\', '/')
                .trim();
    }

    /** Normalizes and checks that the path points to an existing, readable .csv file. */
    public static Path requireCsvFile(String pasted, String label) {
        String normalized = normalize(pasted);
        if (normalized.isEmpty()) {
            throw new UserFacingException(label + " path is required.");
        }
        Path path;
        try {
            path = Path.of(normalized);
        } catch (InvalidPathException e) {
            throw new UserFacingException(label + " path is not valid: " + normalized);
        }
        if (!Files.exists(path)) {
            throw new UserFacingException(label + " was not found: " + normalized
                    + ". In File Explorer, right-click the file, choose 'Copy as path' and paste it here.");
        }
        if (Files.isDirectory(path)) {
            throw new UserFacingException(label + " path points to a folder, not a file: " + normalized);
        }
        if (!Files.isReadable(path)) {
            throw new UserFacingException(label + " cannot be read (no permission?): " + normalized);
        }
        if (!normalized.toLowerCase().endsWith(".csv")) {
            throw new UserFacingException(label + " must be a .csv file: " + normalized);
        }
        return path;
    }
}

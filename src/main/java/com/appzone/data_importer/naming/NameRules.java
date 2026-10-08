package com.appzone.data_importer.naming;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;

import com.appzone.data_importer.common.UserFacingException;

/**
 * The rules every final column name must satisfy, shared by page 1 (building names) and page 2
 * (re-checking names the user may have edited in Excel):
 *
 *  - only a-z, 0-9 and _ (so the name never needs quoting in a SQL query)
 *  - starts with a letter (a name like 2023 or 1st_quarter cannot be used unquoted)
 *  - not a reserved SQL word (order -> order_col)
 *  - unique in the table (email, email_1, email_2)
 *  - no longer than the database allows
 */
public final class NameRules {

    public static final String EMPTY_NAME = "unnamed_column";
    public static final String RESERVED_SUFFIX = "_col";
    public static final String DIGIT_PREFIX = "col_";

    private NameRules() {
    }

    /**
     * "Customer Name (USD)" -> customer_name_usd, "orderDate" -> order_date, "Café" -> cafe.
     * Returns "" when nothing usable is left (the caller then uses unnamed_column).
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String noAccents = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return noAccents
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase()
                .trim()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    public static boolean startsWithLetter(String name) {
        return !name.isEmpty() && name.charAt(0) >= 'a' && name.charAt(0) <= 'z';
    }

    public static boolean containsLetter(String name) {
        return name.chars().anyMatch(ch -> ch >= 'a' && ch <= 'z');
    }

    /** 2023 -> col_2023, 1st_quarter -> col_1st_quarter. */
    public static String withLetterStart(String name) {
        return startsWithLetter(name) ? name : DIGIT_PREFIX + name;
    }

    /** order -> order_col. Keeps the result within maxLen. */
    public static String withoutReservedWord(String name, int maxLen) {
        if (!ReservedSqlKeywords.isReserved(name)) {
            return name;
        }
        int budget = Math.max(1, maxLen - RESERVED_SUFFIX.length());
        return trimUnderscores(name.substring(0, Math.min(name.length(), budget))) + RESERVED_SUFFIX;
    }

    /**
     * Last resort for a name nothing else could shorten: the start of the (already shortened)
     * name + "_" + 4 hex characters computed from the full original name. The same input always
     * gives the same result, and two different long names almost never collide.
     */
    public static String hashFallback(String shortenedSoFar, String originalName, int maxLen) {
        String hex = String.format("%04x", originalName.hashCode() & 0xFFFF);
        int prefixLen = Math.max(1, maxLen - hex.length() - 1);
        String prefix = trimUnderscores(shortenedSoFar.substring(0, Math.min(shortenedSoFar.length(), prefixLen)));
        if (prefix.isEmpty() || !startsWithLetter(prefix)) {
            prefix = "c";
        }
        return prefix + "_" + hex;
    }

    public static String trimUnderscores(String s) {
        return s.replaceAll("^_+|_+$", "");
    }

    /** Cuts a name to maxLen (only used for names that became too long by adding a prefix/suffix). */
    public static String fit(String name, int maxLen) {
        return name.length() <= maxLen ? name : trimUnderscores(name.substring(0, maxLen));
    }

    /**
     * Why a final column name is not acceptable, or null if it is. Duplicates are checked by the
     * caller (they depend on the other names). The page runs the same checks in JavaScript.
     */
    public static String problem(String name, int maxLen) {
        if (name == null || name.isEmpty()) {
            return "Name is empty";
        }
        if (!name.matches("[a-z0-9_]+")) {
            return "Use only lowercase letters a-z, digits 0-9 and _";
        }
        if (!startsWithLetter(name)) {
            return "Must start with a letter";
        }
        if (name.length() > maxLen) {
            return "Too long: " + name.length() + " characters (max " + maxLen + ")";
        }
        if (ReservedSqlKeywords.isReserved(name)) {
            return "'" + name + "' is a reserved SQL word";
        }
        return null;
    }

    /** Table name the user typed -> a safe table name, e.g. "Sales Data 2024" -> sales_data_2024. */
    public static String tableName(String typed, int maxLen) {
        String name = sanitize(typed);
        if (name.isEmpty()) {
            throw new UserFacingException("Table name is required (letters, digits and _).");
        }
        name = withLetterStart(name);
        if (ReservedSqlKeywords.isReserved(name)) {
            throw new UserFacingException("'" + name + "' is a reserved SQL word. Choose another table name.");
        }
        if (name.length() > maxLen) {
            throw new UserFacingException("Table name '" + name + "' is " + name.length()
                    + " characters long; this database allows at most " + maxLen + ".");
        }
        return name;
    }

    /** Hands out unique names: email, email_1, email_2 ... always within maxLen. */
    public static final class UniqueNamer {
        private final Set<String> used = new HashSet<>();
        private final int maxLen;

        public UniqueNamer(int maxLen) {
            this.maxLen = maxLen;
        }

        public void reserve(String name) {
            used.add(name);
        }

        public boolean isUsed(String name) {
            return used.contains(name);
        }

        public String claim(String name) {
            if (used.add(name)) {
                return name;
            }
            for (int n = 1; ; n++) {
                String suffix = "_" + n;
                String base = trimUnderscores(name.substring(0, Math.min(name.length(), maxLen - suffix.length())));
                String candidate = base + suffix;
                if (used.add(candidate)) {
                    return candidate;
                }
            }
        }
    }
}

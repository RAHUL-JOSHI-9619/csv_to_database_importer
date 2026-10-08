package com.appzone.data_importer.naming;

/** Which step produced a column's final name. Written into the mapping file's "method" column. */
public enum ShortenMethod {

    NOT_NEEDED("Not needed (already fits)", false),
    AI("AI (Gemini)", false),
    NLP("NLP", true),
    VOWEL_REMOVAL("Vowel removal", true),
    HASHCODE("Hashcode", true),
    EDITED("Edited by user", false);

    private final String label;
    private final boolean needsReview;

    ShortenMethod(String label, boolean needsReview) {
        this.label = label;
        this.needsReview = needsReview;
    }

    public String getLabel() {
        return label;
    }

    /** Names that AI could not shorten are flagged, so the user can rename them before importing. */
    public boolean needsReview() {
        return needsReview;
    }
}

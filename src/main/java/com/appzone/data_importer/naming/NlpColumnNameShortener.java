package com.appzone.data_importer.naming;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;

/**
 * Non-AI shortening, used only for the "stubborn" names that AI could not shorten after all its
 * rounds (or when AI is not available). Steps, stopping as soon as the name fits:
 *
 *  1. NLP: POS-tag the words, keep nouns, proper nouns and numbers, drop filler words.
 *     FIX for the old project's bug: a name like "total_2023_for_the_last" used to become just
 *     "2023" (no noun survived) - a name without any letter. Now, if the kept words contain no
 *     letter at all, every non-filler word is kept instead. Numbers are kept next to the words
 *     they belong to, so "sales_2023" stays "sales_2023".
 *  2. NLP: replace known business words with standard abbreviations (customer -> cust).
 *  3. Vowel removal (keeps the first letter of each word).
 *
 * The result may still be too long; the caller then uses the hashcode fallback.
 */
@Service
public class NlpColumnNameShortener {

    public record Attempt(String name, ShortenMethod method) {
    }

    private static final String POS_MODEL = "/opennlp-models/opennlp-en-ud-ewt-pos-1.3-2.5.4.bin";

    private static final Set<String> KEEP_TAGS = Set.of("NOUN", "PROPN", "NUM");

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "of", "in", "on", "at", "to", "for", "and", "or",
            "is", "are", "was", "were", "be", "been", "being", "with", "by",
            "from", "as", "that", "this", "these", "those", "it", "its", "into",
            "per", "than", "then", "so", "such", "up", "out", "off", "over", "who",
            "which", "what", "whose", "their", "his", "her", "our", "your", "all", "any");

    private static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            Map.entry("customer", "cust"), Map.entry("customers", "cust"),
            Map.entry("account", "acct"), Map.entry("accounts", "acct"),
            Map.entry("number", "num"), Map.entry("amount", "amt"),
            Map.entry("quantity", "qty"), Map.entry("monthly", "mthly"),
            Map.entry("premium", "prem"), Map.entry("created", "crtd"),
            Map.entry("previous", "prev"), Map.entry("fiscal", "fy"),
            Map.entry("reporting", "rpt"), Map.entry("report", "rpt"),
            Map.entry("period", "prd"), Map.entry("region", "rgn"),
            Map.entry("regional", "rgnl"), Map.entry("business", "biz"),
            Map.entry("department", "dept"), Map.entry("departments", "dept"),
            Map.entry("documentation", "doc"), Map.entry("document", "doc"),
            Map.entry("repository", "repo"), Map.entry("system", "sys"),
            Map.entry("transaction", "txn"), Map.entry("transactions", "txn"),
            Map.entry("history", "hist"), Map.entry("compliance", "cmplnc"),
            Map.entry("regulatory", "reg"), Map.entry("average", "avg"),
            Map.entry("summary", "summ"), Map.entry("analysis", "anlys"),
            Map.entry("detail", "dtl"), Map.entry("details", "dtl"),
            Map.entry("active", "actv"), Map.entry("total", "tot"),
            Map.entry("percentage", "pct"), Map.entry("percent", "pct"),
            Map.entry("identifier", "id"), Map.entry("description", "desc"),
            Map.entry("address", "addr"), Map.entry("information", "info"),
            Map.entry("management", "mgmt"), Map.entry("manager", "mgr"),
            Map.entry("employee", "emp"), Map.entry("employees", "emp"),
            Map.entry("organization", "org"), Map.entry("organisation", "org"),
            Map.entry("category", "cat"), Map.entry("reference", "ref"),
            Map.entry("maximum", "max"), Map.entry("minimum", "min"),
            Map.entry("balance", "bal"), Map.entry("payment", "pmt"),
            Map.entry("payments", "pmt"), Map.entry("invoice", "inv"),
            Map.entry("product", "prod"), Map.entry("products", "prod"),
            Map.entry("service", "svc"), Map.entry("services", "svc"),
            Map.entry("status", "stat"), Map.entry("value", "val"),
            Map.entry("year", "yr"), Map.entry("years", "yr"),
            Map.entry("month", "mon"), Map.entry("months", "mon"),
            Map.entry("quarter", "qtr"), Map.entry("date", "dt"),
            Map.entry("count", "cnt"), Map.entry("revenue", "rev"),
            Map.entry("interest", "intrst"), Map.entry("insurance", "ins"),
            Map.entry("contract", "cntrct"), Map.entry("location", "loc"),
            Map.entry("telephone", "tel"), Map.entry("phone", "ph"));

    private final POSTaggerME posTagger;

    public NlpColumnNameShortener() throws IOException {
        try (InputStream in = new ClassPathResource(POS_MODEL).getInputStream()) {
            this.posTagger = new POSTaggerME(new POSModel(in));
        }
    }

    /** @param sanitizedName a name that already passed NameRules.sanitize (words joined by _) */
    public Attempt shorten(String sanitizedName, int maxLen) {
        String[] words = sanitizedName.split("_+");

        List<String> kept = keepMeaningfulWords(words);
        String candidate = NameRules.withLetterStart(String.join("_", kept));
        if (candidate.length() <= maxLen) {
            return new Attempt(candidate, ShortenMethod.NLP);
        }

        List<String> abbreviated = kept.stream().map(w -> ABBREVIATIONS.getOrDefault(w, w)).toList();
        candidate = NameRules.withLetterStart(String.join("_", abbreviated));
        if (candidate.length() <= maxLen) {
            return new Attempt(candidate, ShortenMethod.NLP);
        }

        return new Attempt(NameRules.withLetterStart(removeVowels(abbreviated)), ShortenMethod.VOWEL_REMOVAL);
    }

    private List<String> keepMeaningfulWords(String[] words) {
        String[] tags;
        try {
            // POSTaggerME is not thread-safe; tagging a few words is fast, so just serialize it.
            synchronized (posTagger) {
                tags = posTagger.tag(words);
            }
        } catch (Exception e) {
            tags = new String[words.length];
        }

        // Words with digits are always kept: in sensor_1 ... sensor_398 the number is the only thing
        // that tells the columns apart, and the tagger labels a lone "398" inconsistently.
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            boolean hasDigit = words[i].chars().anyMatch(Character::isDigit);
            boolean meaningful = tags[i] != null && KEEP_TAGS.contains(tags[i]);
            if ((hasDigit || meaningful) && !STOP_WORDS.contains(words[i])) {
                kept.add(words[i]);
            }
        }
        if (kept.stream().anyMatch(NameRules::containsLetter)) {
            return kept;
        }

        // Only numbers (or nothing) survived: keep every word that is not a filler word.
        List<String> nonFiller = new ArrayList<>();
        for (String w : words) {
            if (!STOP_WORDS.contains(w)) {
                nonFiller.add(w);
            }
        }
        if (nonFiller.stream().anyMatch(NameRules::containsLetter)) {
            return nonFiller;
        }
        return List.of(words);
    }

    private String removeVowels(List<String> words) {
        List<String> out = new ArrayList<>();
        for (String w : words) {
            if (w.length() <= 2) {
                out.add(w);
            } else {
                out.add(w.charAt(0) + w.substring(1).replaceAll("[aeiou]", ""));
            }
        }
        return String.join("_", out);
    }
}

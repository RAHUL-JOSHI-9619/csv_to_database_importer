package com.appzone.data_importer.naming;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

import com.appzone.data_importer.common.CsvRecordReader;

class NamingRulesTest {

    private final NlpColumnNameShortener nlp;

    NamingRulesTest() throws Exception {
        nlp = new NlpColumnNameShortener();
    }

    @Test
    void nlp_never_returns_only_a_number() {
        // The old project turned this into just "2023" (no noun survived), which cannot be a column name.
        String name = "total_2023_for_the_previous_and_the_last_and_the_first_of_all_them";
        NlpColumnNameShortener.Attempt attempt = nlp.shorten(name, 30);
        assertTrue(NameRules.startsWithLetter(attempt.name()), attempt.name());
        assertTrue(attempt.name().contains("2023"), attempt.name());
    }

    @Test
    void nlp_keeps_the_number_that_distinguishes_similar_columns() {
        for (int i : new int[] { 0, 1, 398 }) {
            String name = "measurement_value_for_sensor_number_" + i + "_recorded_during_the_calibration_window";
            assertTrue(nlp.shorten(name, 30).name().contains(String.valueOf(i)), "column " + i);
        }
    }

    @Test
    void name_rules() {
        assertEquals("customer_name_usd", NameRules.sanitize("Customer Name (USD)"));
        assertEquals("order_date", NameRules.sanitize("orderDate"));
        assertEquals("cafe", NameRules.sanitize("Café"));
        assertEquals("col_2023", NameRules.withLetterStart("2023"));
        assertEquals("order_col", NameRules.withoutReservedWord("order", 30));
        assertEquals(30, NameRules.hashFallback("a".repeat(40), "x".repeat(50), 30).length());

        NameRules.UniqueNamer namer = new NameRules.UniqueNamer(30);
        assertEquals("email", namer.claim("email"));
        assertEquals("email_1", namer.claim("email"));
        assertEquals("email_2", namer.claim("email"));
    }

    @Test
    void csv_reader_handles_quotes_the_way_import_wizards_do_not() throws Exception {
        String csv = "a,b,c\r\n"
                + "1,\"x, y\",\"He said \"\"hi\"\"\"\n"
                + "2,He said \"hi\" to me,z\n"
                + "\n"
                + "3,\"line1\nline2\",\"stray \"quote\" inside\"\n";
        try (CsvRecordReader r = new CsvRecordReader(new StringReader(csv))) {
            assertArrayEquals(new String[] { "a", "b", "c" }, r.next());
            assertArrayEquals(new String[] { "1", "x, y", "He said \"hi\"" }, r.next());
            assertArrayEquals(new String[] { "2", "He said \"hi\" to me", "z" }, r.next());
            assertArrayEquals(new String[] { "3", "line1\nline2", "stray \"quote\" inside" }, r.next());
            assertEquals(5, r.getRecordStartLine());
            assertNull(r.next());
        }
    }
}

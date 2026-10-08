package com.appzone.data_importer.naming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.appzone.data_importer.common.Task;
import com.appzone.data_importer.common.TaskRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Exercises the round logic with a fake Gemini, so no network or API key is needed. */
class AiColumnNameShortenerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A shortener whose "Gemini" is a function from (input items) to answers. */
    private static class FakeShortener extends AiColumnNameShortener {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final Function<JsonNode, List<String>> answer;

        FakeShortener(Function<JsonNode, List<String>> answer) {
            super(null, new AiConfig.AiKeyStatus(true));
            this.answer = answer;
            ReflectionTestUtils.setField(this, "enabled", true);
            ReflectionTestUtils.setField(this, "batchSize", 40);
            ReflectionTestUtils.setField(this, "parallelCalls", 4);
            ReflectionTestUtils.setField(this, "timeoutSeconds", 5);
            ReflectionTestUtils.setField(this, "maxRounds", 4);
        }

        @Override
        protected String send(String prompt) {
            prompts.add(prompt);
            try {
                String json = prompt.substring(prompt.indexOf("items):") + "items):".length()).trim();
                return JSON.writeValueAsString(answer.apply(JSON.readTree(json)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static Task task() {
        return new TaskRegistry().start("test", t -> { });
    }

    private static List<String> longNames(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add("the_total_amount_of_premium_collected_from_customer_number_" + i + "_during_last_year");
        }
        return names;
    }

    @Test
    void sends_batches_in_parallel_and_accepts_good_answers_in_one_round() {
        FakeShortener ai = new FakeShortener(items -> {
            List<String> out = new ArrayList<>();
            items.forEach(item -> out.add("prem_cust_" + item.get("name").asText().replaceAll("\\D+", "_")
                    .replaceAll("^_|_$", "") + "_ly"));
            return out;
        });

        AiColumnNameShortener.Outcome outcome = ai.shorten(longNames(120), 30, Set.of(), task());

        assertEquals(120, outcome.shortened().size());
        assertEquals(3, ai.prompts.size(), "120 names / 40 per prompt = 3 prompts, one round");
        assertEquals(120, new HashSet<>(outcome.shortened().values()).size(), "all names unique");
    }

    @Test
    void retries_too_long_and_duplicate_answers_with_feedback() {
        FakeShortener ai = new FakeShortener(items -> {
            List<String> out = new ArrayList<>();
            items.forEach(item -> {
                String number = item.get("name").asText().replaceAll("\\D+", "");
                if (!item.has("problem")) {
                    // Round 1: #0 too long, #1 and #2 collide, #3 collides with an existing column.
                    switch (number) {
                        case "0" -> out.add("total_amount_premium_collected_customer_0_last_year");
                        case "1", "2" -> out.add("prem_cust_ly");
                        case "3" -> out.add("existing_col");
                        default -> out.add("prem_cust_" + number + "_ly");
                    }
                } else {
                    out.add("prem_cust_" + number + "_ly_fixed");
                }
            });
            return out;
        });

        Map<String, String> result = ai.shorten(longNames(5), 30, Set.of("existing_col"), task()).shortened();

        assertEquals(5, result.size());
        assertEquals(5, new HashSet<>(result.values()).size());
        assertTrue(result.values().stream().allMatch(n -> n.length() <= 30));
        assertTrue(result.values().stream().noneMatch("existing_col"::equals));
        assertTrue(ai.prompts.get(ai.prompts.size() - 1).contains("the limit is 30"),
                "round 2 tells the model what was wrong");
    }

    @Test
    void gives_up_after_max_rounds_and_leaves_stubborn_names_to_the_caller() {
        FakeShortener ai = new FakeShortener(items -> {
            List<String> out = new ArrayList<>();
            items.forEach(item -> out.add(item.get("name").asText()));   // never shortens
            return out;
        });

        AiColumnNameShortener.Outcome outcome = ai.shorten(longNames(3), 30, Set.of(), task());

        assertTrue(outcome.shortened().isEmpty());
        assertEquals(4, ai.prompts.size(), "one prompt per round, 4 rounds");
    }

    @Test
    void stops_after_round_one_when_gemini_is_unreachable() {
        FakeShortener ai = new FakeShortener(items -> {
            throw new IllegalStateException("400 API key not valid");
        });

        AiColumnNameShortener.Outcome outcome = ai.shorten(longNames(100), 30, Set.of(), task());

        assertTrue(outcome.shortened().isEmpty());
        assertTrue(outcome.unavailableReason().contains("not valid"));
        assertEquals(3, ai.prompts.size(), "only round 1 was sent");
    }
}

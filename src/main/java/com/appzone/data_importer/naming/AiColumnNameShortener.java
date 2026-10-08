package com.appzone.data_importer.naming;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.appzone.data_importer.common.Task;
import com.appzone.data_importer.common.TaskRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * Shortens long column names with Gemini, in ROUNDS:
 *
 *   Round 1: all long names are cut into batches (app.ai.batch-size, e.g. 40 names per prompt) and
 *            the batches are sent in parallel (app.ai.parallel-calls threads). 120 long names ->
 *            3 prompts, all in flight at once.
 *   Round 2+: only the names whose answer was unusable (still too long, empty, duplicate of
 *            another column, missing) are sent again - with feedback ("your answer X was 71
 *            characters, the limit is 64") and in smaller batches, which models handle better.
 *   ...up to app.ai.max-rounds. Whatever is still unresolved is returned as "stubborn" and the
 *   caller falls back to NLP / vowel removal / hashcode, flagging those names for review.
 *
 * Every single call has a hard timeout (Future.get), so a network that swallows requests can
 * never hang the job. If every call of the first round fails (no key, no internet, quota), the
 * remaining rounds are skipped - they would fail the same way.
 */
@Service
public class AiColumnNameShortener {

    /**
     * One name to shorten. previousAnswer/problem: what the model answered last time and why it was
     * rejected (sent back as feedback). lastError: the request itself failed (timeout, 429...).
     */
    private record Item(String name, String previousAnswer, String problem, String lastError) {
    }

    private record CallResult(List<Item> items, List<String> answers, String error) {
    }

    /**
     * @param shortened         long name -> accepted short name
     * @param failures          long name -> why AI could not shorten it (for the mapping file's notes)
     * @param unavailableReason set when AI could not be used at all
     */
    public record Outcome(Map<String, String> shortened, Map<String, String> failures, String unavailableReason) {
    }

    private static final String RATE_LIMIT = "Gemini rate limit / quota reached (429)";
    private static final long RATE_LIMIT_WAIT_MS = 30_000;
    private static final int MAX_LOGGED_REJECTIONS = 10;

    private final ChatClient chatClient;
    private final AiConfig.AiKeyStatus keyStatus;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService callExecutor = Executors.newCachedThreadPool(daemon("ai-call"));

    @Value("${app.ai.enabled:true}")
    private boolean enabled;

    @Value("${app.ai.batch-size:40}")
    private int batchSize;

    @Value("${app.ai.parallel-calls:2}")
    private int parallelCalls;

    @Value("${app.ai.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${app.ai.max-rounds:4}")
    private int maxRounds;

    public AiColumnNameShortener(ChatClient chatClient, AiConfig.AiKeyStatus keyStatus) {
        this.chatClient = chatClient;
        this.keyStatus = keyStatus;
    }

    /** @return why AI cannot be used at all, or null if it can be tried. */
    public String unavailableReason() {
        if (!enabled) {
            return "AI is switched off (app.ai.enabled=false).";
        }
        if (!keyStatus.isKeyConfigured()) {
            return "No Gemini API key found. Set the GEMINI_API_KEY environment variable and restart the app.";
        }
        return null;
    }

    /**
     * @param longNames  distinct names longer than maxLen
     * @param takenNames final names of the other columns - an AI answer equal to one of these is
     *                   sent back for another try, so two columns do not end up as x and x_1
     */
    public Outcome shorten(List<String> longNames, int maxLen, java.util.Set<String> takenNames, Task task) {
        Map<String, String> accepted = new LinkedHashMap<>();
        String unavailable = unavailableReason();
        if (unavailable != null) {
            task.warn(unavailable);
            return new Outcome(accepted, failAll(longNames, unavailable), unavailable);
        }
        if (longNames.isEmpty()) {
            return new Outcome(accepted, Map.of(), null);
        }

        java.util.Set<String> used = new java.util.HashSet<>(takenNames);
        List<Item> pending = longNames.stream().map(n -> new Item(n, null, null, null)).toList();
        ExecutorService roundPool = Executors.newFixedThreadPool(Math.max(1, parallelCalls), daemon("ai-batch"));

        try {
            for (int round = 1; round <= maxRounds && !pending.isEmpty(); round++) {
                int size = Math.max(5, batchSize >> (round - 1));   // 40, 20, 10, 5 ...
                List<List<Item>> batches = partition(pending, size);
                task.info("AI round " + round + " of " + maxRounds + ": sending " + pending.size() + " name(s) in "
                        + batches.size() + " prompt(s) of up to " + size + " names, " + Math.min(parallelCalls,
                        batches.size()) + " at a time...");

                List<CompletableFuture<CallResult>> futures = new ArrayList<>();
                for (List<Item> batch : batches) {
                    futures.add(CompletableFuture.supplyAsync(() -> callWithTimeout(batch, maxLen), roundPool));
                }
                List<CallResult> results = futures.stream().map(CompletableFuture::join).toList();

                boolean lastRound = round == maxRounds;
                List<Item> retry = new ArrayList<>();
                List<Item> rejected = new ArrayList<>();
                int acceptedThisRound = 0;
                int failedCalls = 0;
                boolean rateLimited = false;
                String firstError = null;

                // Checked one after another (not inside the parallel calls) so the duplicate check is exact.
                for (CallResult result : results) {
                    if (result.error() != null) {
                        failedCalls++;
                        rateLimited |= RATE_LIMIT.equals(result.error());
                        if (firstError == null) {
                            firstError = result.error();
                        }
                        result.items().forEach(i -> retry.add(
                                new Item(i.name(), i.previousAnswer(), i.problem(), result.error())));
                        continue;
                    }
                    for (int i = 0; i < result.items().size(); i++) {
                        Item item = result.items().get(i);
                        String answer = result.answers().get(i);
                        String problem = check(answer, maxLen, used, lastRound);
                        if (problem == null) {
                            String name = NameRules.sanitize(answer);
                            accepted.put(item.name(), name);
                            used.add(name);
                            acceptedThisRound++;
                        } else {
                            Item again = new Item(item.name(), answer, problem, null);
                            retry.add(again);
                            rejected.add(again);
                        }
                    }
                }

                task.info("AI round " + round + " finished: " + acceptedThisRound + " name(s) accepted, "
                        + retry.size() + " still need work"
                        + (failedCalls > 0 ? " (" + failedCalls + " of " + batches.size() + " prompt(s) failed: "
                        + firstError + ")" : "") + ".");
                logRejections(rejected, task);

                // Nothing worked at all in round 1 (bad key, no internet): the next rounds would fail
                // the same way. A rate limit is different - waiting helps - so keep going then.
                if (acceptedThisRound == 0 && failedCalls == batches.size() && round == 1 && !rateLimited) {
                    String reason = "Gemini could not be reached: " + firstError;
                    task.warn(reason + ". Skipping the remaining AI rounds.");
                    return new Outcome(accepted, failAll(longNames, reason), reason);
                }
                pending = retry;
                if (failedCalls > 0 && !pending.isEmpty() && round < maxRounds) {
                    if (rateLimited) {
                        task.warn("Gemini's rate limit was reached. Waiting " + RATE_LIMIT_WAIT_MS / 1000
                                + " seconds before the next round...");
                        sleepQuietly(RATE_LIMIT_WAIT_MS);
                    } else {
                        sleepQuietly(2000L * round);
                    }
                }
            }
        } finally {
            roundPool.shutdownNow();
        }

        Map<String, String> failures = new LinkedHashMap<>();
        for (Item item : pending) {
            failures.put(item.name(), describeFailure(item));
        }
        return new Outcome(accepted, failures, null);
    }

    /** Why a name could not be shortened by AI, in words for the mapping file's notes. */
    private static String describeFailure(Item item) {
        if (item.lastError() != null) {
            return "AI request failed: " + item.lastError();
        }
        return "AI's last answer was rejected: " + item.problem();
    }

    private void logRejections(List<Item> rejected, Task task) {
        rejected.stream().limit(MAX_LOGGED_REJECTIONS).forEach(item -> task.info("    rejected: '"
                + abbreviate(item.name()) + "' -> " + item.problem()));
        if (rejected.size() > MAX_LOGGED_REJECTIONS) {
            task.info("    ... and " + (rejected.size() - MAX_LOGGED_REJECTIONS) + " more rejected answer(s).");
        }
    }

    private static String abbreviate(String name) {
        return name.length() <= 60 ? name : name.substring(0, 57) + "...";
    }

    private static Map<String, String> failAll(List<String> names, String reason) {
        Map<String, String> failures = new LinkedHashMap<>();
        names.forEach(n -> failures.put(n, reason));
        return failures;
    }

    /** @return null if the answer is usable, else a short description of the problem for the next prompt. */
    private String check(String answer, int maxLen, java.util.Set<String> used, boolean lastRound) {
        if (answer == null || answer.isBlank()) {
            return "you returned an empty answer";
        }
        String name = NameRules.sanitize(answer);
        if (name.isEmpty()) {
            return "your answer had no letters or digits";
        }
        if (!NameRules.startsWithLetter(name)) {
            return "'" + name + "' starts with a digit; it must start with a letter";
        }
        if (name.length() > maxLen) {
            return "'" + name + "' is " + name.length() + " characters; the limit is " + maxLen;
        }
        if (ReservedSqlKeywords.isReserved(name)) {
            return "'" + name + "' is a reserved SQL word";
        }
        if (used.contains(name) && !lastRound) {
            return "'" + name + "' is already used by another column; make it distinct";
        }
        return null;
    }

    private CallResult callWithTimeout(List<Item> batch, int maxLen) {
        Future<List<String>> future = callExecutor.submit(() -> callGemini(batch, maxLen));
        try {
            return new CallResult(batch, future.get(timeoutSeconds, TimeUnit.SECONDS), null);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new CallResult(batch, null, "no answer within " + timeoutSeconds + " seconds");
        } catch (Exception e) {
            return new CallResult(batch, null, shortError(TaskRegistry.rootMessage(e)));
        }
    }

    /** Gemini errors carry a multi-line JSON body; the first line (or a plain hint) is enough. */
    private static String shortError(String message) {
        if (message.contains("API_KEY_INVALID") || message.contains("API key not valid")) {
            return "the Gemini API key is not valid - check the GEMINI_API_KEY environment variable";
        }
        if (message.contains("RESOURCE_EXHAUSTED") || message.startsWith("429")) {
            return RATE_LIMIT;
        }
        String firstLine = message.lines().findFirst().orElse(message).trim();
        return firstLine.length() > 200 ? firstLine.substring(0, 200) + "..." : firstLine;
    }

    /**
     * One prompt for a whole batch. The answer is a POSITIONAL JSON array (answer[i] belongs to
     * input[i]) - models do not always echo long names back exactly, so matching by name loses
     * answers. A wrong-length array is treated as a failed call and the batch is retried.
     */
    private List<String> callGemini(List<Item> batch, int maxLen) throws Exception {
        List<Map<String, String>> input = new ArrayList<>();
        for (Item item : batch) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", item.name());
            if (item.previousAnswer() != null || item.problem() != null) {
                entry.put("previous_answer", item.previousAnswer() == null ? "" : item.previousAnswer());
                entry.put("problem", item.problem());
            }
            input.add(entry);
        }
        int n = batch.size();

        String prompt = """
                You shorten database column names so they fit in a table.

                Rules for every output name:
                - At most %d characters. Count carefully; this is a hard limit.
                - Only lowercase letters a-z, digits 0-9 and underscores. Start with a letter.
                - Words separated by underscores. Keep it readable for a human writing SQL.
                - Keep the words that carry the meaning; drop filler words (the, of, for, all, which...).
                - Keep numbers, years, units and qualifiers that distinguish similar columns
                  (e.g. 2023, q1, usd, pct, min, max) - two different inputs must never get the same output.
                - Use common abbreviations (amt, qty, num, cust, acct, avg, pct, dt, yr, prev, tot).
                - Do not invent meaning that is not in the input.
                - Items that have "previous_answer" and "problem" were answered before and rejected;
                  fix the problem described.

                Respond with ONLY a JSON array of exactly %d strings, in the same order as the input
                (output[i] is the short name for input[i]). No markdown, no explanation.

                Input (%d items):
                %s
                """.formatted(maxLen, n, n, objectMapper.writeValueAsString(input));

        String response = send(prompt);
        if (response == null) {
            throw new IllegalStateException("empty response");
        }
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("the answer was not a JSON array");
        }
        List<String> parsed = objectMapper.readValue(response.substring(start, end + 1),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        if (parsed.size() != n) {
            throw new IllegalStateException("the answer had " + parsed.size() + " names instead of " + n);
        }
        return parsed;
    }

    /** The actual network call (overridden in tests). */
    protected String send(String prompt) {
        return chatClient.prompt().user(prompt).call().content();
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(new ArrayList<>(list.subList(i, Math.min(i + size, list.size()))));
        }
        return result;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemon(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    @PreDestroy
    public void shutdown() {
        callExecutor.shutdownNow();
    }
}

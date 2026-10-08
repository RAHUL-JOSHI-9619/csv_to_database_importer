package com.appzone.data_importer.naming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import com.appzone.data_importer.common.Task;
import com.appzone.data_importer.common.TaskRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Page 1 flow: first run (AI down) -> AI rename of chosen rows (AI up) -> save with checks. */
class ColumnNamingServiceTest {

    @TempDir
    Path home;

    /** AI that is "down" until switched on, then answers "short_<n>" for every name. */
    private static class SwitchableAi extends AiColumnNameShortener {
        volatile boolean up;

        SwitchableAi() {
            super(null, new AiConfig.AiKeyStatus(true));
            ReflectionTestUtils.setField(this, "enabled", true);
            ReflectionTestUtils.setField(this, "batchSize", 40);
            ReflectionTestUtils.setField(this, "parallelCalls", 2);
            ReflectionTestUtils.setField(this, "timeoutSeconds", 5);
            ReflectionTestUtils.setField(this, "maxRounds", 4);
        }

        @Override
        protected String send(String prompt) {
            if (!up) {
                throw new IllegalStateException("network unreachable");
            }
            try {
                String json = prompt.substring(prompt.indexOf("items):") + "items):".length()).trim();
                var items = new ObjectMapper().readTree(json);
                StringBuilder out = new StringBuilder("[");
                for (int i = 0; i < items.size(); i++) {
                    String digits = items.get(i).get("name").asText().replaceAll("\\D+", "");
                    out.append(i == 0 ? "" : ",").append("\"short_").append(digits).append('"');
                }
                return out.append(']').toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static Task task() {
        return new TaskRegistry().start("test", t -> { });
    }

    @Test
    void edit_rename_with_ai_and_save() throws Exception {
        System.setProperty("user.home", home.toString());
        Path csv = home.resolve("data.csv");
        Files.writeString(csv, "id,this_is_a_very_long_column_name_number_1_for_testing,"
                + "this_is_a_very_long_column_name_number_2_for_testing,\n1,a,b,c\n");

        SwitchableAi ai = new SwitchableAi();
        ColumnNamingService service = new ColumnNamingService(ai, new NlpColumnNameShortener());
        ReflectionTestUtils.setField(service, "mappingFolderName", "Data_Importer");

        // 1. First run: AI is down -> long names go to NLP etc., flagged, with the reason in notes.
        Task first = task();
        service.run(new ColumnNamingService.Request("oracle", "oracle-12.1", csv.toString()), first);
        ColumnNamingService.Result result = (ColumnNamingService.Result) first.getResult();
        assertEquals(3, result.flaggedColumns(), "2 stubborn names + 1 empty header");
        assertTrue(result.rows().get(1).notes().contains("network unreachable"), result.rows().get(1).notes());

        // 2. AI comes back: rename columns 2 and 3 (and 4, which has an empty header).
        ai.up = true;
        Task rename = task();
        service.aiRename(result.sessionId(), List.of(2, 3, 4), Map.of(), rename);
        @SuppressWarnings("unchecked")
        List<ColumnNamingService.Suggestion> suggestions =
                (List<ColumnNamingService.Suggestion>) ((Map<String, Object>) rename.getResult()).get("suggestions");
        Map<Integer, ColumnNamingService.Suggestion> byNo = new java.util.HashMap<>();
        suggestions.forEach(s -> byNo.put(s.columnNo(), s));
        assertEquals("short_1", byNo.get(2).name());
        assertEquals("short_2", byNo.get(3).name());
        assertNull(byNo.get(4).name(), "empty header cannot go to AI");

        // 3. Saving a duplicate is refused, nothing changes.
        var bad = assertThrows(ColumnNamingService.InvalidNamesException.class, () -> service.save(result.sessionId(),
                List.of(new ColumnNamingService.Change(2, "id", "user"))));
        assertTrue(bad.getErrors().get(2).startsWith("Duplicate"));

        // 4. A valid save updates methods, clears the flags and rewrites the mapping file.
        ColumnNamingService.Result saved = service.save(result.sessionId(), List.of(
                new ColumnNamingService.Change(2, "short_1", "ai"),
                new ColumnNamingService.Change(3, "short_2", "ai"),
                new ColumnNamingService.Change(4, "comments", "user")));
        assertEquals(0, saved.flaggedColumns());
        assertEquals("AI (Gemini)", saved.rows().get(1).method());
        assertEquals("Edited by user", saved.rows().get(3).method());
        List<MappingFile.Row> onDisk = MappingFile.read(Path.of(saved.mappingFilePath()));
        assertEquals("comments", onDisk.get(3).cleanedName());
    }
}

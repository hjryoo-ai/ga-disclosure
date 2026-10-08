package com.ga.disclosure.workflow;

import com.ga.disclosure.workflow.disclosure.CommandResult;
import com.ga.disclosure.workflow.disclosure.LifecycleService;
import com.ga.disclosure.workflow.disclosure.SealService;
import com.ga.disclosure.workflow.feed.EventFeed;
import com.ga.disclosure.workflow.retention.LegalHoldRejectedException;
import com.ga.disclosure.workflow.sign.SignRejection;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설계서 §7의 {@code rejection-categories} 블록(정본)과 거부 코드의 범주(6A 계획 §4.2 — CONFLICT 409·INVALID 422)를 양방향으로 대조한다. 코드가 늘거나
 * 범주가 바뀌면 블록도 같은 커밋에서 바뀌어야 한다.
 */
class RejectionCategoryTableTest {

    private static final Path DESIGN = Path.of(System.getProperty("ga.repoRoot"), "docs", "설계서.md");
    private static final Pattern BLOCK = Pattern.compile("```rejection-categories\\n(.*?)\\n```", Pattern.DOTALL);

    static Map<String, String> documented() throws Exception {
        Matcher m = BLOCK.matcher(Files.readString(DESIGN));
        assertThat(m.find()).as("설계서에 rejection-categories 블록이 있다").isTrue();
        Map<String, String> out = new TreeMap<>();
        List<String> lines = m.group(1).lines().toList();
        assertThat(lines.getFirst()).isEqualTo("source,code,category");
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",");
            assertThat(cells).as(line).hasSize(3);
            assertThat(out.put(cells[0] + "." + cells[1], cells[2])).as("duplicate " + line).isNull();
        }
        return out;
    }

    static Map<String, String> coded() {
        Map<String, String> out = new TreeMap<>();
        for (CommandResult.Rejection r : CommandResult.Rejection.values()) {
            out.put("CommandResult." + r.name(), r.category().name());
        }
        for (LifecycleService.Rejection r : LifecycleService.Rejection.values()) {
            out.put("LifecycleService." + r.name(), r.category().name());
        }
        for (SealService.Rejection r : SealService.Rejection.values()) {
            out.put("SealService." + r.name(), r.category().name());
        }
        for (SignRejection r : SignRejection.values()) {
            out.put("SignRejection." + r.name(), r.category().name());
        }
        for (EventFeed.Rejection r : EventFeed.Rejection.values()) {
            out.put("EventFeed." + r.name(), r.category().name());
        }
        for (String code : List.of("UNKNOWN_REASON", "TEXT_REQUIRED", "TEXT_TOO_LONG", "ALREADY_HELD", "NOT_FOUND", "ALREADY_RELEASED",
                "BAD_RELEASE_REASON", "FOUR_EYES_REQUIRED")) {
            out.put("LegalHold." + code, new LegalHoldRejectedException(code).category().name());
        }
        return out;
    }

    @Test
    void theDocumentedTableAndTheCodeAgree() throws Exception {
        Map<String, String> doc = documented();
        Map<String, String> code = coded();
        doc.keySet().stream().filter(k -> !k.startsWith("CommandRejected.")).forEach(k -> assertThat(code).as("documented " + k).containsKey(k));
        assertThat(doc).containsAllEntriesOf(code);
    }

    @Test
    void mixedRejectionsAreAConflict() {
        assertThat(RejectionCategory.of(List.of(SignRejection.IDENTITY_INCOMPLETE, SignRejection.ALREADY_SIGNED))).isEqualTo(RejectionCategory.CONFLICT);
        assertThat(RejectionCategory.of(List.of(SignRejection.IDENTITY_INCOMPLETE))).isEqualTo(RejectionCategory.INVALID);
        assertThat(RejectionCategory.of(List.of())).isEqualTo(RejectionCategory.INVALID);
    }
}

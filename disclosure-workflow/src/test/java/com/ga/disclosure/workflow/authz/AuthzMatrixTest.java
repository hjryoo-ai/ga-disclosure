package com.ga.disclosure.workflow.authz;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 설계서 §9의 {@code authz-matrix} 블록(정본)과 {@link ScopePolicy}를 양방향으로 대조한다 — 한쪽만 바뀌면 실패. */
class AuthzMatrixTest {

    private static final Path DESIGN = Path.of(System.getProperty("ga.repoRoot"), "docs", "설계서.md");
    private static final Pattern BLOCK = Pattern.compile("```authz-matrix\\n(.*?)\\n```", Pattern.DOTALL);

    static Map<Action, Map<Role, ScopePolicy.Scope>> parse(String markdown) {
        Matcher m = BLOCK.matcher(markdown);
        if (!m.find()) {
            throw new IllegalStateException("설계서에 authz-matrix 블록이 없다");
        }
        if (m.find()) {
            throw new IllegalStateException("authz-matrix 블록이 둘 이상이다");
        }
        m.reset().find();
        List<String> lines = List.of(m.group(1).split("\n"));
        String header = "action," + String.join(",", Arrays.stream(Role.values()).map(Enum::name).toList());
        if (!lines.getFirst().equals(header)) {
            throw new IllegalStateException("header must be " + header);
        }
        Map<Action, Map<Role, ScopePolicy.Scope>> out = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            if (cells.length != Role.values().length + 1) {
                throw new IllegalStateException("bad row: " + line);
            }
            Map<Role, ScopePolicy.Scope> row = new EnumMap<>(Role.class);
            for (int i = 1; i < cells.length; i++) {
                if (!cells[i].equals("-")) {
                    row.put(Role.values()[i - 1], ScopePolicy.Scope.valueOf(cells[i]));
                }
            }
            if (out.put(Action.valueOf(cells[0]), row) != null) {
                throw new IllegalStateException("duplicate row: " + cells[0]);
            }
        }
        return out;
    }

    @Test
    void designBlockEqualsTheCodeBothWays() throws IOException {
        Map<Action, Map<Role, ScopePolicy.Scope>> doc = parse(Files.readString(DESIGN, StandardCharsets.UTF_8));
        assertThat(doc.keySet()).as("rows = actions, in declaration order").containsExactly(Action.values());
        assertThat(doc).containsExactlyInAnyOrderEntriesOf(ScopePolicy.matrix());
    }

    @Test
    void aChangedCellIsDetected() throws IOException {
        String text = Files.readString(DESIGN, StandardCharsets.UTF_8).replace("\nSUPERSEDE,-,ORG,-,", "\nSUPERSEDE,-,ORG,OWN,");
        assertThat(parse(text)).isNotEqualTo(ScopePolicy.matrix());
        assertThatThrownBy(() -> parse("```authz-matrix\naction,AGENT\n```")).isInstanceOf(IllegalStateException.class);
    }
}

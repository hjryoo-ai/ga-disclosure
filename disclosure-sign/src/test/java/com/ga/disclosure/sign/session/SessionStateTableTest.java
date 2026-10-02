package com.ga.disclosure.sign.session;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설계서 §6.5의 {@code session-state-table} 블록(정본, CSV)을 파싱해 {@link SessionStateTable}(EnumMap)과 상태 4 × 사건 9 전수를
 * 양방향으로 대조한다(4 계획 §2.3 — 문서 상태표 W1과 같은 방식). 블록이 없거나 둘이거나 줄 형식이 틀리면 실패한다.
 */
class SessionStateTableTest {

    private static final Path DESIGN = Path.of(System.getProperty("ga.repoRoot"), "docs", "설계서.md");
    private static final Pattern BLOCK = Pattern.compile("```session-state-table\\n(.*?)\\n```", Pattern.DOTALL);

    static Map<SessionStatus, Map<SessionEvent, Set<SessionStatus>>> parse(String markdown) {
        Matcher m = BLOCK.matcher(markdown);
        if (!m.find()) {
            throw new IllegalStateException("설계서에 session-state-table 블록이 없다");
        }
        String body = m.group(1);
        if (m.find()) {
            throw new IllegalStateException("session-state-table 블록이 둘 이상이다");
        }
        List<String> lines = body.lines().toList();
        if (!lines.getFirst().equals("state,event,result")) {
            throw new IllegalStateException("머리줄이 state,event,result가 아니다: " + lines.getFirst());
        }
        Map<SessionStatus, Map<SessionEvent, Set<SessionStatus>>> out = new EnumMap<>(SessionStatus.class);
        for (SessionStatus s : SessionStatus.values()) {
            out.put(s, new EnumMap<>(SessionEvent.class));
        }
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            if (cells.length != 3) {
                throw new IllegalStateException("칸이 3개가 아니다: " + line);
            }
            Set<SessionStatus> results = Arrays.stream(cells[2].split("\\|")).map(SessionStatus::valueOf)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(SessionStatus.class)));
            if (out.get(SessionStatus.valueOf(cells[0])).put(SessionEvent.valueOf(cells[1]), results) != null) {
                throw new IllegalStateException("같은 칸이 두 번 나온다: " + line);
            }
        }
        return out;
    }

    @Test
    void designDocumentTableEqualsTheCodeTableCellByCell() throws IOException {
        Map<SessionStatus, Map<SessionEvent, Set<SessionStatus>>> doc = parse(Files.readString(DESIGN));
        int cells = 0;
        for (SessionStatus s : SessionStatus.values()) {
            for (SessionEvent e : SessionEvent.values()) {
                assertThat(SessionStateTable.results(s, e)).as("%s × %s", s, e).isEqualTo(doc.get(s).getOrDefault(e, Set.of()));
                cells++;
            }
        }
        assertThat(cells).isEqualTo(36);            // 상태 4 × 사건 9
    }

    @Test
    void closedStatesAcceptNothingAndOpenAcceptsEveryEvent() {
        for (SessionStatus s : SessionStatus.values()) {
            for (SessionEvent e : SessionEvent.values()) {
                assertThat(SessionStateTable.allows(s, e)).as("%s × %s", s, e).isEqualTo(!s.isClosed());
            }
        }
        // 취소하는 사건은 전부 사유가 있다
        for (SessionEvent e : SessionEvent.values()) {
            if (SessionStateTable.results(SessionStatus.OPEN, e).contains(SessionStatus.REVOKED)) {
                assertThat(SessionRevokeReason.of(e)).isNotNull();
            }
        }
    }

    @Test
    void outsideTheTableIsRejectedWithTheCell() {
        SessionEventRejected e = org.assertj.core.api.Assertions.catchThrowableOfType(SessionEventRejected.class,
                () -> SessionStateTable.target(SessionStatus.USED, SessionEvent.CAPTURE, SessionStatus.USED));
        assertThat(e.from()).isEqualTo(SessionStatus.USED);
        assertThat(e.event()).isEqualTo(SessionEvent.CAPTURE);
        assertThatThrownBy(() -> SessionStateTable.target(SessionStatus.OPEN, SessionEvent.CAPTURE, SessionStatus.REVOKED))
                .isInstanceOf(SessionEventRejected.class);
    }

    @Test
    void parserRejectsMalformedBlocks() {
        assertThatThrownBy(() -> parse("no block")).hasMessageContaining("없다");
        assertThatThrownBy(() -> parse("```session-state-table\nstate,event,result\nOPEN,CAPTURE,USED\n```\n```session-state-table\nstate,event,result\n```"))
                .hasMessageContaining("둘 이상");
        assertThatThrownBy(() -> parse("```session-state-table\nstate,cmd,result\n```")).hasMessageContaining("머리줄");
        assertThatThrownBy(() -> parse("```session-state-table\nstate,event,result\nOPEN,CAPTURE\n```")).hasMessageContaining("칸이 3개");
        assertThatThrownBy(() -> parse("```session-state-table\nstate,event,result\nOPEN,CAPTURE,USED\nOPEN,CAPTURE,USED\n```"))
                .hasMessageContaining("두 번");
        assertThatThrownBy(() -> parse("```session-state-table\nstate,event,result\nOPEN,SIGN,USED\n```"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

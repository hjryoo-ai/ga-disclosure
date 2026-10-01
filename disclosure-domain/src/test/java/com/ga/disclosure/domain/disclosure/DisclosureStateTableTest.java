package com.ga.disclosure.domain.disclosure;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
 * 3A W1(표 대조): 설계서 §6.1의 {@code state-table} 블록(정본, CSV)을 파싱해 {@link DisclosureStateTable}(EnumMap)과 상태 10 × 명령 11
 * 전수를 양방향으로 대조한다. 블록이 없거나 줄 형식이 틀리면 실패한다(계획 승인 B3). 구현된 명령의 표 밖 전이가 애그리게이트에서
 * {@link IllegalTransition}인지는 워크플로 모듈의 {@code DisclosureTransitionTest}가 증명한다.
 */
class DisclosureStateTableTest {

    private static final Path DESIGN = Path.of(System.getProperty("ga.repoRoot"), "docs", "설계서.md");
    private static final Pattern BLOCK = Pattern.compile("```state-table\\n(.*?)\\n```", Pattern.DOTALL);

    static Map<DisclosureStatus, Map<DisclosureCommand, Set<DisclosureStatus>>> parse(String markdown) {
        Matcher m = BLOCK.matcher(markdown);
        if (!m.find()) {
            throw new IllegalStateException("설계서에 state-table 블록이 없다");
        }
        String body = m.group(1);
        if (m.find()) {
            throw new IllegalStateException("state-table 블록이 둘 이상이다");
        }
        List<String> lines = body.lines().toList();
        if (!lines.getFirst().equals("state,command,result")) {
            throw new IllegalStateException("머리줄이 state,command,result가 아니다: " + lines.getFirst());
        }
        Map<DisclosureStatus, Map<DisclosureCommand, Set<DisclosureStatus>>> out = new EnumMap<>(DisclosureStatus.class);
        for (DisclosureStatus s : DisclosureStatus.values()) {
            out.put(s, new EnumMap<>(DisclosureCommand.class));
        }
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            if (cells.length != 3) {
                throw new IllegalStateException("칸이 3개가 아니다: " + line);
            }
            Set<DisclosureStatus> results = Arrays.stream(cells[2].split("\\|")).map(DisclosureStatus::valueOf)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(DisclosureStatus.class)));
            if (out.get(DisclosureStatus.valueOf(cells[0])).put(DisclosureCommand.valueOf(cells[1]), results) != null) {
                throw new IllegalStateException("같은 칸이 두 번 나온다: " + line);
            }
        }
        return out;
    }

    private static Map<DisclosureStatus, Map<DisclosureCommand, Set<DisclosureStatus>>> documented() throws IOException {
        return parse(Files.readString(DESIGN));
    }

    @Test
    void designDocumentTableEqualsTheCodeTableCellByCell() throws IOException {
        Map<DisclosureStatus, Map<DisclosureCommand, Set<DisclosureStatus>>> doc = documented();
        int cells = 0;
        for (DisclosureStatus s : DisclosureStatus.values()) {
            for (DisclosureCommand c : DisclosureCommand.values()) {
                assertThat(DisclosureStateTable.results(s, c)).as("%s × %s", s, c)
                        .isEqualTo(doc.get(s).getOrDefault(c, Set.of()));
                cells++;
            }
        }
        assertThat(cells).isEqualTo(110);           // 상태 10 × 명령 11(3B REBASE)
    }

    @ParameterizedTest
    @EnumSource(DisclosureStatus.class)
    void cellsOutsideTheTableAreIllegalTransitions(DisclosureStatus from) {
        for (DisclosureCommand c : DisclosureCommand.values()) {
            if (DisclosureStateTable.allows(from, c)) {
                DisclosureStateTable.require(from, c);
                continue;
            }
            IllegalTransition e = org.assertj.core.api.Assertions.catchThrowableOfType(IllegalTransition.class,
                    () -> DisclosureStateTable.require(from, c));
            assertThat(e.from()).isEqualTo(from);
            assertThat(e.command()).isEqualTo(c);
        }
    }

    @Test
    void terminalStatesAllowNothingAndMutableStatesNeverReachSealedExceptBySeal() {
        assertThat(DisclosureStateTable.table().get(DisclosureStatus.VOID)).isEmpty();
        assertThat(DisclosureStateTable.table().get(DisclosureStatus.SUPERSEDED)).isEmpty();
        for (DisclosureStatus s : DisclosureStatus.values()) {
            for (DisclosureCommand c : DisclosureCommand.values()) {
                Set<DisclosureStatus> r = DisclosureStateTable.results(s, c);
                if (!s.isMutable()) {
                    assertThat(r).as("봉인 이후 → 가변 상태 회귀 없음 %s × %s", s, c).noneMatch(DisclosureStatus::isMutable);
                }
                if (s.isMutable() && r.stream().anyMatch(DisclosureStatus::isSealedOrLater) && c != DisclosureCommand.VOID) {
                    assertThat(c).isEqualTo(DisclosureCommand.SEAL);
                }
            }
        }
        assertThatThrownBy(() -> DisclosureStateTable.target(DisclosureStatus.GRADED, DisclosureCommand.REPLACE_ITEMS, DisclosureStatus.GRADED))
                .isInstanceOf(IllegalTransition.class);
        assertThat(DisclosureStateTable.target(DisclosureStatus.GRADED, DisclosureCommand.REPLACE_ITEMS, DisclosureStatus.COMPARED))
                .isEqualTo(DisclosureStatus.COMPARED);
    }

    @Test
    void parserRejectsMalformedBlocks() {
        assertThatThrownBy(() -> parse("no block")).hasMessageContaining("없다");
        assertThatThrownBy(() -> parse("```state-table\nstate,command\nDRAFT,COMPARE\n```")).hasMessageContaining("머리줄");
        assertThatThrownBy(() -> parse("```state-table\nstate,command,result\nDRAFT,COMPARE\n```")).hasMessageContaining("3개");
        assertThatThrownBy(() -> parse("```state-table\nstate,command,result\nDRAFT,COMPARE,COMPARED\nDRAFT,COMPARE,DRAFT\n```"))
                .hasMessageContaining("두 번");
        assertThatThrownBy(() -> parse("```state-table\nstate,command,result\nDRAFT,COMPARE,SEALD\n```"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

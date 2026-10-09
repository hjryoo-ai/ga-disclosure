package com.ga.disclosure.workflow;

import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.CommandRejectedException;
import com.ga.disclosure.workflow.disclosure.CommandResult;
import com.ga.disclosure.workflow.disclosure.LifecycleService;
import com.ga.disclosure.workflow.disclosure.SealService;
import com.ga.disclosure.workflow.feed.EventFeed;
import com.ga.disclosure.workflow.flag.FlagRejectedException;
import com.ga.disclosure.workflow.retention.LegalHoldRejectedException;
import com.ga.disclosure.workflow.sign.SignRejection;
import com.ga.disclosure.workflow.verify.ReceiptExporter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설계서 §7의 {@code rejection-categories} 블록(정본)과 거부 코드의 범주(6A 계획 §4.2 — CONFLICT 409·INVALID 422)를 <b>닫힌 집합으로 양방향</b> 대조한다.
 * 코드 쪽 집합은 손으로 적지 않는다: workflow 클래스 전부를 훑어 {@link RejectionCategory.Categorized}를 구현한 enum을 모두 찾는다(거부 코드는 이 enum으로만
 * 만들 수 있다 — {@code CommandRejectedException}·{@code LegalHoldRejectedException}도 enum만 받는다). 찾은 계열이 아래 출처 표에 없으면 실패하고, 블록의
 * 행과 코드의 값은 하나도 남김없이 같아야 한다. (6B 7단계 회신 ②: 이전 시험은 {@code CommandRejected} 행을 코드와 대조하지 않아 {@code BATCH_REF_REUSED}
 * 누락을 못 봤고, 산출물 열람 거부·앵커 영수증 미가용 코드는 블록에 없었다.)
 */
class RejectionCategoryTableTest {

    private static final Path DESIGN = Path.of(System.getProperty("ga.repoRoot"), "docs", "설계서.md");
    private static final Pattern BLOCK = Pattern.compile("```rejection-categories\\n(.*?)\\n```", Pattern.DOTALL);

    /** 거부 계열 → 블록의 출처 이름. 새 계열은 여기와 블록에 같은 커밋에서. */
    static final Map<Class<?>, String> SOURCES = Map.of(
            CommandResult.Rejection.class, "CommandResult",
            LifecycleService.Rejection.class, "LifecycleService",
            SealService.Rejection.class, "SealService",
            SignRejection.class, "SignRejection",
            LegalHoldRejectedException.Code.class, "LegalHold",
            EventFeed.Rejection.class, "EventFeed",
            FlagRejectedException.Rejection.class, "FlagRejection",
            CommandRejectedException.Code.class, "CommandRejected",
            ArtifactService.View.Reason.class, "ArtifactView",
            ReceiptExporter.Unavailable.class, "AnchorReceipt");

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

    /** workflow 출력(클래스 디렉터리 또는 jar)에서 {@code Categorized}를 구현한 enum 전부. */
    static List<Class<?>> discovered() throws IOException, URISyntaxException, ClassNotFoundException {
        Path location = Path.of(RejectionCategory.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isDirectory(location)) {
            return scan(location);
        }
        try (java.nio.file.FileSystem jar = java.nio.file.FileSystems.newFileSystem(location)) {
            return scan(jar.getPath("/"));
        }
    }

    private static List<Class<?>> scan(Path root) throws IOException, ClassNotFoundException {
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".class") && !f.getFileName().toString().equals("module-info.class")).toList()) {
                String name = root.relativize(p).toString().replace(p.getFileSystem().getSeparator(), ".").replaceAll("\\.class$", "");
                Class<?> c = Class.forName(name, false, RejectionCategory.class.getClassLoader());
                if (c.isEnum() && RejectionCategory.Categorized.class.isAssignableFrom(c)) {
                    out.add(c);
                }
            }
        }
        assertThat(out).as("the scan found the rejection enums").isNotEmpty();
        return out;
    }

    static Map<String, String> coded() throws Exception {
        Map<String, String> out = new TreeMap<>();
        for (Class<?> family : discovered()) {
            assertThat(SOURCES).as("거부 계열 " + family.getName() + "이 블록 출처 표에 없다 — 설계서 블록과 SOURCES에 같은 커밋에서").containsKey(family);
            for (Object constant : family.getEnumConstants()) {
                RejectionCategory.Categorized r = (RejectionCategory.Categorized) constant;
                out.put(SOURCES.get(family) + "." + r.name(), r.category().name());
            }
        }
        return out;
    }

    @Test
    void theDocumentedTableAndTheCodeAreTheSameClosedSet() throws Exception {
        assertThat(discovered()).as("every mapped family is still a rejection enum").containsAll(SOURCES.keySet());
        assertThat(documented()).isEqualTo(coded());
    }

    @Test
    void everyArtifactDenialHasAReceiptCodeAndTheOperatorFormKeepsItsColon() {
        for (ArtifactService.View.Reason r : ArtifactService.View.Reason.values()) {
            assertThat(ReceiptExporter.Unavailable.packageUnavailable(r).code()).isEqualTo("PACKAGE_UNAVAILABLE:" + r.name());
        }
        assertThat(ReceiptExporter.Unavailable.NOT_SEALED.code()).isEqualTo("NOT_SEALED");
    }

    @Test
    void mixedRejectionsAreAConflict() {
        assertThat(RejectionCategory.of(List.of(SignRejection.IDENTITY_INCOMPLETE, SignRejection.ALREADY_SIGNED))).isEqualTo(RejectionCategory.CONFLICT);
        assertThat(RejectionCategory.of(List.of(SignRejection.IDENTITY_INCOMPLETE))).isEqualTo(RejectionCategory.INVALID);
        assertThat(RejectionCategory.of(List.of())).isEqualTo(RejectionCategory.INVALID);
    }
}

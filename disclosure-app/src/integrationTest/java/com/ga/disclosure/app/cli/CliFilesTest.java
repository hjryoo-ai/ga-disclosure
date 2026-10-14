package com.ga.disclosure.app.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운영자 CLI 출력 파일(10단계 수용 회신): 경로에 미리 놓인 심볼릭 링크를 따라가지 않고, 언제나 소유자 전용이다. 경합(만든 뒤 바꿔치기)은 시험으로 재현하기
 * 어렵다 — 그 재발은 ArchUnit {@code fileWritesGoThroughOwnerOnlyCreation}이 막고, 여기서는 미리 놓인 링크와 권한을 본다.
 */
class CliFilesTest {

    @TempDir
    Path dir;

    @Test
    void replaceSwapsThePlantedLinkInsteadOfWritingThroughIt() throws Exception {
        Path victim = Files.writeString(dir.resolve("victim.txt"), "do not touch");
        Path out = dir.resolve("artifact.pdf");
        Files.createSymbolicLink(out, victim);
        CliFiles.replace(out, "plaintext".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readString(victim)).isEqualTo("do not touch");
        assertThat(Files.isSymbolicLink(out)).isFalse();
        assertThat(Files.readString(out)).isEqualTo("plaintext");
        assertThat(Files.getPosixFilePermissions(out, LinkOption.NOFOLLOW_LINKS))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        try (var leftovers = Files.list(dir)) {
            assertThat(leftovers.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("victim.txt", "artifact.pdf");
        }
    }

    @Test
    void replaceOfAWorldReadableFileLeavesAnOwnerOnlyFile() throws Exception {
        Path out = Files.writeString(dir.resolve("report.json"), "old");
        Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rw-r--r--"));
        CliFiles.replace(out, "new".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readString(out)).isEqualTo("new");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(out))).isEqualTo("rw-------");
    }

    @Test
    void createNewRefusesAnyExistingPathIncludingADanglingLink() throws Exception {
        Path link = dir.resolve("base.tar");
        Files.createSymbolicLink(link, dir.resolve("elsewhere"));
        assertThatThrownBy(() -> CliFiles.createNew(link)).isInstanceOf(CliFailure.class).hasMessage("refusing to overwrite base.tar");
        assertThat(Files.exists(dir.resolve("elsewhere"))).isFalse();
    }
}

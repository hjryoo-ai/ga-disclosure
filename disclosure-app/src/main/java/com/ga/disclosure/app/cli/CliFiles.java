package com.ga.disclosure.app.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * 운영자 CLI가 쓰는 파일(Phase 8 10단계 보안 검토·수용 회신): 열어 본 산출물(고객 성명이 든 PDF)·보고서·영수증·백업 평문 — 언제나 <b>소유자 전용(600)</b>으로,
 * 경로에 미리 놓인 심볼릭 링크를 따라가지 않게.
 * <ul>
 *   <li>{@link #createNew}: 만들기·열기를 한 번에({@code O_CREAT|O_EXCL}) — 이미 있으면(링크 포함) 거부. 백업 평문·봉투처럼 덮어쓰면 안 되는 것.</li>
 *   <li>{@link #replace}: 같은 디렉터리에 무작위 이름의 새 파일(위와 같이)을 쓰고 대상 이름으로 원자적 이름 바꾸기 — 이름 바꾸기는 대상 자리의 링크를 따라가지
 *       않고 그 이름을 바꿔 놓는다. 다시 돌리는 스크립트(seed.sh)가 같은 출력 경로를 다시 쓸 수 있다.</li>
 * </ul>
 * 운영 코드의 파일 쓰기 API는 이 클래스와 닫힌 허용 목록에서만(ArchUnit {@code fileWritesGoThroughOwnerOnlyCreation}).
 */
final class CliFiles {

    private static final Set<OpenOption> CREATE_NEW = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

    private CliFiles() {
    }

    static OutputStream createNew(Path file) throws IOException {
        try {
            SeekableByteChannel channel = file.getFileSystem().supportedFileAttributeViews().contains("posix")
                    ? Files.newByteChannel(file, CREATE_NEW, PosixFilePermissions.asFileAttribute(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)))
                    : Files.newByteChannel(file, CREATE_NEW);
            return Channels.newOutputStream(channel);
        } catch (FileAlreadyExistsException e) {
            throw new CliFailure("refusing to overwrite " + file.getFileName());
        }
    }

    static void replace(Path target, byte[] bytes) {
        Path absolute = target.toAbsolutePath();
        Path dir = absolute.getParent();
        Path part = dir.resolve("." + absolute.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            Files.createDirectories(dir);
            try (OutputStream out = createNew(part)) {
                out.write(bytes);
            }
            try {
                Files.move(part, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // 이름을 바꾼 뒤에는 없다 — 실패한 쓰기의 조각만 남을 수 있다(소유자 전용)
            }
        }
    }
}

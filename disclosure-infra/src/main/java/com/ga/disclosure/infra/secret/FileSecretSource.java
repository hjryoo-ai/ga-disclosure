package com.ga.disclosure.infra.secret;

import com.ga.disclosure.workflow.secret.SecretMissingException;
import com.ga.disclosure.workflow.secret.SecretName;
import com.ga.disclosure.workflow.secret.SecretSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 파일 비밀 출처: 이름 = 출처 디렉터리 아래 상대 경로(쿠버네티스 Secret 볼륨 마운트·로컬 디렉터리). 심볼릭 링크는 따라가되(Secret 볼륨은 {@code ..data}
 * 링크다) 실제 파일이 출처 디렉터리의 실제 경로 밖이면 거부한다. 권한: 기타 사용자 권한·그룹 쓰기·실행 비트가 있으면 거부, 그룹 읽기는
 * {@code allowGroupRead}일 때만(쿠버네티스 Secret 볼륨은 파일이 root 소유라 비루트 파드가 {@code fsGroup}으로 읽는다 — 로컬 디렉터리는 소유자 전용).
 * 오류 문장에는 이름만 싣는다(경로·값 없음).
 */
public final class FileSecretSource implements SecretSource {

    private static final Set<PosixFilePermission> NEVER = EnumSet.of(PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OWNER_EXECUTE);

    private final Path root;
    private final boolean allowGroupRead;

    public FileSecretSource(Path root, boolean allowGroupRead) {
        Objects.requireNonNull(root, "root");
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("secret directory is not available");
        }
        if (!Files.isDirectory(this.root)) {
            throw new IllegalStateException("secret directory is not a directory");
        }
        this.allowGroupRead = allowGroupRead;
    }

    @Override
    public byte[] read(SecretName name) {
        Path file = resolve(name).orElseThrow(() -> new SecretMissingException(name));
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read secret " + name, e);
        }
    }

    @Override
    public boolean exists(SecretName name) {
        return resolve(name).isPresent();
    }

    private Optional<Path> resolve(SecretName name) {
        Path candidate = root.resolve(name.value()).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("secret " + name + " is outside the secret directory");
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot resolve secret " + name, e);
        }
        if (!real.startsWith(root) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("secret " + name + " is not a regular file inside the secret directory");
        }
        requirePermissions(name, real);
        return Optional.of(real);
    }

    private void requirePermissions(SecretName name, Path file) {
        if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            boolean groupRead = perms.contains(PosixFilePermission.GROUP_READ);
            if (perms.stream().anyMatch(NEVER::contains) || (groupRead && !allowGroupRead)) {
                throw new IllegalStateException("secret " + name + " must be readable by its owner only (chmod 600"
                        + (allowGroupRead ? " or 640/440 for a mounted secret" : "") + ")");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read permissions of secret " + name, e);
        }
    }

    /**
     * 새 비밀 파일을 소유자 전용(600)으로 만든다 — 이미 있으면 거부(덮어쓰면 그 키로 감싼 것이 모두 풀리지 않는다). 개발·kind 로컬 비밀 생성 전용(운영은
     * 비밀 저장소가 만든다).
     */
    public static void create(Path root, SecretName name, byte[] content) {
        Path file = root.resolve(name.value()).normalize();
        if (!file.startsWith(root.normalize())) {
            throw new IllegalArgumentException("secret " + name + " is outside the secret directory");
        }
        try {
            Files.createDirectories(file.getParent());
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
            } else {
                Files.createFile(file);
            }
            Files.write(file, content);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("secret " + name + " already exists");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create secret " + name, e);
        }
    }
}

package com.ga.disclosure.infra.crypto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;

/**
 * 저장소 밖의 서버 비밀 키 파일(base64 한 줄) — 목록 커서 키·멱등 요청 해시 키가 같은 규약을 쓴다(로컬 KEK와 같다): 없으면 소유자 전용(600)으로 무작위 키를
 * 만들고, 있으면 그룹·기타 권한이 있거나 길이가 틀리면 기동 실패.
 */
final class OwnerOnlyKeyFile {

    private OwnerOnlyKeyFile() {
    }

    /** 키를 읽는다(없으면 만든다). {@code label}은 오류 문장의 이름(예: "cursor"). */
    static byte[] loadOrCreate(Path file, int bytes, String label) {
        Objects.requireNonNull(file, "file");
        try {
            if (!Files.exists(file)) {
                create(file, bytes);
            }
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                for (PosixFilePermission p : Files.getPosixFilePermissions(file)) {
                    if (p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")) {
                        throw new IllegalStateException(label + " key file " + file + " must be readable by its owner only (chmod 600)");
                    }
                }
            }
            byte[] key = Base64.getDecoder().decode(Files.readString(file).strip());
            if (key.length != bytes) {
                throw new IllegalStateException(label + " key file " + file + " does not hold a " + bytes + "-byte key");
            }
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + label + " key file " + file, e);
        }
    }

    private static void create(Path file, int bytes) throws IOException {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try {
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
            } else {
                Files.createFile(file);
            }
        } catch (FileAlreadyExistsException raced) {
            return;
        }
        Files.writeString(file, Base64.getEncoder().encodeToString(key));
    }
}

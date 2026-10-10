package com.ga.disclosure.infra.crypto;

import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <b>전역 시절 KEK</b>(Phase 2~7 — 8 계획 승인 Q2 이전): 테넌트와 무관한 KEK 하나. Phase 8 1a에서는 {@link TenantKeyProvider}가 이행 중 옛 키를 풀 때만
 * 쓴다(새로 감싸지 않는다). 재래핑이 끝나면 지운다(1b). 형식: 저장소 밖의 로컬 키 파일 {@code {"current": "KEK-…", "keys": {"KEK-…": "<base64 32바이트>"}}}.
 * 파일은 소유자만 읽을 수 있어야 한다(POSIX 파일 시스템에서 그룹·기타 권한이 있으면 거부). 감싸기는 AES-256-GCM이고 AAD =
 * JCS {@code {"kekId","keyId","tenantId","v":1}} — 감싼 DEK를 다른 테넌트·키 ID로 옮기면 풀리지 않는다.
 * 운영은 KMS 어댑터로 교체한다(같은 컨텍스트를 KMS 암호화 컨텍스트로).
 */
public final class LocalFileKeyProvider {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String current;
    private final Map<String, byte[]> keys;

    private LocalFileKeyProvider(String current, Map<String, byte[]> keys) {
        this.current = current;
        this.keys = Map.copyOf(keys);
        if (!this.keys.containsKey(current)) {
            throw new IllegalStateException("key file has no key for current KEK " + current);
        }
    }

    public static LocalFileKeyProvider load(Path file) {
        Objects.requireNonNull(file, "file");
        try {
            requireOwnerOnly(file);
            JsonNode root = JSON.readTree(Files.readString(file));
            Map<String, byte[]> keys = new HashMap<>();
            for (Map.Entry<String, JsonNode> e : root.required("keys").properties()) {
                byte[] key = Base64.getDecoder().decode(e.getValue().asString());
                if (key.length != AesGcm.KEY_BYTES) {
                    throw new IllegalStateException("KEK " + e.getKey() + " is not " + AesGcm.KEY_BYTES + " bytes");
                }
                keys.put(e.getKey(), key);
            }
            return new LocalFileKeyProvider(root.required("current").asString(), keys);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read local KEK file " + file, e);
        }
    }

    /** 새 키 파일(소유자 읽기·쓰기만)을 만든다. 이미 있으면 거부 — 기존 KEK를 덮어쓰면 모든 DEK가 풀리지 않는다. */
    public static void initialize(Path file, String kekId) {
        ObjectNode root = JSON.createObjectNode().put("current", kekId);
        root.putObject("keys").put(kekId, Base64.getEncoder().encodeToString(AesGcm.newKey()));
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
            } else {
                Files.createFile(file);
            }
            Files.writeString(file, JSON.writeValueAsString(root));
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("KEK file already exists: " + file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create local KEK file " + file, e);
        }
    }

    private static void requireOwnerOnly(Path file) throws IOException {
        if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        for (PosixFilePermission p : perms) {
            if (p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")) {
                throw new IllegalStateException("local KEK file " + file + " must be readable by its owner only (chmod 600)");
            }
        }
    }

    public String currentKekId() {
        return current;
    }

    public byte[] wrap(TenantId tenant, String keyId, String kekId, byte[] dataKey) {
        return AesGcm.encrypt(kek(kekId), dataKey, KekContext.aad(tenant, keyId, kekId));
    }

    public byte[] unwrap(TenantId tenant, String keyId, String kekId, byte[] wrapped) {
        return AesGcm.decrypt(kek(kekId), wrapped, KekContext.aad(tenant, keyId, kekId));
    }

    private byte[] kek(String kekId) {
        byte[] key = keys.get(kekId);
        if (key == null) {
            throw new CiphertextRejectedException("KEK " + kekId + " is not available in the local key file");
        }
        return key;
    }
}

package com.ga.disclosure.infra.crypto;

import com.ga.disclosure.workflow.customer.KeyProviderPort;
import com.ga.disclosure.workflow.secret.SecretName;
import com.ga.disclosure.workflow.secret.SecretSource;
import com.ga.platform.core.tenant.TenantId;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 테넌트 KEK 어댑터(Phase 8, 8 계획 승인 Q2). KEK ID 형식은 {@code {tenant}-KEK-{n}}(V21 CHECK)이고 바이트는 비밀 {@code kek/{tenant}/{kekId}}(base64
 * 32바이트)다 — 테넌트가 다른 KEK로는 감싸지도 풀지도 않는다(ID 접두 대조 + AAD). 새 DEK의 KEK는 레지스트리의 CURRENT({@code current} 함수 — 설정이
 * 그 테넌트 트랜잭션에서 읽는다). 운영 KMS는 같은 컨텍스트를 KMS 암호화 컨텍스트로 쓰는 다른 어댑터다(실 연동 없음).
 *
 * <p><b>이행(1a) 동안만</b> 전역 시절 KEK ID(형식이 다른 ID)로 감싼 키를 {@link LocalFileKeyProvider}로 푼다(이중 읽기 — 감싼 행의 KEK ID가 어느 키인지
 * 말한다). 전역 KEK로는 새로 감싸지 않는다. 재래핑이 끝나면 그 경로를 지운다(1b).
 */
public final class TenantKeyProvider implements KeyProviderPort {

    private static final Pattern KEK_ID = Pattern.compile("([A-Z0-9][A-Z0-9_]{0,31})-KEK-[1-9][0-9]{0,5}");

    private final SecretSource secrets;
    private final Function<TenantId, Optional<String>> current;
    private final LocalFileKeyProvider legacy;
    private final ConcurrentHashMap<SecretName, byte[]> cache = new ConcurrentHashMap<>();

    /** @param legacy 전역 시절 KEK(이행 중 풀기 전용) — 없으면 null */
    public TenantKeyProvider(SecretSource secrets, Function<TenantId, Optional<String>> current, LocalFileKeyProvider legacy) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.current = Objects.requireNonNull(current, "current");
        this.legacy = legacy;
    }

    /** 테넌트 KEK의 비밀 이름. ID 형식이 아니거나 다른 테넌트의 ID면 빈 값. */
    public static Optional<SecretName> secretName(TenantId tenant, String kekId) {
        Matcher m = KEK_ID.matcher(kekId);
        if (!m.matches() || !m.group(1).equals(tenant.value())) {
            return Optional.empty();
        }
        return Optional.of(SecretName.of("kek/" + tenant.value() + "/" + kekId));
    }

    @Override
    public String currentKekId(TenantId tenant) {
        return current.apply(tenant).orElseThrow(() -> new IllegalStateException("tenant " + tenant + " has no registered KEK"));
    }

    @Override
    public byte[] wrap(TenantId tenant, String keyId, String kekId, byte[] dataKey) {
        SecretName name = secretName(tenant, kekId)
                .orElseThrow(() -> new IllegalArgumentException("KEK " + kekId + " is not a KEK of tenant " + tenant));
        return AesGcm.encrypt(kek(name), dataKey, KekContext.aad(tenant, keyId, kekId));
    }

    @Override
    public byte[] unwrap(TenantId tenant, String keyId, String kekId, byte[] wrapped) {
        Optional<SecretName> name = secretName(tenant, kekId);
        if (name.isPresent()) {
            return AesGcm.decrypt(kek(name.get()), wrapped, KekContext.aad(tenant, keyId, kekId));
        }
        if (legacy != null) {
            return legacy.unwrap(tenant, keyId, kekId, wrapped);
        }
        throw new CiphertextRejectedException("KEK " + kekId + " is not a KEK of tenant " + tenant);
    }

    @Override
    public byte[] rewrap(TenantId tenant, String keyId, String fromKekId, String toKekId, byte[] wrapped) {
        byte[] dek = unwrap(tenant, keyId, fromKekId, wrapped);
        byte[] check = null;
        try {
            byte[] fresh = wrap(tenant, keyId, toKekId, dek);
            check = unwrap(tenant, keyId, toKekId, fresh);
            if (!MessageDigest.isEqual(dek, check)) {
                throw new IllegalStateException("rewrapped key does not unwrap to the same data key");
            }
            return fresh;
        } finally {
            Arrays.fill(dek, (byte) 0);
            if (check != null) {
                Arrays.fill(check, (byte) 0);
            }
        }
    }

    private byte[] kek(SecretName name) {
        return cache.computeIfAbsent(name, n -> secrets.key(n, AesGcm.KEY_BYTES));
    }
}

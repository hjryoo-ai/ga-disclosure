package com.ga.disclosure.infra.crypto;

import com.ga.disclosure.workflow.secret.SecretName;
import com.ga.disclosure.workflow.secret.SecretSource;
import com.ga.disclosure.workflow.idempotency.RequestHashPort;

import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 멱등 요청 해시 키(6B 계획 §A-2): 비밀 {@code api/request-hash}(Phase 8 {@link SecretSource}, base64 32바이트 — 웹은 필수)로 HMAC-SHA256. 규약은 커서
 * 키와 같다. 키가 바뀌면 진행 중 청구는 다른 해시가 되어 재생되지 않고 새 요청으로 처리된다(부작용은 업무 상태 가드가 막는다).
 */
public final class RequestHashKey implements RequestHashPort {

    static final int KEY_BYTES = 32;

    private final byte[] key;

    RequestHashKey(byte[] key) {
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("request hash key must be " + KEY_BYTES + " bytes");
        }
        this.key = key.clone();
    }

    public static final SecretName SECRET = SecretName.of("api/request-hash");

    public static RequestHashKey fromSecret(SecretSource secrets) {
        return new RequestHashKey(secrets.key(SECRET, KEY_BYTES));
    }

    @Override
    public String hash(byte[] canonicalInput) {
        Objects.requireNonNull(canonicalInput, "canonicalInput");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonicalInput));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}

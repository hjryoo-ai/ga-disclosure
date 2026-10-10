package com.ga.disclosure.infra.crypto;

import com.ga.disclosure.workflow.secret.SecretName;
import com.ga.disclosure.workflow.secret.SecretSource;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.workflow.customer.CustomerReceiptPort;
import com.ga.disclosure.workflow.customer.RegistrationKey;
import com.ga.platform.core.tenant.TenantId;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 고객 등록 영수증 ID(6B §9, 10단계 회신 ②): HMAC-SHA256(키, "ga-customer-receipt/v1" ‖ 0x00 ‖ 테넌트 ‖ 0x00 ‖ 가명 ‖ 0x00 ‖ 등록 키)의 앞 16바이트를
 * RFC 9562 UUIDv8(사용자 정의) 모양으로. 키는 비밀 {@code api/customer-receipt}(Phase 8 {@link SecretSource} — 웹은 필수, 커서 키와 같은
 * 규약). 요청 해시 키와 따로 둔다 — 요청 해시 키를 바꿔도 같은 등록의 영수증이 바뀌지 않는다. 이 키를 바꾸면 이후 NOOP 응답의
 * 영수증이 첫 응답과 달라진다(배포 노트).
 */
public final class CustomerReceiptKey implements CustomerReceiptPort {

    static final int KEY_BYTES = 32;
    private static final byte[] LABEL = "ga-customer-receipt/v1".getBytes(StandardCharsets.US_ASCII);

    private final byte[] key;

    CustomerReceiptKey(byte[] key) {
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("customer receipt key must be " + KEY_BYTES + " bytes");
        }
        this.key = key.clone();
    }

    public static final SecretName SECRET = SecretName.of("api/customer-receipt");

    public static CustomerReceiptKey fromSecret(SecretSource secrets) {
        return new CustomerReceiptKey(secrets.key(SECRET, KEY_BYTES));
    }

    /** CLI: 프로세스마다 새 키(CLI는 HTTP 영수증을 내지 않는다 — 조립만 맞춘다). */
    public static CustomerReceiptKey ephemeral() {
        byte[] key = new byte[KEY_BYTES];
        new java.security.SecureRandom().nextBytes(key);
        return new CustomerReceiptKey(key);
    }

    @Override
    public UUID receipt(TenantId tenant, CustomerRef ref, RegistrationKey registrationKey) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(registrationKey, "registrationKey");
        byte[] mac;
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            hmac.update(LABEL);
            for (String part : new String[] {tenant.value(), ref.value(), registrationKey.value()}) {
                hmac.update((byte) 0);
                hmac.update(part.getBytes(StandardCharsets.UTF_8));
            }
            mac = hmac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
        mac[6] = (byte) ((mac[6] & 0x0f) | 0x80);                       // version 8
        mac[8] = (byte) ((mac[8] & 0x3f) | 0x80);                       // variant 10
        ByteBuffer b = ByteBuffer.wrap(mac, 0, 16);
        return new UUID(b.getLong(), b.getLong());
    }
}

package com.ga.disclosure.infra.crypto;

import com.ga.disclosure.domain.enums.PiiField;
import com.ga.disclosure.domain.pii.BirthDate;
import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.domain.pii.SensitiveValue;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;

/**
 * {@code customer_ref}의 암호문 컬럼 암·복호화. AAD = JCS {@code {"column","customerRef","keyId","table":"customer_ref","tenantId","v":1}}
 * — 암호문을 다른 행(고객·테넌트)·다른 컬럼·다른 키 ID 표기로 옮겨 붙이면 태그 검증이 실패한다(Phase 2 P3).
 */
public final class CustomerFieldCipher {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CustomerFieldCipher() {
    }

    public static byte[] encrypt(byte[] dataKey, TenantId tenant, CustomerRef ref, String keyId, Sensitive<? extends SensitiveValue> value) {
        byte[] plaintext = value.reveal(v -> v.canonical().getBytes(StandardCharsets.UTF_8));
        try {
            return AesGcm.encrypt(dataKey, plaintext, aad(tenant, ref, value.field(), keyId));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public static Sensitive<CustomerName> decryptName(byte[] dataKey, TenantId tenant, CustomerRef ref, String keyId, byte[] envelope) {
        return CustomerName.of(decrypt(dataKey, tenant, ref, PiiField.NAME, keyId, envelope));
    }

    public static Sensitive<PhoneNumber> decryptPhone(byte[] dataKey, TenantId tenant, CustomerRef ref, String keyId, byte[] envelope) {
        return PhoneNumber.of(decrypt(dataKey, tenant, ref, PiiField.PHONE, keyId, envelope));
    }

    public static Sensitive<BirthDate> decryptBirthDate(byte[] dataKey, TenantId tenant, CustomerRef ref, String keyId, byte[] envelope) {
        return BirthDate.of(LocalDate.parse(decrypt(dataKey, tenant, ref, PiiField.BIRTH_DATE, keyId, envelope)));
    }

    private static String decrypt(byte[] dataKey, TenantId tenant, CustomerRef ref, PiiField field, String keyId, byte[] envelope) {
        byte[] plaintext = AesGcm.decrypt(dataKey, envelope, aad(tenant, ref, field, keyId));
        try {
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    static byte[] aad(TenantId tenant, CustomerRef ref, PiiField field, String keyId) {
        ObjectNode aad = JSON.createObjectNode().put("column", field.column()).put("customerRef", ref.value()).put("keyId", keyId)
                .put("table", "customer_ref").put("tenantId", tenant.value()).put("v", 1);
        return Canonicalizer.canonicalize(aad);
    }
}

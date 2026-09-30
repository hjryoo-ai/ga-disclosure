package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.pii.BirthDate;
import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.infra.crypto.AesGcm;
import com.ga.disclosure.infra.crypto.CiphertextRejectedException;
import com.ga.disclosure.infra.crypto.CustomerFieldCipher;
import com.ga.disclosure.workflow.customer.Customer;
import com.ga.disclosure.workflow.customer.CustomerVault;
import com.ga.disclosure.workflow.customer.KeyProviderPort;
import com.ga.disclosure.workflow.customer.NewCustomer;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CustomerVault} 어댑터: 테넌트 데이터 키(DEK, {@code customer_data_key}에 KEK로 감싼 형태)로 {@code customer_ref}의
 * 이름·연락처·생년월일을 AES-256-GCM 암호문으로 저장·복호화한다(설계서 §9). DEK 평문은 호출 한 번 동안만 메모리에 있고
 * 사용 뒤 0으로 지운다. 평문·암호문·키는 예외 메시지에 넣지 않는다.
 */
@Repository
public class CustomerVaultRepository extends TenantScopedRepository implements CustomerVault {

    private final KeyProviderPort keys;

    public CustomerVaultRepository(TenantJdbcGateway gateway, KeyProviderPort keys) {
        super(gateway);
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    private record StoredKey(String keyId, String kekId, byte[] wrapped, String status) {
    }

    private record StoredRow(CustomerRef ref, byte[] name, byte[] phone, byte[] birthDate, String keyId, Instant createdAt) {
    }

    @Override
    public String insert(CustomerRef ref, NewCustomer customer, Instant createdAt) {
        TenantId tenant = TenantContext.current();
        StoredKey key = activeKey().orElseGet(() -> createKey(createdAt));
        byte[] dek = unwrap(tenant, key);
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("customerRef", ref.value());
            params.put("name", CustomerFieldCipher.encrypt(dek, tenant, ref, key.keyId(), customer.name()));
            params.put("phone", customer.phone().map(p -> CustomerFieldCipher.encrypt(dek, tenant, ref, key.keyId(), p)).orElse(null));
            params.put("birthDate", customer.birthDate().map(b -> CustomerFieldCipher.encrypt(dek, tenant, ref, key.keyId(), b)).orElse(null));
            params.put("keyId", key.keyId());
            params.put("createdAt", Timestamp.from(createdAt));
            update("""
                    INSERT INTO customer_ref (tenant_id, customer_ref, name_enc, phone_enc, birth_date_enc, enc_key_id, created_at)
                    VALUES (:tenantId, :customerRef, :name, :phone, :birthDate, :keyId, :createdAt)
                    """, params);
            return key.keyId();
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    @Override
    public Optional<Customer> find(CustomerRef ref) {
        TenantId tenant = TenantContext.current();
        return queryAtMostOne("""
                SELECT customer_ref, name_enc, phone_enc, birth_date_enc, enc_key_id, created_at
                  FROM customer_ref
                 WHERE tenant_id = :tenantId
                   AND customer_ref = :customerRef
                """, Map.of("customerRef", ref.value()), (rs, n) -> row(rs))
                .map(row -> {
                    StoredKey key = key(row.keyId());
                    byte[] dek = unwrap(tenant, key);
                    try {
                        return decrypt(dek, tenant, row);
                    } finally {
                        Arrays.fill(dek, (byte) 0);
                    }
                });
    }

    @Override
    public KeyRotation rotate(Instant at) {
        Optional<StoredKey> active = activeKey();
        active.ifPresent(k -> update("""
                UPDATE customer_data_key
                   SET status = 'RETIRED', retired_at = :at
                 WHERE tenant_id = :tenantId
                   AND key_id = :keyId
                   AND status = 'ACTIVE'
                """, Map.of("keyId", k.keyId(), "at", Timestamp.from(at))));
        StoredKey created = createKey(at);
        return new KeyRotation(active.map(StoredKey::keyId), created.keyId());
    }

    @Override
    public int reencryptBatch(int limit) {
        TenantId tenant = TenantContext.current();
        StoredKey target = activeKey().orElseThrow(() -> new IllegalStateException(tenant + " has no ACTIVE customer data key"));
        List<StoredRow> rows = query("""
                SELECT customer_ref, name_enc, phone_enc, birth_date_enc, enc_key_id, created_at
                  FROM customer_ref
                 WHERE tenant_id = :tenantId
                   AND enc_key_id <> :keyId
                 ORDER BY customer_ref
                 LIMIT :limit
                   FOR UPDATE
                """, Map.of("keyId", target.keyId(), "limit", limit), (rs, n) -> row(rs));
        if (rows.isEmpty()) {
            return 0;
        }
        byte[] newKey = unwrap(tenant, target);
        Map<String, byte[]> oldKeys = new HashMap<>();
        try {
            for (StoredRow row : rows) {
                byte[] oldKey = oldKeys.computeIfAbsent(row.keyId(), id -> unwrap(tenant, key(id)));
                Customer plain = decrypt(oldKey, tenant, row);
                Map<String, Object> params = new HashMap<>();
                params.put("customerRef", row.ref().value());
                params.put("name", CustomerFieldCipher.encrypt(newKey, tenant, row.ref(), target.keyId(), plain.name()));
                params.put("phone", plain.phone().map(p -> CustomerFieldCipher.encrypt(newKey, tenant, row.ref(), target.keyId(), p)).orElse(null));
                params.put("birthDate",
                        plain.birthDate().map(b -> CustomerFieldCipher.encrypt(newKey, tenant, row.ref(), target.keyId(), b)).orElse(null));
                params.put("keyId", target.keyId());
                params.put("oldKeyId", row.keyId());
                update("""
                        UPDATE customer_ref
                           SET name_enc = :name, phone_enc = :phone, birth_date_enc = :birthDate, enc_key_id = :keyId
                         WHERE tenant_id = :tenantId
                           AND customer_ref = :customerRef
                           AND enc_key_id = :oldKeyId
                        """, params);
            }
            return rows.size();
        } finally {
            Arrays.fill(newKey, (byte) 0);
            oldKeys.values().forEach(k -> Arrays.fill(k, (byte) 0));
        }
    }

    @Override
    public List<String> destroyUnusedRetiredKeys(Instant at) {
        List<String> unused = query("""
                SELECT k.key_id
                  FROM customer_data_key k
                 WHERE k.tenant_id = :tenantId
                   AND k.status = 'RETIRED'
                   AND NOT EXISTS (SELECT 1 FROM customer_ref r WHERE r.tenant_id = :tenantId AND r.enc_key_id = k.key_id)
                 ORDER BY k.key_id
                """, Map.of(), (rs, n) -> rs.getString("key_id"));
        List<String> destroyed = new ArrayList<>();
        for (String keyId : unused) {
            update("""
                    UPDATE customer_data_key
                       SET status = 'DESTROYED', wrapped_key = NULL, destroyed_at = :at
                     WHERE tenant_id = :tenantId
                       AND key_id = :keyId
                       AND status = 'RETIRED'
                    """, Map.of("keyId", keyId, "at", Timestamp.from(at)));
            destroyed.add(keyId);
        }
        return destroyed;
    }

    // ------------------------------------------------------------------

    private Optional<StoredKey> activeKey() {
        return queryAtMostOne("""
                SELECT key_id, kek_id, wrapped_key, status
                  FROM customer_data_key
                 WHERE tenant_id = :tenantId
                   AND status = 'ACTIVE'
                """, Map.of(), (rs, n) -> new StoredKey(rs.getString("key_id"), rs.getString("kek_id"), rs.getBytes("wrapped_key"),
                rs.getString("status")));
    }

    private StoredKey key(String keyId) {
        return queryAtMostOne("""
                SELECT key_id, kek_id, wrapped_key, status
                  FROM customer_data_key
                 WHERE tenant_id = :tenantId
                   AND key_id = :keyId
                """, Map.of("keyId", keyId), (rs, n) -> new StoredKey(rs.getString("key_id"), rs.getString("kek_id"), rs.getBytes("wrapped_key"),
                rs.getString("status")))
                .orElseThrow(() -> new CiphertextRejectedException("customer data key " + keyId + " does not exist"));
    }

    private StoredKey createKey(Instant at) {
        TenantId tenant = TenantContext.current();
        String keyId = "DEK-" + UUID.randomUUID().toString().replace("-", "");
        String kekId = keys.currentKekId();
        byte[] dek = AesGcm.newKey();
        byte[] wrapped;
        try {
            wrapped = keys.wrap(tenant, keyId, kekId, dek);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
        // 테넌트 첫 등록이 동시에 들어오면 둘 다 ACTIVE 키가 없다고 본다. 진 쪽은 유일 인덱스에서 이긴 쪽의 커밋을 기다린 뒤
        // DO NOTHING(0행)이 되고, READ COMMITTED의 다음 문장이 커밋된 행을 읽는다. 진 쪽이 감싼 DEK는 저장되지 않고 버려진다.
        int inserted = update("""
                INSERT INTO customer_data_key (tenant_id, key_id, kek_id, wrapped_key, status, created_at)
                VALUES (:tenantId, :keyId, :kekId, :wrapped, 'ACTIVE', :at)
                ON CONFLICT (tenant_id) WHERE status = 'ACTIVE' DO NOTHING
                """, Map.of("keyId", keyId, "kekId", kekId, "wrapped", wrapped, "at", Timestamp.from(at)));
        if (inserted == 1) {
            return new StoredKey(keyId, kekId, wrapped, "ACTIVE");
        }
        return activeKey().orElseThrow(() -> new IllegalStateException(tenant + " lost the data key race but sees no ACTIVE key"));
    }

    private byte[] unwrap(TenantId tenant, StoredKey key) {
        if (key.wrapped() == null) {
            throw new CiphertextRejectedException("customer data key " + key.keyId() + " is " + key.status() + " (key material destroyed)");
        }
        return keys.unwrap(tenant, key.keyId(), key.kekId(), key.wrapped());
    }

    private static StoredRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredRow(CustomerRef.of(rs.getString("customer_ref")), rs.getBytes("name_enc"), rs.getBytes("phone_enc"),
                rs.getBytes("birth_date_enc"), rs.getString("enc_key_id"), rs.getTimestamp("created_at").toInstant());
    }

    private static Customer decrypt(byte[] dek, TenantId tenant, StoredRow row) {
        Sensitive<PhoneNumber> phone = row.phone() == null ? null
                : CustomerFieldCipher.decryptPhone(dek, tenant, row.ref(), row.keyId(), row.phone());
        Sensitive<BirthDate> birthDate = row.birthDate() == null ? null
                : CustomerFieldCipher.decryptBirthDate(dek, tenant, row.ref(), row.keyId(), row.birthDate());
        return new Customer(row.ref(), CustomerFieldCipher.decryptName(dek, tenant, row.ref(), row.keyId(), row.name()), phone, birthDate,
                row.keyId(), row.createdAt());
    }
}

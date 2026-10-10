package com.ga.disclosure.infra.testing;

import com.ga.disclosure.infra.crypto.TenantKeyProvider;
import com.ga.disclosure.infra.secret.FileSecretSource;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.customer.KeyProviderPort;
import com.ga.disclosure.workflow.kek.TenantKekStore;
import com.ga.disclosure.workflow.secret.SecretName;
import com.ga.disclosure.workflow.secret.SecretSource;
import com.ga.platform.core.tenant.TenantId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * 시험용 테넌트 KEK(Phase 8): 임시 비밀 디렉터리(소유자 전용)에 {@code kek/{T}/{T}-KEK-n}을 만든다. 시드 테넌트는 {@code {T}-KEK-1}이 CURRENT로
 * 등록된다({@link SeedData#tenant}) — 그 첫 키는 처음 읽힐 때 무작위로 생긴다. {@link #shared()}는 실행마다 하나, {@link #fresh()}는 같은 ID에 다른
 * 바이트(다른 KEK로는 풀리지 않는다는 시험). 키 바이트는 그 디렉터리 밖으로 나가지 않는다.
 */
public final class TestKeks {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final TestKeks SHARED = new TestKeks(createDir());

    private final Path dir;

    private TestKeks(Path dir) {
        this.dir = dir;
    }

    public static TestKeks shared() {
        return SHARED;
    }

    public static TestKeks fresh() {
        return new TestKeks(createDir());
    }

    public static String firstKekId(String tenant) {
        return tenant + "-KEK-1";
    }

    public Path dir() {
        return dir;
    }

    /** {@code kek/{T}/{kekId}}가 없으면 무작위 키로 만든다. */
    public synchronized void ensure(TenantId tenant, String kekId) {
        SecretName name = TenantKeyProvider.secretName(tenant, kekId).orElseThrow(() -> new IllegalArgumentException("not a KEK of " + tenant));
        if (!Files.exists(dir.resolve(name.value()))) {
            byte[] key = new byte[32];
            RANDOM.nextBytes(key);
            FileSecretSource.create(dir, name, Base64.getEncoder().encode(key));
        }
    }

    /** 테넌트 첫 KEK를 필요할 때 만드는 출처(그 밖의 이름은 있는 그대로). */
    public SecretSource secrets() {
        FileSecretSource files = new FileSecretSource(dir, false);
        return new SecretSource() {
            @Override
            public byte[] read(SecretName name) {
                provisionFirst(name);
                return files.read(name);
            }

            @Override
            public boolean exists(SecretName name) {
                provisionFirst(name);
                return files.exists(name);
            }
        };
    }

    private void provisionFirst(SecretName name) {
        String[] parts = name.value().split("/");
        if (parts.length == 3 && parts[0].equals("kek") && parts[2].equals(firstKekId(parts[1]))) {
            ensure(TenantId.of(parts[1]), parts[2]);
        }
    }

    /** DB 레지스트리를 쓰는 어댑터(앱과 같은 조립). */
    public KeyProviderPort provider(WorkflowTransactions tx, TenantKekStore registry) {
        return new TenantKeyProvider(secrets(), tenant -> tx.inTenant(tenant, registry::current), null);
    }

    /** DB 없는 시험: CURRENT = 언제나 {@code {T}-KEK-1}. */
    public KeyProviderPort standalone() {
        return new TenantKeyProvider(secrets(), tenant -> Optional.of(firstKekId(tenant.value())), null);
    }

    private static Path createDir() {
        try {
            Path dir = Files.createTempDirectory("ga-test-secrets");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

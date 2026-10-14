package com.ga.disclosure.app.cli;

import com.ga.disclosure.app.demo.DemoOidcIssuer;
import com.ga.disclosure.audit.tsa.stub.LocalStubTsa;
import com.ga.disclosure.infra.crypto.CursorCodec;
import com.ga.disclosure.infra.crypto.CustomerReceiptKey;
import com.ga.disclosure.infra.crypto.RequestHashKey;
import com.ga.disclosure.infra.secret.FileSecretSource;
import com.ga.disclosure.workflow.secret.SecretName;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 로컬·kind 비밀 디렉터리 준비(Phase 8, 8 계획 ④). 운영은 비밀 저장소가 만든다 — 앱은 비밀을 만들지 않고 읽기만 한다(없으면 기동 실패).
 * <pre>
 * secrets init --secrets-dir &lt;dir&gt; [--demo yes]   (없는 것만 만든다 — 있는 것은 그대로, 소유자 전용 600)
 * </pre>
 * 대상: 목록 커서 키 {@code api/cursor}, 멱등 요청 해시 키 {@code api/request-hash}, 고객 등록 영수증 키 {@code api/customer-receipt}, 백업 키
 * {@code backup/key}, (--demo yes) 데모 OIDC 서명 키 {@code demo/oidc-signing}·스텁 TSA 키 저장소 {@code demo/tsa-stub.p12}(kind 데모의 모든 파드가 같은
 * 키 — 앱은 저장소 파일을 소유자 전용으로 요구하므로 파드는 Secret 볼륨에서 600 사본을 만든다). 테넌트 KEK는 {@code crypto kek init}. 값은 출력하지 않는다.
 */
final class SecretCommands {

    static final SecretName BACKUP_KEY = SecretName.of("backup/key");
    static final SecretName TSA_STUB_KEY_STORE = SecretName.of("demo/tsa-stub.p12");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PrintStream out;
    private final Clock clock;

    SecretCommands(PrintStream out, Clock clock) {
        this.out = Objects.requireNonNull(out, "out");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void run(CliArguments args) {
        if (!args.command().equals("secrets init")) {
            throw new CliFailure("unknown command '" + args.command() + "' — see SecretCommands javadoc");
        }
        Path dir = Path.of(args.required("secrets-dir"));
        Map<SecretName, Supplier<byte[]>> wanted = new LinkedHashMap<>();
        wanted.put(CursorCodec.SECRET, SecretCommands::key32);
        wanted.put(RequestHashKey.SECRET, SecretCommands::key32);
        wanted.put(CustomerReceiptKey.SECRET, SecretCommands::key32);
        wanted.put(BACKUP_KEY, SecretCommands::key32);
        if (args.optional("demo").map("yes"::equals).orElse(false)) {
            wanted.put(DemoOidcIssuer.SECRET, DemoOidcIssuer::newSigningKeyPem);
            wanted.put(TSA_STUB_KEY_STORE, () -> LocalStubTsa.newDemoKeyStore(clock));
        }
        wanted.forEach((name, value) -> {
            if (Files.exists(dir.resolve(name.value()))) {
                out.println("SECRET " + name + " EXISTS");
                return;
            }
            byte[] bytes = value.get();
            try {
                FileSecretSource.create(dir, name, bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
            out.println("SECRET " + name + " CREATED");
        });
    }

    private static byte[] key32() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        try {
            return Base64.getEncoder().encode(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
}

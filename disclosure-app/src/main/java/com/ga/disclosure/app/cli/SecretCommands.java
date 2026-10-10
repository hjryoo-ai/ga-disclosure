package com.ga.disclosure.app.cli;

import com.ga.disclosure.app.demo.DemoOidcIssuer;
import com.ga.disclosure.infra.crypto.CursorCodec;
import com.ga.disclosure.infra.crypto.CustomerReceiptKey;
import com.ga.disclosure.infra.crypto.RequestHashKey;
import com.ga.disclosure.infra.secret.FileSecretSource;
import com.ga.disclosure.workflow.secret.SecretName;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
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
 * {@code backup/key}, (--demo yes) 데모 OIDC 서명 키 {@code demo/oidc-signing}. 테넌트 KEK는 {@code crypto kek init}. 값은 출력하지 않는다.
 */
final class SecretCommands {

    static final SecretName BACKUP_KEY = SecretName.of("backup/key");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PrintStream out;

    SecretCommands(PrintStream out) {
        this.out = Objects.requireNonNull(out, "out");
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

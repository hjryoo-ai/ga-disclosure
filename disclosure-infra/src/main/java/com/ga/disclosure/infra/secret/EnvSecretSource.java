package com.ga.disclosure.infra.secret;

import com.ga.disclosure.workflow.secret.SecretMissingException;
import com.ga.disclosure.workflow.secret.SecretName;
import com.ga.disclosure.workflow.secret.SecretSource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 환경변수 비밀 출처(개발 전용): 이름 {@code a/b-c.d} → 변수 {@code GA_SECRET_A_B_C_D}, 값은 UTF-8 바이트. 운영 프로파일은 키 재료를 이 출처에서
 * 읽으면 기동하지 않는다 — 환경변수는 프로세스 환경·컨테이너 설명·크래시 덤프로 새기 쉽다(8 계획 Q13).
 */
public final class EnvSecretSource implements SecretSource {

    private final Map<String, String> env;

    public EnvSecretSource(Map<String, String> env) {
        this.env = Map.copyOf(Objects.requireNonNull(env, "env"));
    }

    static String variable(SecretName name) {
        return "GA_SECRET_" + name.value().replaceAll("[/.-]", "_").toUpperCase(Locale.ROOT);
    }

    @Override
    public byte[] read(SecretName name) {
        String value = env.get(variable(name));
        if (value == null) {
            throw new SecretMissingException(name);
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean exists(SecretName name) {
        return env.containsKey(variable(name));
    }
}

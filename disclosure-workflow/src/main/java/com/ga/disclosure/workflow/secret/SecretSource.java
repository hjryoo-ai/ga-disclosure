package com.ga.disclosure.workflow.secret;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** 이름으로 비밀 바이트를 읽는다. 없으면 {@link SecretMissingException}(이름만 — 값·경로를 싣지 않는다). */
public interface SecretSource {

    /** 비밀의 원래 바이트(파일 내용 그대로). */
    byte[] read(SecretName name);

    boolean exists(SecretName name);

    /** base64 한 줄로 저장된 고정 길이 키(KEK·커서·요청 해시·영수증·백업 키의 규약). 앞뒤 공백은 지운다. 길이가 다르면 거부(이름만). */
    default byte[] key(SecretName name, int bytes) {
        byte[] raw = read(name);
        byte[] key;
        try {
            key = Base64.getDecoder().decode(new String(raw, StandardCharsets.US_ASCII).strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("secret " + name + " is not a base64 key");
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
        if (key.length != bytes) {
            Arrays.fill(key, (byte) 0);
            throw new IllegalStateException("secret " + name + " does not hold a " + bytes + "-byte key");
        }
        return key;
    }
}

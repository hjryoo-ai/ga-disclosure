package com.ga.disclosure.infra.crypto;

import com.ga.disclosure.workflow.secret.SecretName;
import com.ga.disclosure.workflow.secret.SecretSource;
import com.ga.disclosure.workflow.page.CursorPort;
import com.ga.disclosure.workflow.page.InvalidCursorException;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 서명된 목록 커서(6A 계획 §4.2): {@code base64url(JCS{v:1, s:stream, q:position}) "." base64url(HMAC-SHA256(key, tenant ‖ 0x00 ‖ 앞부분)[0..16])}.
 * 테넌트를 MAC에 묶어 다른 테넌트의 커서는 열리지 않는다. 비교는 상수 시간. 커서는 위치만 담는다(검색 조건·개인정보 없음).
 * <ul>
 *   <li>웹: 비밀 {@code api/cursor}(Phase 8 — {@link SecretSource}, base64 32바이트). 없거나 길이가 틀리면 기동 실패 — 만들지 않는다(생성은
 *       {@code secrets init}·비밀 저장소).</li>
 *   <li>CLI: 프로세스마다 새 키({@link #ephemeral()}) — CLI는 커서를 받지 않는다.</li>
 * </ul>
 */
public final class CursorCodec implements CursorPort {

    static final int KEY_BYTES = 32;
    static final int MAC_BYTES = 16;
    static final int MAX_CURSOR_CHARS = 512;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;

    CursorCodec(byte[] key) {
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("cursor key must be " + KEY_BYTES + " bytes");
        }
        this.key = key.clone();
    }

    public static CursorCodec ephemeral() {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return new CursorCodec(key);
    }

    public static final SecretName SECRET = SecretName.of("api/cursor");

    /** 비밀 출처에서 읽는다(없으면 실패). */
    public static CursorCodec fromSecret(SecretSource secrets) {
        return new CursorCodec(secrets.key(SECRET, KEY_BYTES));
    }

    @Override
    public String seal(TenantId tenant, String stream, String position) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(position, "position");
        String body = ENCODER.encodeToString(Canonicalizer.canonicalize(JSON.createObjectNode().put("v", 1).put("s", stream).put("q", position)));
        String cursor = body + "." + ENCODER.encodeToString(mac(tenant, body));
        if (cursor.length() > MAX_CURSOR_CHARS) {
            throw new IllegalArgumentException("cursor position is too long");
        }
        return cursor;
    }

    @Override
    public String open(TenantId tenant, String stream, String cursor) {
        Objects.requireNonNull(tenant, "tenant");
        if (cursor == null || cursor.length() > MAX_CURSOR_CHARS) {
            throw new InvalidCursorException();
        }
        int dot = cursor.indexOf('.');
        if (dot <= 0 || dot != cursor.lastIndexOf('.')) {
            throw new InvalidCursorException();
        }
        String body = cursor.substring(0, dot);
        byte[] given;
        byte[] payload;
        try {
            given = DECODER.decode(cursor.substring(dot + 1));
            payload = DECODER.decode(body);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException();
        }
        if (!MessageDigest.isEqual(given, mac(tenant, body))) {
            throw new InvalidCursorException();
        }
        JsonNode node;
        try {
            node = Canonicalizer.parseStrict(new String(payload, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new InvalidCursorException();
        }
        if (node.path("v").asInt(0) != 1 || !stream.equals(node.path("s").asString(null)) || !node.path("q").isString()) {
            throw new InvalidCursorException();
        }
        return node.get("q").asString();
    }

    private byte[] mac(TenantId tenant, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            ByteArrayOutputStream input = new ByteArrayOutputStream();
            input.writeBytes(tenant.value().getBytes(StandardCharsets.UTF_8));
            input.write(0);
            input.writeBytes(body.getBytes(StandardCharsets.US_ASCII));
            return Arrays.copyOf(mac.doFinal(input.toByteArray()), MAC_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}

package com.ga.disclosure.app.image;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 이미지 레이어 전수 스캔(Phase 8 ③·G3): {@code docker save}의 tar(OCI 레이아웃 — blob마다 레이어 tar, gzip이거나 아니거나)를 풀어 모든 레이어의 모든
 * 파일을 본다(JDK만 — 새 의존성 0).
 * <ul>
 *   <li>이름: 개인키·키 저장소 확장자({@code .key}·{@code .p12}·{@code .pfx}·{@code .jks}·{@code .keystore}).</li>
 *   <li>내용: PEM 개인키 블록(머리 + base64 본문 100자 이상 + 꼬리 — 코드 속 머리 문자열 리터럴은 본문이 없어 걸리지 않는다).</li>
 *   <li>내용: 주어진 바늘(로컬에서 만든 비밀 파일의 바이트·base64를 푼 바이트, 허구 개인정보 센티널).</li>
 * </ul>
 * 결과는 바늘 번호·레이어·경로만(값 0).
 */
final class LayerScanner {

    static final List<String> KEY_EXTENSIONS = List.of(".key", ".p12", ".pfx", ".jks", ".keystore");
    private static final Pattern PEM_PRIVATE_KEY = Pattern.compile(
            "-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----\\s*[A-Za-z0-9+/=\\r\\n]{100,}-----END (?:[A-Z]+ )*PRIVATE KEY-----");

    record Hit(String layer, String path, String why) {
    }

    record Result(int layers, long files, List<Hit> hits) {
    }

    private LayerScanner() {
    }

    static Result scan(Path savedImage, List<byte[]> needles) throws IOException {
        List<Hit> hits = new ArrayList<>();
        int[] layers = {0};
        long[] files = {0};
        try (InputStream outer = new BufferedInputStream(Files.newInputStream(savedImage))) {
            Tar.each(outer, (name, body) -> {
                if (!name.startsWith("blobs/")) {
                    return;
                }
                // 크기는 푼 뒤에 본다 — gzip 레이어는 512바이트보다 작을 수 있다(작은 COPY 한 줄 — 대조 시험이 처음에 이것을 놓쳤다)
                byte[] layer = gunzipIfNeeded(body);
                if (!Tar.looksLikeTar(layer)) {
                    return;                                                          // 매니페스트·설정 JSON
                }
                layers[0]++;
                Tar.each(new ByteArrayInputStream(layer), (path, content) -> {
                    files[0]++;
                    String lower = path.toLowerCase(java.util.Locale.ROOT);
                    for (String ext : KEY_EXTENSIONS) {
                        if (lower.endsWith(ext)) {
                            hits.add(new Hit(name, path, "key file name " + ext));
                        }
                    }
                    String text = new String(content, StandardCharsets.ISO_8859_1);
                    if (text.contains("PRIVATE KEY-----") && PEM_PRIVATE_KEY.matcher(text).find()) {
                        hits.add(new Hit(name, path, "PEM private key block"));
                    }
                    for (int i = 0; i < needles.size(); i++) {
                        if (indexOf(content, needles.get(i)) >= 0) {
                            hits.add(new Hit(name, path, "needle #" + i));
                        }
                    }
                });
            });
        }
        return new Result(layers[0], files[0], hits);
    }

    private static byte[] gunzipIfNeeded(byte[] body) throws IOException {
        if (body.length > 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return in.readAllBytes();
            }
        }
        return body;
    }

    static int indexOf(byte[] hay, byte[] needle) {
        if (needle.length == 0) {
            return -1;
        }
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** 최소 tar 읽기: ustar 머리(512바이트), GNU 긴 이름(L), PAX 경로(x). 정규 파일만 본문을 넘긴다. */
    static final class Tar {

        interface Entry {
            void accept(String name, byte[] body) throws IOException;
        }

        static boolean looksLikeTar(byte[] b) {
            return b.length >= 512 && new String(b, 257, 5, StandardCharsets.US_ASCII).equals("ustar");
        }

        static void each(InputStream in, Entry entry) throws IOException {
            String longName = null;
            while (true) {
                byte[] header = in.readNBytes(512);
                if (header.length < 512 || isZero(header)) {
                    return;
                }
                String name = cString(header, 0, 100);
                String prefix = cString(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                long size = octal(header, 124, 12);
                char type = (char) header[156];
                byte[] body = in.readNBytes((int) size);
                long pad = (512 - size % 512) % 512;
                in.skipNBytes(pad);
                switch (type) {
                    case 'L' -> longName = cString(body, 0, body.length);
                    case 'x' -> longName = paxPath(body);
                    case 'g' -> {
                    }
                    case '0', '\0', '7' -> {
                        entry.accept(longName != null ? longName : name, body);
                        longName = null;
                    }
                    default -> longName = null;
                }
            }
        }

        private static String paxPath(byte[] body) {
            for (String line : new String(body, StandardCharsets.UTF_8).split("\n")) {
                int eq = line.indexOf(" path=");
                if (eq > 0) {
                    return line.substring(eq + 6);
                }
            }
            return null;
        }

        private static boolean isZero(byte[] b) {
            for (byte x : b) {
                if (x != 0) {
                    return false;
                }
            }
            return true;
        }

        private static String cString(byte[] b, int off, int len) {
            int end = off;
            while (end < off + len && end < b.length && b[end] != 0) {
                end++;
            }
            return new String(b, off, end - off, StandardCharsets.UTF_8);
        }

        private static long octal(byte[] b, int off, int len) {
            if ((b[off] & 0x80) != 0) {                                               // base-256(큰 파일)
                long v = 0;
                for (int i = off + 1; i < off + len; i++) {
                    v = (v << 8) | (b[i] & 0xff);
                }
                return v;
            }
            String s = cString(b, off, len).trim();
            return s.isEmpty() ? 0 : Long.parseLong(s, 8);
        }
    }
}

package com.ga.disclosure.api.security;

import com.ga.disclosure.api.error.Problem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 쓰기(POST) 요청의 멱등 재료를 잡는다(6A 계획 §4.2) — 원 헤더 읽기는 {@code api.security}만 한다(ApiLayerRulesTest (e)):
 * <ul>
 *   <li>{@code Idempotency-Key} 헤더와 본문 바이트를 요청 속성에 둔다(본문은 메모리에 한 번 읽어 컨트롤러에 다시 준다 — 요청 해시가 본문을 덮는다).</li>
 *   <li>응답을 버퍼에 받는다 — 인터셉터가 완료 때 응답 바이트의 해시와 영수증 튜플을 기록한다. 체인이 끝나면 버퍼를 실제 응답으로 보낸다.</li>
 * </ul>
 * 상한은 종이 스캔(원본 20 MiB, base64로 약 27 MiB)을 담는 크기다. 본문이 {@link #MAX_BODY_BYTES}를 넘으면 400 {@code MALFORMED_REQUEST}({@code field: body}).
 */
public final class IdempotencyCaptureFilter extends OncePerRequestFilter {

    public static final String KEY_ATTRIBUTE = IdempotencyCaptureFilter.class.getName() + ".key";
    public static final String BODY_ATTRIBUTE = IdempotencyCaptureFilter.class.getName() + ".body";
    public static final String HEADER = "Idempotency-Key";
    static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            byte[] problem = Problem.body("MALFORMED_REQUEST", tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode()
                    .put("field", "body"));
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setContentLength(problem.length);
            response.getOutputStream().write(problem);
            return;
        }
        String key = request.getHeader(HEADER);
        if (key != null) {
            request.setAttribute(KEY_ATTRIBUTE, key);
        }
        request.setAttribute(BODY_ATTRIBUTE, body);
        ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(new BufferedBodyRequest(request, body), buffered);
        } finally {
            buffered.copyBodyToResponse();
        }
    }

    /** 미리 읽은 본문을 다시 내주는 요청. */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("synchronous body only");
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}

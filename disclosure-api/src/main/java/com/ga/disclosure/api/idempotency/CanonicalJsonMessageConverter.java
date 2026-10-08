package com.ga.disclosure.api.idempotency;

import com.ga.platform.canonical.Canonicalizer;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Objects;

/**
 * JSON 응답을 JCS 바이트로 쓴다(쓰기 전용). 멱등 재생은 저장된 영수증 튜플에서 바이트를 다시 만들어 응답 해시와 대조하므로(승인 Q4), 처음 응답도 같은
 * 정규형이어야 한다 — 오류 본문({@code Problem})과 같은 규약. 직렬화는 애플리케이션 매퍼(개인정보 값객체 거부 모듈 포함)로 한다. {@code byte[]}·문자열은
 * 다루지 않는다(보고서·산출물 바이트는 그대로).
 */
public final class CanonicalJsonMessageConverter extends AbstractHttpMessageConverter<Object> {

    private final JsonMapper mapper;

    public CanonicalJsonMessageConverter(JsonMapper mapper) {
        super(MediaType.APPLICATION_JSON);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return clazz != byte[].class && !CharSequence.class.isAssignableFrom(clazz)
                && !org.springframework.core.io.Resource.class.isAssignableFrom(clazz);
    }

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return false;
    }

    @Override
    protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage) {
        throw new HttpMessageNotReadableException("write-only converter", inputMessage);
    }

    @Override
    protected void writeInternal(Object value, HttpOutputMessage outputMessage) throws IOException {
        outputMessage.getBody().write(Canonicalizer.canonicalize(mapper.valueToTree(value)));
    }
}

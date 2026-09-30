package com.ga.disclosure.infra.json;

import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.domain.pii.SensitiveValue;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * 개인정보 값객체({@link Sensitive}, {@link SensitiveValue})를 JSON으로 쓰려 하면 실패시키는 Jackson 모듈(Phase 2 P5).
 * 앱의 매퍼에 등록된다 — API 응답·로그 구조화 출력으로 원문이 새는 경로를 막는다. 기본 매퍼도 게터가 없는 {@code Sensitive}에서
 * 빈 빈 오류로 실패하지만, 이 모듈은 설정({@code FAIL_ON_EMPTY_BEANS})과 무관하게 명시적으로 거부한다.
 */
public final class SensitiveGuardModule extends SimpleModule {

    public SensitiveGuardModule() {
        super("ga-sensitive-guard");
        addSerializer(Sensitive.class, new Refuse<>());
        addSerializer(SensitiveValue.class, new Refuse<>());
    }

    private static final class Refuse<T> extends ValueSerializer<T> {
        @Override
        public void serialize(T value, JsonGenerator gen, SerializationContext ctxt) {
            ctxt.reportMappingProblem("personal data (%s) must not be serialized; map it to a masked view explicitly",
                    value.getClass().getSimpleName());
        }
    }
}

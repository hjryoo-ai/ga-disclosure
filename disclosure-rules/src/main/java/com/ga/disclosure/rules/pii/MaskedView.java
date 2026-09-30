package com.ga.disclosure.rules.pii;

import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.domain.pii.SensitiveValue;
import com.ga.disclosure.rules.resolve.EffectiveRule;

/**
 * 화면·문서 표시용 부분 마스킹(설계서 §9). 규칙은 기준일에 해석된 룰의 {@code masking}(규제 번들 기본값 + 사규 덮어쓰기,
 * TODO(confirm#12))이며 코드에 자릿수를 두지 않는다. 결과 문자열만 밖으로 나간다.
 */
public final class MaskedView {

    private MaskedView() {
    }

    public static String of(Sensitive<? extends SensitiveValue> value, EffectiveRule rule) {
        return value.reveal(v -> rule.masking(v.field()).apply(v.canonical()));
    }
}

package com.ga.disclosure.seal.renderer;

import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.seal.canonical.CanonicalDocument;
import com.ga.disclosure.seal.canonical.CanonicalDocumentBuilder;
import com.ga.disclosure.seal.canonical.CanonicalInput;
import com.ga.disclosure.seal.canonical.SealFixtures;

/** 렌더 시험 재료: 정본 서식 STANDARD v1 번들 + 봉인 본문 시험 재료. */
public final class RenderFixtures {

    public static final TemplateResolution STANDARD =
            TemplateResolver.resolution(Bundles.template(Bundles.template(Bundles.STANDARD_V1), null));

    private RenderFixtures() {
    }

    public static CanonicalDocument canonical(CanonicalInput in) {
        return CanonicalDocumentBuilder.build(in, SealFixtures.NAME);
    }

    public static String number(int seq) {
        return "DEMO1-2026-" + String.valueOf(1_000_000 + seq).substring(1);
    }
}

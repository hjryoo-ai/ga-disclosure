package com.ga.disclosure.seal.golden;

import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignatureMethod;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.seal.renderer.DisclosurePdfRenderer;
import com.ga.disclosure.seal.renderer.SignatureAppearance;
import com.ga.disclosure.seal.renderer.SignedPdfAppender;
import com.ga.platform.canonical.Canonicalizer;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 서명본 골든 1건(4 계획 §3, G7): 바탕 골든 사례의 봉인 PDF(커밋된 입력에서 렌더) + {@code signatures.json}(서명 요약·이미지 파일).
 * 기대값은 {@code expected.properties}의 {@code signedPdfSha256}.
 */
record SignedGolden(GoldenCase files) {

    static final List<String> NAMES = List.of("signed-01");

    static SignedGolden of(String name) {
        return new SignedGolden(GoldenCase.of(name));
    }

    JsonNode spec() {
        return Canonicalizer.parseStrict(new String(files.read("signatures.json"), StandardCharsets.UTF_8));
    }

    GoldenCase base() {
        return GoldenCase.of(spec().path("base").asString());
    }

    byte[] originalPdf() {
        GoldenCase b = base();
        return new DisclosurePdfRenderer().render(b.canonical(), b.template(), b.disclosureNo()).pdf();
    }

    List<SignatureAppearance> signatures() {
        List<SignatureAppearance> out = new ArrayList<>();
        for (JsonNode s : spec().path("signatures")) {
            List<SignatureAppearance.IdentityResult> identity = new ArrayList<>();
            s.path("identity").forEach(r -> identity.add(new SignatureAppearance.IdentityResult(IdentityMethod.valueOf(r.path("method").asString()),
                    r.path("passed").asBoolean())));
            JsonNode image = s.path("image");
            out.add(new SignatureAppearance(SignerRole.valueOf(s.path("role").asString()), SignatureChannel.valueOf(s.path("channel").asString()),
                    SignatureMethod.valueOf(s.path("method").asString()), Instant.parse(s.path("signedAt").asString()), identity,
                    image.isString() ? files.read(image.asString()) : null));
        }
        return out;
    }

    DisclosurePdfRenderer.Rendered sign(byte[] original, List<SignatureAppearance> signatures) {
        GoldenCase b = base();
        return new SignedPdfAppender().append(original, b.canonical(), b.template(), b.disclosureNo(), signatures);
    }

    DisclosurePdfRenderer.Rendered sign() {
        return sign(originalPdf(), signatures());
    }

    Map<String, String> expected() {
        return files.expected();
    }
}

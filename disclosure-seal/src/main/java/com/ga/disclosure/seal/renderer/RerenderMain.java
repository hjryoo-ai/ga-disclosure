package com.ga.disclosure.seal.renderer;

import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.vo.TemplateRef;
import com.ga.disclosure.rules.template.FormTemplate;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.seal.canonical.CanonicalDocument;
import com.ga.platform.canonical.Canonicalizer;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * 재렌더 진입점(별도 프로세스, 3B S2 — Phase 5 {@code verify}의 재렌더 검증도 같은 경로): 저장된 봉인 본문(JCS 바이트)·고정 서식 본문·확인서 번호로
 * PDF를 다시 만든다. 입력은 파일뿐이고 시계·환경을 읽지 않는다.
 * <pre>RerenderMain canonical.json template.json {templateType} {templateId} {templateVersion} {disclosureNo} out.pdf</pre>
 */
public final class RerenderMain {

    private RerenderMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 7) {
            throw new IllegalArgumentException("usage: RerenderMain canonical.json template.json templateType templateId templateVersion disclosureNo out.pdf");
        }
        CanonicalDocument canonical = CanonicalDocument.parse(Files.readAllBytes(Path.of(args[0])));
        JsonNode body = Canonicalizer.parseStrict(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
        FormTemplate template = new FormTemplate(TemplateRef.of(args[3], Integer.parseInt(args[4])), TemplateType.valueOf(args[2]),
                LocalDate.of(2000, 1, 1), null, body.get("fields"), body.get("layout"), body.get("pendingConfirmation"), null, null);
        byte[] pdf = new DisclosurePdfRenderer().render(canonical, TemplateResolver.resolution(template), args[5]).pdf();
        Files.write(Path.of(args[6]), pdf);
    }
}

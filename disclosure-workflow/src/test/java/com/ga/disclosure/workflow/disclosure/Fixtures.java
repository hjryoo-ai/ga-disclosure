package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.disclosure.FieldValue;
import com.ga.disclosure.domain.disclosure.ItemDraft;
import com.ga.disclosure.domain.enums.IssuerMode;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.grade.EngineSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.domain.grade.RatioLabel;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.domain.enums.InsuranceLine;
import com.ga.disclosure.workflow.catalog.PanelEntry;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.SnapshotId;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.Bind;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.rules.validation.ValidationRegistry;
import com.ga.disclosure.rules.validation.standard.StandardValidations;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** 애그리게이트 단위 테스트 조립: DISC-2026-07 + STANDARD v1, 전 보험사 패널, 규칙 레지스트리를 그대로 쓰는 단계 검증. */
final class Fixtures {

    static final LocalDate CONSULT = LocalDate.of(2026, 9, 23);
    static final GroupCode GROUP = GroupCode.of("PG-HEALTH-SIMPLE-NR");
    static final EffectiveRule RULE = RuleResolver.merge(CONSULT,
            Bundles.global(Bundles.rule(Bundles.DISC_2026_07), RuleStatus.ACTIVE, null), null);
    static final TemplateResolution TEMPLATE =
            TemplateResolver.resolution(Bundles.template(Bundles.template(Bundles.STANDARD_V1), null));
    static final ValidationRegistry REGISTRY = StandardValidations.registry();
    static final StageCheck CHECK = (stage, subject) -> REGISTRY.run(stage, subject, RULE, TEMPLATE);
    static final DisclosureContext CONTEXT = new DisclosureContext(true, (insurer, date) -> true, TEMPLATE, "건강(간편)",
            panel("INS-A", "INS-B", "INS-C", "INS-D", "INS-E"), labels(), RULE.signDeadlineDays());

    private Fixtures() {
    }

    static Disclosure draft() {
        return Disclosure.draft(DisclosureId.of(UUID.randomUUID()), "AGENT-1", CustomerRef.of("CR-" + "0".repeat(31) + "1"), GROUP,
                CONSULT, RULE.globalRuleVersionId(), null, TEMPLATE.ref(), IssuerMode.SELF, CONTEXT);
    }

    /** 서식의 카탈로그 기본값 결속 항목 전부에 값(코드는 서식 데이터에서 읽는다 — 카탈로그 defaults가 같은 코드로 준 값에 해당). */
    static Map<String, FieldValue> catalogValues() {
        Map<String, FieldValue> values = new LinkedHashMap<>();
        TEMPLATE.fields().stream().filter(f -> f.bind() == Bind.CATALOG_DEFAULT)
                .forEach(f -> values.put(f.code(), new FieldValue("\"값\"", FieldValue.Origin.CATALOG)));
        return values;
    }

    /** 상담일 패널(이름 = "보험사 " + 코드). */
    static List<PanelEntry> panel(String... insurers) {
        List<PanelEntry> out = new ArrayList<>();
        for (String i : insurers) {
            out.add(new PanelEntry(InsurerCode.of(i), "보험사 " + i, InsuranceLine.LIFE, LocalDate.of(2026, 1, 1), null));
        }
        return out;
    }

    static Map<ReasonCode, String> labels() {
        Map<ReasonCode, String> out = new LinkedHashMap<>();
        RULE.reasonCodes().forEach(r -> out.put(r.code(), r.label()));
        return out;
    }

    static ItemDraft catalog(String key, boolean recommended) {
        return ItemDraft.catalog(ProductKey.parse(key), GROUP, "상품 " + key, recommended, false, catalogValues());
    }

    static ItemDraft requested(String key) {
        return ItemDraft.catalog(ProductKey.parse(key), GROUP, "상품 " + key, false, true, catalogValues());
    }

    static ItemDraft temp(String insurer, String quote, boolean recommended) {
        Map<String, FieldValue> values = new LinkedHashMap<>();
        catalogValues().forEach((code, v) -> values.put(code, new FieldValue(v.canonicalJson(), FieldValue.Origin.AGENT)));
        return ItemDraft.temp(InsurerCode.of(insurer), GROUP, "임시 " + quote, quote, recommended, false, values);
    }

    /** A·B·C 세 보험사, A·C 추천. */
    static List<ItemDraft> threeItems() {
        return List.of(catalog("INS-A:PRD-1001", true), catalog("INS-B:PRD-2044", false), catalog("INS-C:PRD-3120", true));
    }

    /** 요청 상품 전부 OK, 순위는 주어진 순서대로 1..m(STRICT), 서수는 순위와 같은 방향. */
    static EngineSnapshot snapshotFor(EngineRequest request) {
        List<GradeSnapshotItem> items = new ArrayList<>();
        int rank = 1;
        for (ProductKey key : request.products()) {
            items.add(GradeSnapshotItem.ok(key, "G" + rank, "등급" + rank, rank, rank, false, new RatioLabel("0." + (80 + rank))));
            rank++;
        }
        return engine(new GradeSnapshot(SnapshotId.of("GRD-20260923-000001"), "GRADING-2026-07", "RANK-2026-07", TieBreak.STRICT, items));
    }

    static EngineSnapshot engine(GradeSnapshot snapshot) {
        return new EngineSnapshot(snapshot, "{\"groupAvgSource\":\"ASSOC_DISCLOSURE\",\"groupPopulation\":27,\"period\":\"2026Q2\"}",
                Instant.parse("2026-09-23T01:15:30Z"));
    }

    /**
     * 정합한 무작위 스냅샷: 일부는 UNAVAILABLE, OK 항목은 무작위 정수 키로 경쟁 순위(SHARED_RANK) 또는 순열(STRICT)을 받고 서수는 순위에
     * 단조. 테스트 코드라 정렬을 쓴다(운영 코드의 정렬 금지 규칙은 테스트 소스셋을 보지 않는다).
     */
    static EngineSnapshot randomSnapshot(EngineRequest request, RandomGenerator r) {
        TieBreak tieBreak = r.nextBoolean() ? TieBreak.SHARED_RANK : TieBreak.STRICT;
        List<ProductKey> ok = new ArrayList<>();
        List<GradeSnapshotItem> out = new ArrayList<>();
        for (ProductKey k : request.products()) {
            if (r.nextInt(5) == 0) {
                out.add(GradeSnapshotItem.unavailable(k, r.nextBoolean() ? "NO_RATE_DATA" : "NOT_IN_GROUP"));
            } else {
                ok.add(k);
            }
        }
        Map<ProductKey, Integer> keyOf = new HashMap<>();
        ok.forEach(k -> keyOf.put(k, tieBreak == TieBreak.STRICT ? r.nextInt(1_000_000) : r.nextInt(3)));
        List<ProductKey> sorted = new ArrayList<>(ok);
        sorted.sort(Comparator.comparing((ProductKey k) -> keyOf.get(k)).thenComparing(ProductKey::value));
        int ordinal = 1;
        int rank = 0;
        for (int i = 0; i < sorted.size(); i++) {
            ProductKey k = sorted.get(i);
            boolean sameAsPrev = i > 0 && tieBreak == TieBreak.SHARED_RANK && keyOf.get(sorted.get(i - 1)).equals(keyOf.get(k));
            boolean sameAsNext = i + 1 < sorted.size() && tieBreak == TieBreak.SHARED_RANK && keyOf.get(sorted.get(i + 1)).equals(keyOf.get(k));
            if (!sameAsPrev) {
                rank = i + 1;
                if (i > 0) {
                    ordinal += r.nextInt(2);
                }
            }
            out.add(GradeSnapshotItem.ok(k, "G" + ordinal, "등급" + ordinal, ordinal, rank, sameAsPrev || sameAsNext, new RatioLabel("r" + ordinal)));
        }
        return engine(new GradeSnapshot(SnapshotId.of("GRD-20260923-" + String.format("%06d", r.nextInt(1, 999_999))),
                "GRADING-2026-07", "RANK-2026-07", tieBreak, out));
    }
}

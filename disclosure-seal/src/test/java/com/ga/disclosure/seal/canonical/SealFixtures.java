package com.ga.disclosure.seal.canonical;

import com.ga.disclosure.domain.disclosure.FieldValue;
import com.ga.disclosure.domain.disclosure.ItemGrade;
import com.ga.disclosure.domain.enums.IssuerMode;
import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.grade.EngineSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.domain.grade.RatioLabel;
import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.domain.vo.SnapshotId;
import com.ga.disclosure.domain.vo.TemplateRef;
import com.ga.platform.core.tenant.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 봉인 본문 시험 재료(전부 허구 데이터). 골든 사례 01(3사 정상)·02(임시등록 + 엔진 산출불가 + 긴 한글 사유)·03(50항목)의 입력이기도 하다 —
 * 값을 바꾸면 골든 기대값이 바뀐다.
 */
public final class SealFixtures {

    public static final TenantId TENANT = TenantId.of("DEMO1");
    public static final LocalDate CONSULT = LocalDate.of(2026, 9, 23);
    public static final Sensitive<CustomerName> NAME = CustomerName.of("가상고객");

    private SealFixtures() {
    }

    public static CanonicalInput case01() {
        List<CanonicalInput.Item> items = List.of(
                catalog(1, "INS-A:PRD-1001", true, ok("LOW", "낮음", 2, 1, "0.84"), reasons("PREMIUM", "보험료 수준"), null),
                catalog(2, "INS-B:PRD-2044", false, ok("VERY_HIGH", "매우높음", 5, 3, "1.37"), List.of(), null),
                catalog(3, "INS-C:PRD-3120", true, ok("MID", "보통", 3, 2, "1.02"), reasons("COVERAGE", "보장 범위"), null));
        return input("00000000-0000-4000-8000-000000000001", items);
    }

    public static CanonicalInput case02() {
        Map<String, FieldValue> tempValues = new LinkedHashMap<>();
        tempValues.put("PREMIUM", new FieldValue("30100", FieldValue.Origin.AGENT));
        tempValues.put("SURRENDER_VALUE_EXAMPLE", new FieldValue("\"가입설계서 Q-2026-0001 참조\"", FieldValue.Origin.AGENT));
        String longText = "고객이 기존 계약의 보장 공백을 우려하여 입원·수술 보장을 우선했고, 보험료 납입 여력과 갱신 구조를 함께 비교한 결과 "
                + "이 상품이 고객의 요청 조건에 가장 가깝다고 판단함. 해약환급금이 적다는 점과 무해지 구조의 의미를 설명하였고 고객이 이해했음을 확인함.";
        List<CanonicalInput.Item> items = List.of(
                catalog(1, "INS-A:PRD-1001", true, ok("LOW", "낮음", 2, 1, "0.84"), reasons("PREMIUM", "보험료 수준"), null),
                new CanonicalInput.Item(2, null, InsurerCode.of("INS-B"), "(가상) 다라생명 신상품", GroupCode.of("PG-HEALTH-SIMPLE-NR"), true,
                        "Q-2026-0001", true, false, tempValues, ItemGrade.Unavailable.localTempProduct(),
                        List.of(new CanonicalInput.Reason("OTHER", "기타")), longText),
                catalog(3, "INS-C:PRD-3120", false, ItemGrade.Unavailable.engine("NO_RATE_DATA"), List.of(), null));
        return input("00000000-0000-4000-8000-000000000002", items);
    }

    public static CanonicalInput case03() {
        List<CanonicalInput.Item> items = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            String insurer = "INS-" + (char) ('A' + (i - 1) % 5);
            boolean recommended = i % 7 == 1;
            items.add(catalog(i, insurer + ":PRD-" + (1000 + i), recommended, ok("G" + (i % 5 + 1), "등급" + (i % 5 + 1), i % 5 + 1, i, "r" + i),
                    recommended ? reasons("PREMIUM", "보험료 수준") : List.of(), null));
        }
        return input("00000000-0000-4000-8000-000000000003", items);
    }

    public static CanonicalInput input(String disclosureId, List<CanonicalInput.Item> items) {
        List<GradeSnapshotItem> engine = new ArrayList<>();
        for (CanonicalInput.Item i : items) {
            if (i.productKeyOrNull() != null) {
                engine.add(switch (i.grade()) {
                    case ItemGrade.Ok ok -> GradeSnapshotItem.ok(i.productKeyOrNull(), ok.gradeCode(), ok.gradeLabel(), ok.gradeOrdinal(),
                            ok.rankInSet(), ok.tie(), ok.ratioToAvg());
                    case ItemGrade.Unavailable u -> GradeSnapshotItem.unavailable(i.productKeyOrNull(), u.reason());
                });
            }
        }
        EngineSnapshot snapshot = new EngineSnapshot(new GradeSnapshot(SnapshotId.of("GRD-0000001"), "GRADING-2026-07", "RANK-2026-07",
                TieBreak.SHARED_RANK, engine), "{\"groupAvgSource\":\"ASSOC_DISCLOSURE\",\"groupPopulation\":27,\"period\":\"2026Q2\"}",
                Instant.parse("2026-09-23T00:30:00Z"));
        List<CanonicalInput.PanelInsurer> panel = List.of(
                new CanonicalInput.PanelInsurer(InsurerCode.of("INS-A"), "(가상) 가나생명"),
                new CanonicalInput.PanelInsurer(InsurerCode.of("INS-B"), "(가상) 다라생명"),
                new CanonicalInput.PanelInsurer(InsurerCode.of("INS-C"), "(가상) 마바생명"),
                new CanonicalInput.PanelInsurer(InsurerCode.of("INS-D"), "(가상) 사아손보"),
                new CanonicalInput.PanelInsurer(InsurerCode.of("INS-E"), "(가상) 자차손보"));
        return new CanonicalInput(TENANT, DisclosureId.of(UUID.fromString(disclosureId)), 1, null, "agent-1@demo",
                CustomerRef.of("CR-" + "0".repeat(31) + "7"), CONSULT, GroupCode.of("PG-HEALTH-SIMPLE-NR"), "(가상) 간편건강 무해지", IssuerMode.SELF,
                RuleVersionId.of("DISC-2026-07"), null, TemplateRef.of("STANDARD", 1), snapshot, panel, items);
    }

    public static CanonicalInput.Item catalog(int no, String key, boolean recommended, ItemGrade grade, List<CanonicalInput.Reason> reasons,
                                              String text) {
        ProductKey k = ProductKey.parse(key);
        Map<String, FieldValue> values = new LinkedHashMap<>();
        values.put("PREMIUM", new FieldValue(String.valueOf(32100 + no), FieldValue.Origin.CATALOG));
        values.put("SURRENDER_VALUE_EXAMPLE", new FieldValue("[{\"refundWon\":0,\"year\":10}]", FieldValue.Origin.CATALOG));
        return new CanonicalInput.Item(no, k, k.insurer(), "(가상) " + key, GroupCode.of("PG-HEALTH-SIMPLE-NR"), false, null, recommended, false,
                values, grade, reasons, text);
    }

    public static ItemGrade ok(String code, String label, int ordinal, int rank, String ratio) {
        return new ItemGrade.Ok(code, label, ordinal, rank, false, new RatioLabel(ratio));
    }

    public static List<CanonicalInput.Reason> reasons(String code, String label) {
        return List.of(new CanonicalInput.Reason(code, label));
    }
}

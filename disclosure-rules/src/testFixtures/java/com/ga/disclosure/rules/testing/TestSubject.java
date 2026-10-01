package com.ga.disclosure.rules.testing;

import com.ga.disclosure.domain.disclosure.ItemGrade;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.rules.template.BindingView;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트 픽스처: 검증 대상 확인서 단면. 패널 판정은 함수로 주입한다 — 보험사 코드 집합(단위 테스트) 또는
 * {@code InsurerPanelPort}를 테넌트에 묶은 함수(통합 테스트, Phase 2). 애그리게이트의 구현은 Phase 3.
 */
public record TestSubject(
        GroupCode groupCode,
        LocalDate consultDate,
        boolean largeGa,
        List<ValidationSubject.Item> items,
        Optional<GradeSnapshot> gradeSnapshot,
        Optional<String> productGroupName,
        List<SignatureMark> signatures,
        Optional<Instant> signDeadline,
        BiPredicate<InsurerCode, LocalDate> panel) implements ValidationSubject {

    public TestSubject {
        items = List.copyOf(items);
        signatures = List.copyOf(signatures);
        Objects.requireNonNull(panel, "panel");
    }

    public static TestSubject of(String group, LocalDate consultDate, List<? extends ValidationSubject.Item> items) {
        Set<InsurerCode> panel = new HashSet<>();
        items.forEach(i -> panel.add(i.insurerCode()));
        return new TestSubject(GroupCode.of(group), consultDate, true, List.copyOf(items), Optional.empty(), Optional.of("상품군 " + group), List.of(),
                Optional.empty(), onPanel(panel));
    }

    @Override
    public boolean isInsurerOnPanel(InsurerCode insurer, LocalDate date) {
        return panel.test(insurer, date);
    }

    /**
     * 결속 단면: 번호·성명은 봉인 전이라 없고(발급 예정), 설계사는 {@code AGENT-T}, 패널 이름 = 항목 보험사 중 패널에 있는 것의 코드,
     * 항목 등급 = 스냅샷에서 상품키로 찾은 결과(임시등록은 스냅샷이 있으면 로컬 산출불가 — 애그리게이트와 같은 규칙), 사유 라벨 = 코드.
     */
    @Override
    public BindingView bindings() {
        List<BindingView.Item> views = new ArrayList<>();
        for (ValidationSubject.Item i : items) {
            views.add(new ItemBindings((TestItem) i, grade(i), isInsurerOnPanel(i.insurerCode(), consultDate)));
        }
        List<String> panelNames = items.stream().map(ValidationSubject.Item::insurerCode).distinct()
                .filter(c -> isInsurerOnPanel(c, consultDate)).map(InsurerCode::value).sorted().toList();
        return new DocumentBindings(consultDate, productGroupName, panelNames, List.copyOf(views));
    }

    private Optional<ItemGrade> grade(ValidationSubject.Item item) {
        if (gradeSnapshot.isEmpty()) {
            return Optional.empty();
        }
        if (item.tempProduct()) {
            return Optional.of(ItemGrade.Unavailable.localTempProduct());
        }
        return gradeSnapshot.get().items().stream().filter(r -> item.productKey().map(r.productKey()::equals).orElse(false)).findFirst()
                .map(r -> r.isAvailable()
                        ? new ItemGrade.Ok(r.gradeCode(), r.gradeLabel(), r.gradeOrdinal(), r.rankInSet(), r.tie(), r.ratioToAvg())
                        : ItemGrade.Unavailable.engine(r.unavailableReason()));
    }

    private record DocumentBindings(LocalDate consultDate, Optional<String> productGroupName, List<String> panelInsurerNames,
                                    List<BindingView.Item> items) implements BindingView {
        @Override
        public Optional<String> disclosureNo() {
            return Optional.empty();
        }

        @Override
        public String agentId() {
            return "AGENT-T";
        }

        @Override
        public Optional<String> customerName() {
            return Optional.empty();
        }
    }

    private record ItemBindings(TestItem item, Optional<ItemGrade> grade, boolean onPanel) implements BindingView.Item {
        @Override
        public Optional<String> insurerName() {
            return onPanel ? Optional.of(item.insurerCode().value()) : Optional.empty();
        }

        @Override
        public String productName() {
            return item.productName();
        }

        @Override
        public Optional<JsonNode> fieldValue(String code) {
            return Optional.ofNullable(item.fieldValues().get(code)).map(v -> JSON.getNodeFactory().stringNode(v));
        }

        @Override
        public boolean enteredByAgent(String code) {
            return item.agentCodes().contains(code) && item.fieldValues().containsKey(code);
        }

        @Override
        public boolean recommended() {
            return item.isRecommended();
        }

        @Override
        public List<String> reasonLabels() {
            return item.reasonCodes().stream().map(ReasonCode::value).toList();
        }

        @Override
        public Optional<String> reasonText() {
            return item.reasonText();
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 날짜와 무관한 고정 패널. */
    public static BiPredicate<InsurerCode, LocalDate> onPanel(Set<InsurerCode> insurers) {
        Set<InsurerCode> copy = Set.copyOf(insurers);
        return (insurer, date) -> copy.contains(insurer);
    }

    public TestSubject withItems(List<? extends ValidationSubject.Item> newItems) {
        return new TestSubject(groupCode, consultDate, largeGa, List.copyOf(newItems), gradeSnapshot, productGroupName, signatures,
                signDeadline, panel);
    }

    public TestSubject withLargeGa(boolean value) {
        return new TestSubject(groupCode, consultDate, value, items, gradeSnapshot, productGroupName, signatures, signDeadline, panel);
    }

    public TestSubject withSnapshot(GradeSnapshot snapshot) {
        return new TestSubject(groupCode, consultDate, largeGa, items, Optional.ofNullable(snapshot), productGroupName, signatures,
                signDeadline, panel);
    }

    /** 상담일 카탈로그에 상품군이 없는 확인서(HEADER_PRODUCT_GROUP 결속이 비어 있다). */
    public TestSubject withoutProductGroupName() {
        return new TestSubject(groupCode, consultDate, largeGa, items, gradeSnapshot, Optional.empty(), signatures, signDeadline, panel);
    }

    public TestSubject withPanel(Set<InsurerCode> newPanel) {
        return withPanel(onPanel(newPanel));
    }

    public TestSubject withPanel(BiPredicate<InsurerCode, LocalDate> lookup) {
        return new TestSubject(groupCode, consultDate, largeGa, items, gradeSnapshot, productGroupName, signatures, signDeadline,
                lookup);
    }

    public TestSubject withDeadline(Instant deadline) {
        return new TestSubject(groupCode, consultDate, largeGa, items, gradeSnapshot, productGroupName, signatures,
                Optional.ofNullable(deadline), panel);
    }

    public TestSubject withSignatures(List<SignatureMark> marks) {
        return new TestSubject(groupCode, consultDate, largeGa, items, gradeSnapshot, productGroupName, marks, signDeadline, panel);
    }

    public TestSubject signedBy(SignerRole role, Instant at) {
        List<SignatureMark> marks = new ArrayList<>(signatures);
        marks.add(new SignatureMark(role, at));
        return new TestSubject(groupCode, consultDate, largeGa, items, gradeSnapshot, productGroupName, marks, signDeadline, panel);
    }
}

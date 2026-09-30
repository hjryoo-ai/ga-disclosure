package com.ga.disclosure.rules.testing;

import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

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
        Map<String, String> documentFieldValues,
        List<SignatureMark> signatures,
        Optional<Instant> signDeadline,
        BiPredicate<InsurerCode, LocalDate> panel) implements ValidationSubject {

    public TestSubject {
        items = List.copyOf(items);
        documentFieldValues = Map.copyOf(documentFieldValues);
        signatures = List.copyOf(signatures);
        Objects.requireNonNull(panel, "panel");
    }

    public static TestSubject of(String group, LocalDate consultDate, List<? extends ValidationSubject.Item> items) {
        Set<InsurerCode> panel = new HashSet<>();
        items.forEach(i -> panel.add(i.insurerCode()));
        return new TestSubject(GroupCode.of(group), consultDate, true, List.copyOf(items), Optional.empty(), Map.of(), List.of(),
                Optional.empty(), onPanel(panel));
    }

    @Override
    public boolean isInsurerOnPanel(InsurerCode insurer, LocalDate date) {
        return panel.test(insurer, date);
    }

    /** 날짜와 무관한 고정 패널. */
    public static BiPredicate<InsurerCode, LocalDate> onPanel(Set<InsurerCode> insurers) {
        Set<InsurerCode> copy = Set.copyOf(insurers);
        return (insurer, date) -> copy.contains(insurer);
    }

    public TestSubject withItems(List<? extends ValidationSubject.Item> newItems) {
        return new TestSubject(groupCode, consultDate, largeGa, List.copyOf(newItems), gradeSnapshot, documentFieldValues, signatures,
                signDeadline, panel);
    }

    public TestSubject withLargeGa(boolean value) {
        return new TestSubject(groupCode, consultDate, value, items, gradeSnapshot, documentFieldValues, signatures, signDeadline, panel);
    }

    public TestSubject withSnapshot(GradeSnapshot snapshot) {
        return new TestSubject(groupCode, consultDate, largeGa, items, Optional.ofNullable(snapshot), documentFieldValues, signatures,
                signDeadline, panel);
    }

    public TestSubject withDocumentField(String code, String value) {
        Map<String, String> values = new HashMap<>(documentFieldValues);
        values.put(code, value);
        return new TestSubject(groupCode, consultDate, largeGa, items, gradeSnapshot, values, signatures, signDeadline, panel);
    }

    public TestSubject withPanel(Set<InsurerCode> newPanel) {
        return withPanel(onPanel(newPanel));
    }

    public TestSubject withPanel(BiPredicate<InsurerCode, LocalDate> lookup) {
        return new TestSubject(groupCode, consultDate, largeGa, items, gradeSnapshot, documentFieldValues, signatures, signDeadline,
                lookup);
    }

    public TestSubject withDeadline(Instant deadline) {
        return new TestSubject(groupCode, consultDate, largeGa, items, gradeSnapshot, documentFieldValues, signatures,
                Optional.ofNullable(deadline), panel);
    }

    public TestSubject withSignatures(List<SignatureMark> marks) {
        return new TestSubject(groupCode, consultDate, largeGa, items, gradeSnapshot, documentFieldValues, marks, signDeadline, panel);
    }

    public TestSubject signedBy(SignerRole role, Instant at) {
        List<SignatureMark> marks = new ArrayList<>(signatures);
        marks.add(new SignatureMark(role, at));
        return new TestSubject(groupCode, consultDate, largeGa, items, gradeSnapshot, documentFieldValues, marks, signDeadline, panel);
    }
}

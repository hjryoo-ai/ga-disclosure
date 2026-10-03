package com.ga.disclosure.audit.verify;

import com.ga.disclosure.audit.verify.VerifyReport.Check;
import com.ga.disclosure.audit.verify.VerifyReport.Finding;
import com.ga.disclosure.audit.verify.VerifyReport.Status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 검사 실행 기록: 검사마다 통과·실패·건너뜀과 센 항목 수, 발견, 문장. 검사 순서는 실행 순서다. */
public final class ReportBuilder {

    private final List<Check> checks = new ArrayList<>();
    private final List<Finding> findings = new ArrayList<>();
    private final List<String> statements = new ArrayList<>();

    /** 검사 하나를 연다. 그 뒤의 {@link #finding}이 이 검사의 실패로 센다. */
    public CheckScope check(String name) {
        return new CheckScope(name, findings.size());
    }

    public void skipped(String name) {
        checks.add(new Check(name, Status.SKIPPED, 0));
    }

    public void finding(FindingCode code, Map<String, Object> where, Map<String, Object> detail) {
        findings.add(new Finding(code, where, detail));
    }

    public void statement(String text) {
        statements.add(text);
    }

    public boolean clean() {
        return findings.isEmpty();
    }

    public List<Check> checks() {
        return List.copyOf(checks);
    }

    public List<Finding> findings() {
        return List.copyOf(findings);
    }

    public List<String> statements() {
        return List.copyOf(statements);
    }

    /** 키·값 쌍으로 순서 있는 맵(null 값 허용). */
    public static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    public final class CheckScope {
        private final String name;
        private final int findingsBefore;
        private int count;

        private CheckScope(String name, int findingsBefore) {
            this.name = name;
            this.findingsBefore = findingsBefore;
        }

        public CheckScope counted(int n) {
            count += n;
            return this;
        }

        /** 검사를 닫는다. 연 뒤로 발견이 생겼으면 FAIL. */
        public boolean close() {
            boolean pass = findings.size() == findingsBefore;
            checks.add(new Check(name, pass ? Status.PASS : Status.FAIL, count));
            return pass;
        }
    }
}

package com.ga.disclosure.rules.metric;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.rules.resolve.CollectionRateFormula;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 징구율 순수 산식(6B 계획 §5·승인 §3, 설계서 §6.8). <b>내부 지표 — 규제 정의 없음</b>(§14 #17, TODO(confirm#17)) — 이름을 "규제 징구율"로 쓰지 않는다.
 * 입력은 기준월에 계약일이 있는 <b>활성</b> 계약 연결(정정·이전으로 닫힌 행은 입력이 아니다 — 저장소가 고른다)과, 안 B만 같은 달 미매칭 증권 키다.
 * <ul>
 *   <li>공통: 연결 확인서가 파기됐거나 {@code ABANDONED}면 분모·분자 양쪽에서 뺀다. 묶음은 연결 확인서의 작성 시점 조직별 + 테넌트 전체({@value #TENANT_WIDE}).</li>
 *   <li>{@code LINKED_COMPLETED_BY_CONTRACT_DATE}(안 A): 분모 = 남은 연결, 분자 = 그중 확인서가 COMPLETED이고 완료일(KST) ≤ 계약일.</li>
 *   <li>{@code TARGET_INCLUDING_UNMATCHED}(안 B): 테넌트 전체 행의 분모에 미매칭 증권 키(같은 키는 한 번)를 더한다. 조직 행은 안 A와 같다.</li>
 * </ul>
 * 비율은 만분율 정수 {@code numerator × 10000 / denominator}(버림), 분모 0이면 없음 — DB CHECK가 같은 식을 다시 본다. {@code inputsHash}는 묶음마다
 * SHA-256(JCS({inputs: 입력 확인서 번호 정렬, numerator: 분자 확인서 번호 정렬[, unmatched: 미매칭 키 정렬]})) — 같은 입력이면 같은 값(재현).
 * Spring·DB 무의존(모듈 규칙). 이 계산은 수수료율과 무관하다.
 */
public final class CollectionRates {

    /** 응답·보고서·감사에 함께 싣는 정의 표기(승인 §3). */
    public static final String DEFINITION = "INTERNAL_METRIC_NO_REGULATORY_DEFINITION";
    public static final String DEFINITION_TEXT = "내부 지표 — 규제 정의 없음";
    public static final String TENANT_WIDE = "/";
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CollectionRates() {
    }

    /**
     * 활성 연결 하나와 그 확인서의 사실. 번호는 봉인 때 생기므로 셀 수 있는 연결(파기·폐기 아님)에는 늘 있다 — 없으면 저장소 상태가 모순이다.
     */
    public record Linked(Optional<String> disclosureNo, String orgPath, LocalDate contractDate, DisclosureStatus status, Optional<Instant> completedAt,
                         boolean destroyed) {
        public Linked {
            Objects.requireNonNull(disclosureNo, "disclosureNo");
            Objects.requireNonNull(orgPath, "orgPath");
            Objects.requireNonNull(contractDate, "contractDate");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(completedAt, "completedAt");
            if (orgPath.equals(TENANT_WIDE)) {
                throw new IllegalArgumentException("a disclosure's org path is never the tenant-wide group");
            }
        }
    }

    /** 묶음 하나의 수치. */
    public record Group(String orgPath, int denominator, int numerator, OptionalInt rateBp, String inputsHash) {
        public Group {
            Objects.requireNonNull(orgPath, "orgPath");
            Objects.requireNonNull(rateBp, "rateBp");
            Objects.requireNonNull(inputsHash, "inputsHash");
        }
    }

    /** 한 달의 결과: 테넌트 전체 행이 먼저, 그 뒤 조직 경로 순. 연결이 없어도 테넌트 전체 행(0/0)은 있다. */
    public record Result(CollectionRateFormula formula, YearMonth period, List<Group> groups) {
        public Result {
            Objects.requireNonNull(formula, "formula");
            Objects.requireNonNull(period, "period");
            groups = List.copyOf(groups);
        }

        public Group tenantWide() {
            return groups.getFirst();
        }
    }

    public static Result compute(CollectionRateFormula formula, YearMonth period, List<Linked> links, Collection<String> unmatchedPolicyKeys) {
        Objects.requireNonNull(formula, "formula");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(links, "links");
        Objects.requireNonNull(unmatchedPolicyKeys, "unmatchedPolicyKeys");
        Tally all = new Tally();
        Map<String, Tally> byOrg = new TreeMap<>();
        for (Linked l : links) {
            if (!counted(l, period)) {
                continue;
            }
            String no = l.disclosureNo().orElseThrow(() -> new IllegalArgumentException("a counted link's disclosure has no number"));
            boolean collected = collected(l);
            all.add(no, collected);
            byOrg.computeIfAbsent(l.orgPath(), k -> new Tally()).add(no, collected);
        }
        TreeSet<String> unmatched = formula == CollectionRateFormula.TARGET_INCLUDING_UNMATCHED ? new TreeSet<>(unmatchedPolicyKeys) : new TreeSet<>();
        List<Group> groups = new ArrayList<>();
        groups.add(all.group(TENANT_WIDE, formula == CollectionRateFormula.TARGET_INCLUDING_UNMATCHED ? Optional.of(unmatched) : Optional.empty()));
        byOrg.forEach((org, t) -> groups.add(t.group(org, Optional.empty())));
        return new Result(formula, period, groups);
    }

    /** 분모에 드는가: 계약일이 기준월 안이고, 확인서가 파기·폐기되지 않았다. */
    public static boolean counted(Linked l, YearMonth period) {
        return YearMonth.from(l.contractDate()).equals(period) && !l.destroyed() && l.status() != DisclosureStatus.ABANDONED;
    }

    /** 분자에 드는가: 확인서가 COMPLETED이고 완료일(KST)이 계약일과 같거나 앞선다. */
    public static boolean collected(Linked l) {
        return l.status() == DisclosureStatus.COMPLETED && l.completedAt().map(at -> !LocalDate.ofInstant(at, SEOUL).isAfter(l.contractDate())).orElse(false);
    }

    /** 만분율(버림), 분모 0이면 없음. */
    public static OptionalInt rateBp(int numerator, int denominator) {
        if (numerator < 0 || denominator < 0 || numerator > denominator) {
            throw new IllegalArgumentException("0 <= numerator <= denominator");
        }
        return denominator == 0 ? OptionalInt.empty() : OptionalInt.of(Math.toIntExact(Math.multiplyExact((long) numerator, 10_000L) / denominator));
    }

    private static final class Tally {
        private final TreeSet<String> inputs = new TreeSet<>();
        private final TreeSet<String> numerator = new TreeSet<>();

        void add(String no, boolean collected) {
            if (!inputs.add(no)) {
                throw new IllegalArgumentException("a disclosure holds at most one active link");
            }
            if (collected) {
                numerator.add(no);
            }
        }

        Group group(String org, Optional<TreeSet<String>> unmatched) {
            ObjectNode hashed = JSON.createObjectNode();
            ArrayNode in = hashed.putArray("inputs");
            inputs.forEach(in::add);
            ArrayNode num = hashed.putArray("numerator");
            numerator.forEach(num::add);
            unmatched.ifPresent(u -> {
                ArrayNode un = hashed.putArray("unmatched");
                u.forEach(un::add);
            });
            int denominator = Math.addExact(inputs.size(), unmatched.map(TreeSet::size).orElse(0));
            return new Group(org, denominator, numerator.size(), rateBp(numerator.size(), denominator), Sha256.of(Canonicalizer.canonicalize(hashed)));
        }
    }
}

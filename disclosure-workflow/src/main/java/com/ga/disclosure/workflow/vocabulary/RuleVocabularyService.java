package com.ga.disclosure.workflow.vocabulary;

import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.LifecycleReasonRule;
import com.ga.disclosure.rules.resolve.ResolutionFailure;
import com.ga.disclosure.rules.resolve.RuleResolutionException;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.onboarding.RulesNotInForceException;
import com.ga.platform.canonical.Sha256;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * 룰 어휘(행위 {@code RULE_VOCABULARY_READ} — 설계사·관리자·준법 TENANT, 대상 없음, 감사 없음 — 룰 데이터 읽기, 개인정보 0). 오늘(KST) 시행 중인
 * 룰(GLOBAL + 사규 병합)에서 <b>닫힌 목록의 코드와 표기만</b>, 룰 순서 그대로:
 * <ul>
 *   <li>추천사유 코드 — 시스템 부가 코드({@code auto})는 뺀다(설계사 선택지가 아니다, 규칙 7). 텍스트 필수 여부는 주지 않는다.</li>
 *   <li>무효·정정·보류 설정·보류 해제·초안 폐기 사유.</li>
 *   <li>플래그 유형과 유형별 수동 해소 코드(빈 목록 = 수동 해소 없음). 담당·가시성·기한·근거 요구는 주지 않는다. 유형은 코드 순 — 룰 본문에서
 *       유형은 객체의 키라 순서가 없다(JSONB는 키 순서를 보존하지 않는다). 목록(배열)은 룰 순서 그대로.</li>
 *   <li>켜진 고객 서명 채널(계약 enum 순), 서명자 집합(룰 순서).</li>
 * </ul>
 * 수·불리언·문구 틀(한도·임계치·기한·보존·마스킹·워터마크)은 하나도 없다 — 응답 모양 자체에 그런 칸이 없다. 무효·정정 판정은 그 문서의 <b>고정 룰</b>이
 * 하므로, 오늘 룰과 다른 룰에 고정된 문서는 이 목록 밖 코드가 맞을 수도 있다 — 화면은 서버 거부를 그대로 보인다(화면 판단 0).
 * 오늘 시행 중인 GLOBAL 룰이 없으면 {@link RulesNotInForceException}(쓰기의 503과 같은 응답).
 */
public final class RuleVocabularyService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final RuleResolver rules;
    private final WorkflowTransactions transactions;
    private final AuthorizationPort authz;
    private final Clock clock;

    public RuleVocabularyService(RuleResolver rules, WorkflowTransactions transactions, AuthorizationPort authz, Clock clock) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 코드와 표기. */
    public record Entry(String code, String label) {
        public Entry {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(label, "label");
        }
    }

    /** 플래그 유형 하나와 그 수동 해소 코드. */
    public record FlagType(String type, List<Entry> resolutionCodes) {
        public FlagType {
            Objects.requireNonNull(type, "type");
            resolutionCodes = List.copyOf(resolutionCodes);
        }
    }

    /**
     * @param version 응답 판(ETag 재료) — 오늘 날짜와 병합 룰 본문 해시에서 만든다. 같은 날 같은 룰이면 같다.
     */
    public record Vocabulary(LocalDate asOf, String version, List<Entry> reasonCodes, List<Entry> voidReasons, List<Entry> supersedeReasons,
                             List<Entry> legalHoldReasons, List<Entry> legalHoldReleaseReasons, List<Entry> draftAbandonReasons,
                             List<FlagType> flagTypes, List<SignatureChannel> channels, List<SignerRole> signerRoles) {
        public Vocabulary {
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(version, "version");
            reasonCodes = List.copyOf(reasonCodes);
            voidReasons = List.copyOf(voidReasons);
            supersedeReasons = List.copyOf(supersedeReasons);
            legalHoldReasons = List.copyOf(legalHoldReasons);
            legalHoldReleaseReasons = List.copyOf(legalHoldReleaseReasons);
            draftAbandonReasons = List.copyOf(draftAbandonReasons);
            flagTypes = List.copyOf(flagTypes);
            channels = List.copyOf(channels);
            signerRoles = List.copyOf(signerRoles);
        }
    }

    @UseCaseEntry(Action.RULE_VOCABULARY_READ)
    public Vocabulary read(Caller caller) {
        Objects.requireNonNull(caller, "caller");
        return transactions.inTenant(caller.tenant(), () -> {
            authz.require(caller, Action.RULE_VOCABULARY_READ, Target.none());
            LocalDate today = clock.instant().atZone(SEOUL).toLocalDate();
            EffectiveRule rule;
            try {
                rule = rules.resolve(caller.tenant(), today);
            } catch (RuleResolutionException e) {
                if (e.failure() == ResolutionFailure.NO_GLOBAL_RULE) {
                    throw new RulesNotInForceException();
                }
                throw e;
            }
            String version = Sha256.of(("rule-vocabulary/1|" + today + "|" + rule.bodyHash()).getBytes(StandardCharsets.UTF_8)).substring(0, 32);
            return new Vocabulary(today, version,
                    rule.reasonCodes().stream().filter(r -> !r.auto()).map(r -> new Entry(r.code().value(), r.label())).toList(),
                    entries(rule.voidReasons()), entries(rule.supersedeReasons()), entries(rule.legalHoldReasons()),
                    entries(rule.legalHoldReleaseReasons()), entries(rule.draftAbandonReasons()),
                    rule.complianceQueue().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                            .map(e -> new FlagType(e.getKey(), entries(e.getValue().resolutionCodes()))).toList(),
                    rule.channels().entrySet().stream().filter(e -> e.getValue().enabled()).map(java.util.Map.Entry::getKey).toList(),
                    rule.signerSet());
        });
    }

    private static List<Entry> entries(List<LifecycleReasonRule> rules) {
        return rules.stream().map(r -> new Entry(r.code(), r.label())).toList();
    }
}

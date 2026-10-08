package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.outbox.EventType;
import com.ga.disclosure.audit.outbox.OutboxPayloads;
import com.ga.disclosure.audit.outbox.OutboxPort;
import com.ga.disclosure.domain.disclosure.DisclosureCommand;
import com.ga.disclosure.domain.disclosure.DisclosureStateTable;
import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.ValidationStage;
import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.domain.vo.ChainHash;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.DisclosureNo;
import com.ga.disclosure.domain.vo.Sha256;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.seal.canonical.CanonicalDocument;
import com.ga.disclosure.seal.canonical.CanonicalDocumentBuilder;
import com.ga.disclosure.seal.canonical.CanonicalInput;
import com.ga.disclosure.seal.renderer.DisclosurePdfRenderer;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.artifact.ArtifactRecord;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.customer.CustomerVault;
import com.ga.disclosure.workflow.disclosure.DisclosureLoader.Loaded;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 봉인 유스케이스(설계서 §6.4, 3A 수용심사 §3-2~§3-7, 3B 계획 §3).
 *
 * <p><b>조건(전부 업무 거부, 단락 없이 전부 평가해 한 번에 반환, 싼 것부터)</b>: ① {@code RULE_SUPERSEDED} 상담일 재해석 룰 ≠ 고정 룰 ②
 * {@code TEMPLATE_SUPERSEDED} 상담일 재해석 서식 ≠ 고정 서식(①·②면 {@code RULE_SUPERSEDED_DRAFT} 플래그) ③ {@code SNAPSHOT_STALE} 스냅샷 산출 뒤
 * {@code snapshotMaxAgeDays} 경과 ④ {@code VALIDATION_BLOCKED} SEAL 단계 오버라이드 불가 실패 ⑤ {@code APPROVAL_MISSING} 고정 룰 버전에 귀속된 승인이
 * 없는 오버라이드 가능 실패 ⑥ {@code CUSTOMER_NAME_UNAVAILABLE} 성명을 풀 수 없음(키 상태만 보고 복호화하지 않는다). 거부는 상태·번호·카운터·체인·
 * 산출물·저장소를 바꾸지 않고 감사 {@code DISCLOSURE_SEAL_REJECTED} 1행(①·②면 플래그도 올리고 그 ID를 이 행에 싣는다)만 남긴다 — 조건 평가가 채번보다 앞이라 번호를 쓰지 않는다.
 *
 * <p><b>성공 경로(한 쓰기 트랜잭션, 잠금 순서 확인서 → 카운터 → 체인 머리)</b>: 성명 복호화(감사 {@code CUSTOMER_VIEW}, 사유 SEAL) → 봉인 본문·해시 →
 * 채번(봉인일 Asia/Seoul 연도) → 렌더 → 문서 키·암호화 → 업로드(잠금 없음) → 체인 → 봉인 컬럼 저장 → 키·산출물 기록 → 체인 머리 이동 → 감사
 * {@code DISCLOSURE_SEAL} → 오버라이드 플래그 해소(APPROVED·RESOLVED_AT_SEAL) → 커밋. 커밋 <b>후</b> 산출물마다 Object Lock을 건다(보존기한 =
 * 봉인일 + 보존기간({@code retentionYears}년 + {@code retentionDays}일)의 당일 끝, Asia/Seoul) — 실패하면 {@code retention_applied_at}이 NULL로 남아 재적용 대상이 된다.
 *
 * <p>예외(룰 해석 실패·복호화 실패·스키마 위반·저장소 오류)는 명령 오류다: 롤백 + {@code COMMAND_FAILED}. 업로드 뒤 롤백되면 저장소에 잠금 없는
 * 잔여물이 남고 잔여물 정리({@code ArtifactMaintenance#gc})가 치운다.
 */
public final class SealService {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ZERO_CHAIN = com.ga.disclosure.audit.chain.SealChain.ZERO;
    static final String CUSTOMER_TARGET = "CUSTOMER_REF";
    static final String ARTIFACT_TARGET = "DOCUMENT_ARTIFACT";

    /** 봉인 거부 코드(닫힌 어휘, 평가 순서). */
    public enum Rejection {
        RULE_SUPERSEDED,
        TEMPLATE_SUPERSEDED,
        SNAPSHOT_STALE,
        VALIDATION_BLOCKED,
        APPROVAL_MISSING,
        CUSTOMER_NAME_UNAVAILABLE
    }

    /**
     * 봉인 결과. 거부면 {@code rejections}가 비어 있지 않고 번호가 없다.
     *
     * @param retentionPending 커밋 후 Object Lock 적용 중 실패한 산출물이 있다(재적용 대상)
     */
    public record Outcome(DisclosureId id, DisclosureStatus status, List<Rejection> rejections, List<ValidationResult> results,
                          Optional<DisclosureNo> number, boolean retentionPending) {
        public Outcome {
            rejections = List.copyOf(rejections);
            results = List.copyOf(results);
        }

        public boolean sealed() {
            return rejections.isEmpty();
        }
    }

    private final DisclosureStore store;
    private final ReviewStore reviews;
    private final DisclosureFlagPort flags;
    private final CustomerVault customers;
    private final RuleResolver rules;
    private final TemplateResolver templates;
    private final SealLedgerPort ledger;
    private final DocumentCryptoPort crypto;
    private final DocumentRecordStore records;
    private final ArtifactStore storage;
    private final DisclosurePdfRenderer renderer;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;
    private final OutboxPort outbox;
    private final CommandRunner runner;
    private final DisclosureLoader loader;

    /** 봉인 트랜잭션 제한 시간 기본값(잔여물 정리 유예는 이보다 커야 한다). */
    public static final Duration DEFAULT_TRANSACTION_TIMEOUT = Duration.ofSeconds(60);

    private final Duration transactionTimeout;
    private final RetentionLocks locks;
    private final AuthorizationPort authz;

    public SealService(DisclosureServiceDeps deps, SealLedgerPort ledger, DocumentCryptoPort crypto, DocumentRecordStore records,
                       ArtifactStore storage, DisclosurePdfRenderer renderer) {
        this(deps, ledger, crypto, records, storage, renderer, DEFAULT_TRANSACTION_TIMEOUT);
    }

    public SealService(DisclosureServiceDeps deps, SealLedgerPort ledger, DocumentCryptoPort crypto, DocumentRecordStore records,
                       ArtifactStore storage, DisclosurePdfRenderer renderer, Duration transactionTimeout) {
        this.transactionTimeout = Objects.requireNonNull(transactionTimeout, "transactionTimeout");
        this.store = deps.store();
        this.reviews = deps.reviews();
        this.flags = deps.flags();
        this.outbox = deps.outbox();
        this.customers = deps.customers();
        this.rules = deps.rules();
        this.templates = deps.templates();
        this.audit = deps.audit();
        this.transactions = deps.transactions();
        this.clock = deps.clock();
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.records = Objects.requireNonNull(records, "records");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.runner = new CommandRunner(transactions, audit, clock);
        this.loader = deps.loader();
        this.authz = deps.authz();
        this.locks = new RetentionLocks(records, storage, audit, transactions, clock);
    }

    public Duration transactionTimeout() {
        return transactionTimeout;
    }

    private record Committed(Outcome outcome, List<ArtifactRecord> artifacts, LocalDate retentionUntil, Actor actor) {
    }

    @UseCaseEntry(Action.SEAL)
    public Outcome seal(Caller caller, DisclosureId id) {
        TenantId tenant = caller.tenant();
        Committed committed = runner.inTransaction(caller, DisclosureCommand.SEAL.name(), id.toString(), transactionTimeout,
                attempt -> sealInTransaction(tenant, attempt.granted(authz.require(caller, Action.SEAL, Target.disclosure(id))), id));
        if (!committed.outcome().sealed()) {
            return committed.outcome();
        }
        boolean pending = applyRetention(tenant, committed.actor(), committed.artifacts(), committed.retentionUntil());
        Outcome o = committed.outcome();
        return new Outcome(o.id(), o.status(), o.rejections(), o.results(), o.number(), pending);
    }

    private Committed sealInTransaction(TenantId tenant, Actor actor, DisclosureId id) {
        Loaded l = loader.load(tenant, id);
        Disclosure d = l.disclosure();
        DisclosureStateTable.require(d.status(), DisclosureCommand.SEAL);
        Instant now = clock.instant();

        // ---------------------------------------------------------------- 조건 ①~⑥(전부 평가)
        List<Rejection> rejections = new ArrayList<>();
        EffectiveRule current = rules.resolve(tenant, d.consultDate());
        boolean ruleSuperseded = !current.globalRuleVersionId().equals(d.ruleVersionId())
                || !current.tenantRuleVersion().equals(d.tenantRuleVersionId());
        if (ruleSuperseded) {
            rejections.add(Rejection.RULE_SUPERSEDED);
        }
        TemplateResolution currentTemplate = templates.resolve(tenant, l.template().templateType(), d.consultDate());
        if (!currentTemplate.ref().equals(d.template())) {
            rejections.add(Rejection.TEMPLATE_SUPERSEDED);
        }
        Instant generatedAt = d.engineSnapshot().orElseThrow().generatedAt();
        if (Duration.between(generatedAt, now).compareTo(Duration.ofDays(l.rule().snapshotMaxAgeDays())) > 0) {
            rejections.add(Rejection.SNAPSHOT_STALE);
        }
        List<ValidationResult> results = l.check().run(ValidationStage.SEAL, d);
        List<Review> approvals = reviews.findFor(id);
        List<ValidationResult> unapproved = SealGate.unapproved(results, approvals, d.ruleVersionId(), d.tenantRuleVersionId());
        if (unapproved.stream().anyMatch(r -> !r.overridable())) {
            rejections.add(Rejection.VALIDATION_BLOCKED);
        }
        if (unapproved.stream().anyMatch(ValidationResult::overridable)) {
            rejections.add(Rejection.APPROVAL_MISSING);
        }
        if (!customers.nameReadable(d.customerRef())) {
            rejections.add(Rejection.CUSTOMER_NAME_UNAVAILABLE);
        }
        if (!rejections.isEmpty()) {
            ObjectNode detail = identity(l).put("status", d.status().name());
            if (rejections.contains(Rejection.RULE_SUPERSEDED) || rejections.contains(Rejection.TEMPLATE_SUPERSEDED)) {
                // 플래그는 올리되 감사는 거부 1행에 싣는다(S6 "거부 시 감사 1행" — 별도 FLAG_RAISE 행 없음)
                DisclosureFlagPort.RaisedFlag flag = flags.raise(DisclosureFlagPort.Type.RULE_SUPERSEDED_DRAFT, "HIGH", id, CommandRunner.TARGET,
                        id.toString(), now);
                detail.putObject("flag").put("flagId", flag.flagId().toString()).put("type", DisclosureFlagPort.Type.RULE_SUPERSEDED_DRAFT.name())
                        .put("created", flag.created());
            }
            ArrayNode codes = detail.putArray("rejections");
            rejections.forEach(r -> codes.add(r.name()));
            detail.put("currentRuleVersionId", current.globalRuleVersionId().value())
                    .put("currentTemplateVersion", currentTemplate.ref().version());
            current.tenantRuleVersion().ifPresentOrElse(v -> detail.put("currentTenantRuleVersionId", v.value()),
                    () -> detail.putNull("currentTenantRuleVersionId"));
            results(detail, results, unapproved);
            record(actor, AuditAction.DISCLOSURE_SEAL_REJECTED, id, detail);
            return new Committed(new Outcome(id, d.status(), rejections, results, Optional.empty(), false), List.of(), null, actor);
        }

        // ---------------------------------------------------------------- 성공 경로
        Sensitive<CustomerName> name = customers.name(d.customerRef())
                .orElseThrow(() -> new IllegalStateException("customer of " + id + " vanished inside the sealing transaction"));
        audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.CUSTOMER_VIEW, CUSTOMER_TARGET, d.customerRef().value(),
                JSON.createObjectNode().put("reason", "SEAL").put("disclosureId", id.toString())));
        CanonicalDocument canonical = CanonicalDocumentBuilder.build(canonicalInput(tenant, l), name);
        LocalDate sealDate = now.atZone(SEOUL).toLocalDate();
        DisclosureNo number = new DisclosureNo(tenant, sealDate.getYear(), ledger.issueNumber(sealDate.getYear()));
        DisclosurePdfRenderer.Rendered pdf = renderer.render(canonical, l.template(), number.value());

        Map<ArtifactKind, byte[]> plaintexts = new EnumMap<>(ArtifactKind.class);
        plaintexts.put(ArtifactKind.CANONICAL_JSON, canonical.bytes());
        plaintexts.put(ArtifactKind.PDF, pdf.pdf());
        DocumentCryptoPort.Sealed sealed = crypto.seal(tenant, id, plaintexts);
        List<ArtifactRecord> artifacts = new ArrayList<>();
        for (Map.Entry<ArtifactKind, byte[]> e : plaintexts.entrySet()) {
            byte[] cipher = sealed.ciphertext(e.getKey());
            Sha256 cipherHash = Sha256.of(com.ga.platform.canonical.Sha256.of(cipher));
            String key = ArtifactRecord.storageKey(tenant.value(), id, e.getKey(), cipherHash);
            storage.put(key, cipher);
            artifacts.add(new ArtifactRecord(id, e.getKey(), key, Sha256.of(com.ga.platform.canonical.Sha256.of(e.getValue())), e.getValue().length, cipherHash,
                    cipher.length, sealed.key().keyId(), now, null));
        }

        Optional<SealLedgerPort.ChainLink> head = ledger.lockChainHead();
        String prev = head.map(SealLedgerPort.ChainLink::hash).orElse(ZERO_CHAIN);
        long chainSeq = head.map(h -> h.seq() + 1).orElse(1L);
        String chainHash = chainHash(prev, canonical.sha256(), pdf.sha256());
        LocalDate retentionUntil = l.rule().retentionPeriod().from(sealDate);
        SealStamp stamp = new SealStamp(number, now, Sha256.of(canonical.sha256()), Sha256.of(pdf.sha256()), ChainHash.of(chainHash), chainSeq,
                retentionUntil);
        DisclosureStatus from = d.status();
        d.seal(stamp);
        store.save(d);
        records.insertKey(id, sealed.key(), now);
        artifacts.forEach(records::insertArtifact);
        ledger.advanceChainHead(new SealLedgerPort.ChainLink(chainSeq, chainHash));

        ObjectNode detail = identity(l).put("from", from.name()).put("to", d.status().name()).put("disclosureNo", number.value())
                .put("canonicalHash", canonical.sha256()).put("pdfHash", pdf.sha256()).put("chainSeq", chainSeq).put("chainHash", chainHash)
                .put("retentionUntil", retentionUntil.toString()).put("documentKeyId", sealed.key().keyId());
        results(detail, results, unapproved);
        record(actor, AuditAction.DISCLOSURE_SEAL, id, detail);
        resolveOverrideFlags(actor, d, results, approvals, now);
        outbox.append(EventType.DisclosureSealed, id.toString(), now, OutboxPayloads.disclosureSealed(id.value(), number.value(),
                d.lineage().version(), d.ruleVersionId().value(), d.engineSnapshot().map(s -> s.snapshot().snapshotId().value()).orElse(null),
                canonical.sha256(), pdf.sha256(), chainHash, chainSeq, now));
        return new Committed(new Outcome(id, d.status(), List.of(), results, Optional.of(number), false), artifacts, retentionUntil, actor);
    }

    /** 봉인 체인 식 하나({@link com.ga.disclosure.audit.chain.SealChain} — 검증·앵커가 같은 식을 쓴다, V7 GD095는 SQL로 다시 계산). */
    static String chainHash(String prev, String canonicalHash, String pdfHash) {
        return com.ga.disclosure.audit.chain.SealChain.next(prev, canonicalHash, pdfHash);
    }

    /** 보존기한(날짜) 당일 끝 = 다음 날 00:00 Asia/Seoul. */
    public static Instant retainUntilInstant(LocalDate retentionUntil) {
        return retentionUntil.plusDays(1).atStartOfDay(SEOUL).toInstant();
    }

    /** 커밋 후 Object Lock 적용(공통 경로 {@link RetentionLocks}). 실패해도 봉인은 유효하다 — 하나라도 실패하면 true. */
    boolean applyRetention(TenantId tenant, Actor actor, List<ArtifactRecord> artifacts, LocalDate retentionUntil) {
        return locks.apply(tenant, actor, artifacts, retentionUntil);
    }

    /**
     * 봉인 성공 시 열린 오버라이드 플래그 해소(3A 수용심사 §3-7, 승인 Q9): 그 규칙의 실패를 덮는 승인이 있으면 APPROVED(해소자 = 승인자), 봉인
     * 시점에 그 규칙이 더는 실패하지 않으면 RESOLVED_AT_SEAL(SYSTEM). 승인 없는 실패가 남았으면 봉인되지 않았으므로 여기 오지 않는다.
     */
    private void resolveOverrideFlags(Actor actor, Disclosure d, List<ValidationResult> results, List<Review> approvals, Instant at) {
        for (DisclosureFlagPort.OpenFlag f : flags.openFor(d.id())) {
            if (f.type() != DisclosureFlagPort.Type.VALIDATION_OVERRIDE) {
                continue;
            }
            String ruleId = f.targetId().substring(f.targetId().indexOf('/') + 1);
            Optional<ValidationResult> failure = results.stream().filter(r -> r.ruleId().equals(ruleId) && !r.passed()).findFirst();
            DisclosureFlagPort.Resolution resolution;
            String by;
            if (failure.isEmpty()) {
                resolution = DisclosureFlagPort.Resolution.RESOLVED_AT_SEAL;
                by = "SYSTEM";
            } else {
                Review approval = SealGate.approvalFor(failure.get(), approvals, d.ruleVersionId(), d.tenantRuleVersionId())
                        .orElseThrow(() -> new IllegalStateException("sealed with an unapproved overridable failure of " + ruleId));
                resolution = DisclosureFlagPort.Resolution.APPROVED;
                by = approval.approvedBy();
            }
            resolveFlag(actor, d.id(), f, resolution, by, at);
        }
    }

    void resolveFlag(Actor actor, DisclosureId id, DisclosureFlagPort.OpenFlag f, DisclosureFlagPort.Resolution resolution, String by, Instant at) {
        if (flags.resolve(f.flagId(), resolution, by, at)) {
            record(actor, AuditAction.FLAG_RESOLVE, id, JSON.createObjectNode().put("flagId", f.flagId().toString()).put("type", f.type().name())
                    .put("resolution", resolution.name()).put("resolvedBy", by));
        }
    }

    /** 봉인 본문 재료: 고정 룰·서식·상담일 카탈로그로 해석한 문자열 결과까지(승인 Q11). */
    private CanonicalInput canonicalInput(TenantId tenant, Loaded l) {
        Disclosure d = l.disclosure();
        DisclosureContext ctx = d.context();
        List<CanonicalInput.PanelInsurer> panel = ctx.panelOnConsultDate().stream()
                .map(p -> new CanonicalInput.PanelInsurer(p.insurer(), p.insurerName())).toList();
        List<CanonicalInput.Item> items = new ArrayList<>();
        for (DisclosureItem item : d.disclosureItems()) {
            List<CanonicalInput.Reason> reasons = new ArrayList<>();
            List<String> labels = d.reasonLabels(item);
            item.recommendation().ifPresent(r -> {
                for (int i = 0; i < r.codes().size(); i++) {
                    reasons.add(new CanonicalInput.Reason(r.codes().get(i).value(), labels.get(i)));
                }
            });
            items.add(new CanonicalInput.Item(item.itemNo(), item.draft().productKey().orElse(null), item.draft().insurer(),
                    item.draft().productName(), item.draft().group(), item.draft().tempProduct(), item.draft().quoteDocNo().orElse(null),
                    item.draft().recommended(), item.draft().requestedByCustomer(), item.draft().fieldValues(), item.grade().orElseThrow(), reasons,
                    item.recommendation().flatMap(com.ga.disclosure.domain.disclosure.Recommendation::text).orElse(null)));
        }
        return new CanonicalInput(tenant, d.id(), d.lineage().version(), d.lineage().supersedesIdOrNull(), d.agentId(), d.customerRef(),
                d.consultDate(), d.groupCode(), ctx.productGroupName().orElseThrow(() -> new IllegalStateException(
                        "product group " + d.groupCode() + " is not in the catalog on " + d.consultDate())),
                d.issuerMode(), d.ruleVersionId(), d.tenantRuleVersionId().orElse(null), d.template(), d.engineSnapshot().orElseThrow(), panel,
                items);
    }

    /** 판정 룰의 정체(3A I7 구조): 고정 ID·사규 ID·본문 해시·서식 버전. */
    private static ObjectNode identity(Loaded l) {
        ObjectNode detail = JSON.createObjectNode().put("ruleVersionId", l.rule().globalRuleVersionId().value())
                .put("ruleBodyHash", l.rule().bodyHash()).put("templateId", l.template().ref().templateId())
                .put("templateVersion", l.template().ref().version());
        l.rule().tenantRuleVersion().ifPresentOrElse(v -> detail.put("tenantRuleVersionId", v.value()),
                () -> detail.putNull("tenantRuleVersionId"));
        return detail;
    }

    /** SEAL 단계 검증 결과 요약(감사 1행에 싣는다 — 별도 DISCLOSURE_VALIDATE 행을 두지 않는다, 3B 계획 §3.2). */
    private static void results(ObjectNode detail, List<ValidationResult> results, List<ValidationResult> unapproved) {
        ArrayNode rows = detail.putArray("results");
        for (ValidationResult r : results) {
            ObjectNode row = rows.addObject().put("ruleId", r.ruleId()).put("passed", r.passed()).put("overridable", r.overridable())
                    .put("approved", !r.passed() && r.overridable() && !unapproved.contains(r));
            r.subjectHash().ifPresent(h -> row.put("subjectHash", h));
        }
    }

    private void record(Actor actor, AuditAction action, DisclosureId id, ObjectNode detail) {
        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), action, CommandRunner.TARGET, id.toString(), detail));
    }
}

package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.outbox.EventType;
import com.ga.disclosure.audit.outbox.OutboxPayloads;
import com.ga.disclosure.audit.outbox.OutboxPort;
import com.ga.disclosure.domain.disclosure.DisclosureCommand;
import com.ga.disclosure.domain.disclosure.DisclosureStateTable;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.domain.enums.ManagerConfirmMode;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignatureEvidenceKind;
import com.ga.disclosure.domain.enums.SignatureMethod;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.Sha256;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;
import com.ga.disclosure.seal.renderer.SignedPdfAppender;
import com.ga.disclosure.sign.gate.GateFunction;
import com.ga.disclosure.sign.identity.IdentityPolicy;
import com.ga.disclosure.sign.proxy.ProxyIndicator;
import com.ga.disclosure.sign.proxy.ProxySignatureDetector;
import com.ga.disclosure.sign.proxy.ProxyThresholds;
import com.ga.disclosure.sign.token.SignToken;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Channel;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.identity.AgentDirectory;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.anchor.AnchorStore;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.artifact.LockedObject;
import com.ga.disclosure.workflow.artifact.SignatureEvidenceRecord;
import com.ga.disclosure.workflow.disclosure.DisclosureLoader.Loaded;
import com.ga.disclosure.workflow.disclosure.SignSupport.Access;
import com.ga.disclosure.workflow.sign.DeviceInfo;
import com.ga.disclosure.workflow.sign.IdentityResult;
import com.ga.disclosure.workflow.sign.PaperScan;
import com.ga.disclosure.workflow.sign.ScanMatch;
import com.ga.disclosure.workflow.sign.SignRejection;
import com.ga.disclosure.workflow.sign.SignSession;
import com.ga.disclosure.workflow.sign.SignSessionStore;
import com.ga.disclosure.workflow.sign.SignatureCapture;
import com.ga.disclosure.workflow.sign.SignatureStore;
import com.ga.disclosure.workflow.sign.StoredSignature;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * 서명 수집·완료 유스케이스(설계서 §6.5, 4 계획 §7.1~§7.3). 서명 1건은 한 쓰기 트랜잭션이다:
 * <ol>
 *   <li>확인서 행 잠금 → (고객이면) 세션 행 잠금 → 검사(업무 거부, 단락 없이 전부: 채널·본인확인·순서·해시·기한·서명자 집합·중복 등).</li>
 *   <li>서명 ID 발급 → 증거 객체(스트로크·이미지·스캔)를 봉인 때의 문서 키로 암호화(AAD에 서명 ID) → 업로드(잠금 없음).</li>
 *   <li>{@code signature} INSERT(트리거가 두 해시·부모 상태·세션·서명자 집합 검사) → {@code signature_evidence} INSERT → 세션 USED.</li>
 *   <li>대리 서명 탐지(REMOTE_LINK 고객만, 서명을 막지 않음) → 종이 스캔 검토 플래그 → 감사 {@code SIGNATURE_CAPTURED}.</li>
 *   <li>완료 조건(COMPLETE 단계 검증 전부 통과 + 열린 종이 스캔 검토 없음)이면 같은 트랜잭션에서 서명본·증거 패키지를 만들고 COMPLETED(보존기한 연장),
 *       아니면 PARTIALLY_SIGNED → 아웃박스 {@code SignatureCaptured}(완료면 {@code DisclosureCompleted}도) → 커밋.</li>
 *   <li>커밋 뒤 새 증거 객체에 잠금(완료면 산출물·증거 전부에 연장된 기한으로).</li>
 * </ol>
 * 업로드 뒤 롤백되면 잠금 없는 잔여물이 남고 잔여물 정리가 치운다 — 그래서 이 트랜잭션도 봉인과 같은 제한 시간을 쓴다(잔여물 정리 유예의 전제).
 */
public final class SignService {

    /** 서명·완료 결과. 거부면 {@code rejections}가 있고 서명이 없다. */
    public record Outcome(DisclosureId id, DisclosureStatus status, List<SignRejection> rejections, Optional<UUID> signatureId, boolean completed,
                          List<ValidationResult> completionResults, boolean retentionPending) {
        public Outcome {
            rejections = List.copyOf(rejections);
            completionResults = List.copyOf(completionResults);
        }

        public boolean accepted() {
            return rejections.isEmpty();
        }
    }

    /** 서명 1건의 재료(검사를 통과한 뒤). */
    private record Draft(SignerRole role, String subjectOrNull, SignSession sessionOrNull, SignatureChannel channel, SignatureMethod method,
                         List<IdentityResult> identity, DeviceInfo deviceOrNull, String ipOrNull, List<UUID> acknowledged, ScanMatch scanOrNull,
                         Map<SignatureEvidenceKind, byte[]> evidence) {
    }

    private record Committed(Outcome outcome, List<LockedObject> toLock, LocalDate retentionUntil, Actor actorOrNull) {
        static Committed rejected(Outcome o, Actor actor) {
            return new Committed(o, List.of(), null, actor);
        }

        Actor actorOr(Caller caller) {
            return actorOrNull != null ? actorOrNull : new Actor(caller.subject(), "OPERATOR");
        }
    }

    private final DisclosureStore store;
    private final DisclosureFlagPort flags;
    private final AuditPort audit;
    private final Clock clock;
    private final OutboxPort outbox;
    private final SignSessionStore sessions;
    private final SignatureStore signatures;
    private final DocumentRecordStore records;
    private final DocumentCryptoPort crypto;
    private final ArtifactStore storage;
    private final CommandRunner runner;
    private final DisclosureLoader loader;
    private final SignSupport support;
    private final StoredArtifacts stored;
    private final Completion completion;
    private final RetentionLocks locks;
    private final Duration transactionTimeout;
    private final AuthorizationPort authz;
    private final AgentDirectory agents;

    public SignService(DisclosureServiceDeps deps, SignSessionStore sessions, SignatureStore signatures, DocumentRecordStore records,
                       DocumentCryptoPort crypto, ArtifactStore storage, SignedPdfAppender appender, Duration transactionTimeout, AnchorStore anchors) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.signatures = Objects.requireNonNull(signatures, "signatures");
        this.records = Objects.requireNonNull(records, "records");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.transactionTimeout = Objects.requireNonNull(transactionTimeout, "transactionTimeout");
        this.store = deps.store();
        this.flags = deps.flags();
        this.audit = deps.audit();
        this.clock = deps.clock();
        this.outbox = deps.outbox();
        WorkflowTransactions transactions = deps.transactions();
        this.runner = new CommandRunner(transactions, audit, clock);
        this.loader = deps.loader();
        this.support = new SignSupport(sessions, loader, deps.agents(), audit, transactions, clock, deps.tenants());
        this.stored = new StoredArtifacts(records, crypto, storage);
        RuleResolver rules = deps.rules();
        TemplateResolver templates = deps.templates();
        this.completion = new Completion(stored, records, crypto, storage, audit, rules, templates, appender, anchors);
        this.locks = new RetentionLocks(records, storage, audit, transactions, clock);
        this.authz = deps.authz();
        this.agents = deps.agents();
    }

    // ------------------------------------------------------------------ 고객: 터치·원격 서명

    /** 고객 서명(TOUCH_PAD·REMOTE_LINK, DRAWN). 토큰 경로 — 세션을 열 수 없으면 {@code SignTokenRejected}. */
    @UseCaseEntry(Action.SIGN_CAPTURE)
    public Outcome capture(String rawToken, SignatureCapture capture) {
        Objects.requireNonNull(capture, "capture");
        SignToken token = SignSupport.parse(rawToken);
        return customerPath(SignSupport.anonymous(token), token, "SIGN",
                s -> authz.require(SignSupport.customerCaller(token, s), Action.SIGN_CAPTURE, new Target.Session(s.sessionId())), (g, now) -> {
            SignSession s = g.session();
            if (s.channel() == SignatureChannel.PAPER_SCAN) {
                throw new IllegalArgumentException("a PAPER_SCAN session takes an uploaded scan, not a drawn signature");
            }
            Map<SignatureEvidenceKind, byte[]> evidence = new EnumMap<>(SignatureEvidenceKind.class);
            evidence.put(SignatureEvidenceKind.STROKES, capture.strokes());
            evidence.put(SignatureEvidenceKind.IMAGE, capture.imagePng());
            return new Draft(SignerRole.CUSTOMER, null, s, s.channel(), SignatureMethod.DRAWN, null, capture.deviceOrNull(), capture.ipOrNull(),
                    List.of(), null, evidence);
        });
    }

    /**
     * 종이 스캔(PAPER_SCAN 세션, UPLOADED_SCAN): 담당 설계사가 업로드하고 스캔본 각주의 번호·해시 접두를 입력해 원본과 대조한다(OCR 없음, 불일치는
     * 업무 거부 {@code SCAN_MISMATCH} — 감사에는 일치 여부만). 룰이 관리자 검토를 요구하면 플래그 {@code PAPER_SCAN_REVIEW}가 열리고 해소 전에는
     * 완료되지 않는다.
     */
    @UseCaseEntry(Action.PAPER_SCAN_UPLOAD)
    public Outcome uploadPaperScan(Caller caller, String rawToken, PaperScan scan) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(scan, "scan");
        SignToken token = SignSupport.parse(rawToken);
        SignSupport.sameTenant(caller, token);
        return customerPath(caller, token, "SIGN", s -> authz.require(caller, Action.PAPER_SCAN_UPLOAD, new Target.Session(s.sessionId())),
                (g, now) -> {
            SignSession s = g.session();
            if (s.channel() != SignatureChannel.PAPER_SCAN) {
                throw new IllegalArgumentException("a " + s.channel() + " session does not take a paper scan");
            }
            SealStamp seal = g.loaded().disclosure().sealStamp().orElseThrow();
            ScanMatch match = scan.matchAgainst(seal.number().value(), seal.canonicalHash().hex());
            Map<SignatureEvidenceKind, byte[]> evidence = new EnumMap<>(SignatureEvidenceKind.class);
            evidence.put(SignatureEvidenceKind.SCAN, scan.image());
            return new Draft(SignerRole.CUSTOMER, null, s, SignatureChannel.PAPER_SCAN, SignatureMethod.UPLOADED_SCAN, null, null, null, List.of(),
                    match, evidence);
        });
    }

    @FunctionalInterface
    private interface CustomerDraft {
        Draft draft(Access.Granted granted, Instant now);
    }

    /**
     * 고객 토큰 경로(고객 터치·원격 서명, 설계사의 종이 스캔 업로드). 토큰 대조로 세션을 연 뒤 {@code authorize}(진입점의 람다 — 인가 호출은
     * 진입점에 있다)로 행위자를 받는다. 설계사 업로드({@code caller}가 CLI·API)는 담당 설계사여야 한다.
     */
    private Outcome customerPath(Caller caller, SignToken token, String command, java.util.function.Function<SignSession, Actor> authorize,
                                 CustomerDraft drafting) {
        TenantId tenant = token.tenant();
        boolean byAgent = caller.channel() != Channel.SIGN_TOKEN;
        Object result = runner.inTransaction(caller, command, null, transactionTimeout, attempt -> {
            Access a = support.access(tenant, token, clock.instant());
            if (!(a instanceof Access.Granted g)) {
                return a;
            }
            Actor actor = attempt.granted(authorize.apply(g.session()));
            Instant now = SignSupport.signingTime(g.loaded().disclosure(), clock.instant());
            Draft draft = drafting.draft(g, now);
            List<SignRejection> rejections = customerChecks(g, draft, now);
            if (byAgent && !support.assigned(actor, g.loaded().disclosure())) {
                rejections.add(SignRejection.AGENT_NOT_ASSIGNED);
            }
            if (draft.scanOrNull() != null && !draft.scanOrNull().matched()) {
                rejections.add(SignRejection.SCAN_MISMATCH);
            }
            if (!rejections.isEmpty()) {
                ObjectNode extra = SignSupport.JSON.createObjectNode().put("sessionId", g.session().sessionId().toString())
                        .put("channel", g.session().channel().name());
                if (draft.scanOrNull() != null) {
                    extra.set("scanMatch", draft.scanOrNull().toJson());
                }
                return reject(actor, g.loaded().disclosure(), command, rejections, extra);
            }
            List<IdentityMethod> required = g.loaded().rule().identityMethods(g.session().channel());
            List<IdentityResult> identity = required.stream().map(m -> new IdentityResult(m, true, now)).toList();
            Draft complete = new Draft(draft.role(), draft.subjectOrNull(), draft.sessionOrNull(), draft.channel(), draft.method(), identity,
                    draft.deviceOrNull(), draft.ipOrNull(), draft.acknowledged(), draft.scanOrNull(), draft.evidence());
            return record(tenant, actor, g.loaded(), complete, now, () -> {
            });
        });
        if (result instanceof Access.Denied denied) {
            throw support.deny(tenant, command, denied);
        }
        Committed committed = (Committed) result;
        return afterCommit(tenant, committed.actorOr(caller), committed);
    }

    /** 고객 서명 검사(4 계획 §7.1 3항, 단락 없음). 세션 OPEN·TTL은 접근이 이미 확인했다. */
    private List<SignRejection> customerChecks(Access.Granted g, Draft draft, Instant now) {
        Disclosure d = g.loaded().disclosure();
        EffectiveRule rule = g.loaded().rule();
        SignSession s = g.session();
        List<SignRejection> out = new ArrayList<>();
        if (!SignSupport.channelEnabled(rule, s.channel())) {
            out.add(SignRejection.CHANNEL_DISABLED);
        }
        if (!IdentityPolicy.complete(rule.identityMethods(s.channel()), s.state().identityPassed())) {
            out.add(SignRejection.IDENTITY_INCOMPLETE);
        }
        commonChecks(d, rule, SignerRole.CUSTOMER, now, out);
        SealStamp seal = d.sealStamp().orElseThrow();
        if (!s.signedDocHash().equals(seal.canonicalHash()) || !s.signedPdfHash().equals(seal.pdfHash())) {
            out.add(SignRejection.HASH_CHANGED);
        }
        return out;
    }

    /** 역할 공통 검사: 서명자 집합·중복·순서·기한. */
    private static void commonChecks(Disclosure d, EffectiveRule rule, SignerRole role, Instant now, List<SignRejection> out) {
        if (!SignSupport.inSignerSet(rule, role)) {
            out.add(SignRejection.NOT_IN_SIGNER_SET);
        }
        if (SignSupport.signed(d, role)) {
            out.add(SignRejection.ALREADY_SIGNED);
        }
        if (SignSupport.outOfOrder(rule, d, role)) {
            out.add(SignRejection.ORDER_VIOLATION);
        }
        if (d.deadline().orElseThrow().passed(now)) {
            out.add(SignRejection.DEADLINE_PASSED);
        }
    }

    // ------------------------------------------------------------------ 설계사·관리자(SSO)

    /**
     * 설계사 서명(채널 SSO): 행위자 → {@code identity_link} → agent_id = 확인서 agent_id. 방법은 룰 {@code agentSignMethod} — DRAWN이면 스트로크·이미지가
     * 필요하고, SSO_APPROVAL이면 받지 않는다.
     */
    @UseCaseEntry(Action.AGENT_SIGN)
    public Outcome agentSign(Caller caller, DisclosureId id, SignatureCapture drawnOrNull) {
        return ssoPath(caller, id, () -> authz.require(caller, Action.AGENT_SIGN, Target.disclosure(id)), (l, now, rejections, agent) -> {
            Disclosure d = l.disclosure();
            if (!support.assigned(agent, d)) {
                rejections.add(SignRejection.AGENT_NOT_ASSIGNED);
            }
            SignatureMethod method = l.rule().agentSignMethod();
            if ((method == SignatureMethod.DRAWN) != (drawnOrNull != null)) {
                rejections.add(SignRejection.AGENT_SIGN_METHOD_MISMATCH);
            }
            commonChecks(d, l.rule(), SignerRole.AGENT, now, rejections);
            Map<SignatureEvidenceKind, byte[]> evidence = new EnumMap<>(SignatureEvidenceKind.class);
            if (drawnOrNull != null) {
                evidence.put(SignatureEvidenceKind.STROKES, drawnOrNull.strokes());
                evidence.put(SignatureEvidenceKind.IMAGE, drawnOrNull.imagePng());
            }
            return new Draft(SignerRole.AGENT, agent.subject(), null, SignatureChannel.SSO, method, List.of(),
                    drawnOrNull == null ? null : drawnOrNull.deviceOrNull(), drawnOrNull == null ? null : drawnOrNull.ipOrNull(), List.of(), null,
                    evidence);
        }, agent -> {
        });
    }

    /**
     * 관리자 확인(채널 SSO, SSO_APPROVAL, D-9): {@code managerConfirmMode = OFF}면 거부. 확인서에 걸린 플래그 전부(열림·닫힘)를 사유 확인 목록에 담아야
     * 한다(승인 Q9) — 확인한 ID를 서명 레코드에 남긴다. 열린 종이 스캔 검토는 이 확인이 해소한다(승인 Q10). 역할·조직 범위는 {@code MANAGER_CONFIRM} 인가(관리자 ORG).
     */
    @UseCaseEntry(Action.MANAGER_CONFIRM)
    public Outcome managerConfirm(Caller caller, DisclosureId id, Set<UUID> acknowledgedFlags) {
        Objects.requireNonNull(acknowledgedFlags, "acknowledgedFlags");
        List<UUID> acknowledged = new ArrayList<>();
        return ssoPath(caller, id, () -> authz.require(caller, Action.MANAGER_CONFIRM, Target.disclosure(id)), (l, now, rejections, manager) -> {
            Disclosure d = l.disclosure();
            if (l.rule().managerConfirmMode() == ManagerConfirmMode.OFF) {
                rejections.add(SignRejection.MANAGER_CONFIRM_DISABLED);
            } else {
                commonChecks(d, l.rule(), SignerRole.MANAGER, now, rejections);
            }
            List<DisclosureFlagPort.FlagSummary> all = flags.allFor(id);
            if (!all.stream().map(DisclosureFlagPort.FlagSummary::flagId).allMatch(acknowledgedFlags::contains)) {
                rejections.add(SignRejection.ACKNOWLEDGEMENT_MISSING);
            }
            all.stream().map(DisclosureFlagPort.FlagSummary::flagId).filter(acknowledgedFlags::contains).forEach(acknowledged::add);
            return new Draft(SignerRole.MANAGER, manager.subject(), null, SignatureChannel.SSO, SignatureMethod.SSO_APPROVAL, List.of(), null, null,
                    List.copyOf(acknowledged), null, Map.of());
        }, manager -> resolveScanReviews(manager, id));
    }

    @FunctionalInterface
    private interface SsoDraft {
        Draft draft(Loaded loaded, Instant now, List<SignRejection> rejections, Actor actor);
    }

    /** 설계사·관리자 서명 경로. {@code authorize}는 진입점의 람다(인가 호출은 진입점에 있다)이고 트랜잭션 안에서 먼저 부른다. */
    private Outcome ssoPath(Caller caller, DisclosureId id, java.util.function.Supplier<Actor> authorize, SsoDraft drafting,
                            java.util.function.Consumer<Actor> beforeCompletion) {
        TenantId tenant = caller.tenant();
        Committed committed = runner.inTransaction(caller, DisclosureCommand.SIGN.name(), id.toString(), transactionTimeout, attempt -> {
            Actor actor = attempt.granted(authorize.get());
            Loaded l = loader.load(tenant, id);
            DisclosureStateTable.require(l.disclosure().status(), DisclosureCommand.SIGN);
            Instant now = SignSupport.signingTime(l.disclosure(), clock.instant());
            List<SignRejection> rejections = new ArrayList<>();
            Draft draft = drafting.draft(l, now, rejections, actor);
            if (!rejections.isEmpty()) {
                return reject(actor, l.disclosure(), DisclosureCommand.SIGN.name(), rejections,
                        SignSupport.JSON.createObjectNode().put("signerRole", draft.role().name()));
            }
            return record(tenant, actor, l, draft, now, () -> beforeCompletion.accept(actor));
        });
        return afterCommit(tenant, committed.actorOr(caller), committed);
    }

    // ------------------------------------------------------------------ 종이 스캔 검토·완료 명령

    /**
     * 종이 스캔 검토(승인 Q10): 관리자가 서명자 집합에 없을 때(OFF·OPTIONAL) {@code exceptionApproval.role}이 열린 {@code PAPER_SCAN_REVIEW}를 해소한다.
     * 완료는 이 뒤의 {@link #complete} 명령이 시도한다.
     */
    @UseCaseEntry(Action.PAPER_SCAN_REVIEW)
    public Outcome reviewPaperScan(Caller caller, DisclosureId id) {
        TenantId tenant = caller.tenant();
        return runner.inTransaction(caller, "PAPER_SCAN_REVIEW", id.toString(), attempt -> {
            Actor reviewer = attempt.granted(authz.require(caller, Action.PAPER_SCAN_REVIEW, Target.disclosure(id)));
            Loaded l = loader.load(tenant, id);
            Disclosure d = l.disclosure();
            List<SignRejection> rejections = new ArrayList<>();
            // 검토 역할은 룰의 예외 승인 역할 — identity_link로 본다(6A, 절대 규칙 5)
            if (!BusinessRoles.holds(agents, reviewer, l.rule().exceptionApprovalRole().name())) {
                rejections.add(SignRejection.REVIEW_ROLE_REQUIRED);
            }
            if (l.rule().signerSet().contains(SignerRole.MANAGER)) {
                rejections.add(SignRejection.REVIEW_VIA_MANAGER_CONFIRM);
            }
            if (openScanReviews(id).isEmpty()) {
                rejections.add(SignRejection.NO_REVIEW_PENDING);
            }
            if (!rejections.isEmpty()) {
                return reject(reviewer, d, "PAPER_SCAN_REVIEW", rejections, null).outcome();
            }
            resolveScanReviews(reviewer, id);
            return new Outcome(id, d.status(), List.of(), Optional.empty(), false, List.of(), false);
        });
    }

    /**
     * 완료 명령(PARTIALLY_SIGNED → COMPLETED, 상태표 {@code PARTIALLY_SIGNED,COMPLETE}): 서명을 더하지 않고 완료 조건을 다시 본다 — 종이 스캔 검토가
     * 해소된 뒤의 경로다. 조건이 안 되면 업무 거부({@code SIGNER_SET_INCOMPLETE}·{@code PAPER_SCAN_REVIEW_OPEN}, 승인 Q10).
     */
    @UseCaseEntry(Action.COMPLETE)
    public Outcome complete(Caller caller, DisclosureId id) {
        TenantId tenant = caller.tenant();
        Committed committed = runner.inTransaction(caller, DisclosureCommand.COMPLETE.name(), id.toString(), transactionTimeout, attempt -> {
            Actor actor = attempt.granted(authz.require(caller, Action.COMPLETE, Target.disclosure(id)));
            Loaded l = loader.load(tenant, id);
            Disclosure d = l.disclosure();
            DisclosureStateTable.require(d.status(), DisclosureCommand.COMPLETE);
            Instant now = SignSupport.signingTime(d, clock.instant());
            List<ValidationResult> results = d.completionResults(null, l.check());
            List<SignRejection> rejections = new ArrayList<>();
            if (!results.stream().allMatch(ValidationResult::passed)) {
                rejections.add(SignRejection.SIGNER_SET_INCOMPLETE);
            }
            if (!openScanReviews(id).isEmpty()) {
                rejections.add(SignRejection.PAPER_SCAN_REVIEW_OPEN);
            }
            if (!rejections.isEmpty()) {
                ObjectNode extra = SignSupport.JSON.createObjectNode();
                ArrayNode failed = extra.putArray("failedRules");
                results.stream().filter(r -> !r.passed()).forEach(r -> failed.add(r.ruleId()));
                support.reject(actor, d, DisclosureCommand.COMPLETE.name(), rejections, extra);
                return Committed.rejected(new Outcome(id, d.status(), rejections, Optional.empty(), false, results, false), actor);
            }
            Completion.Built built = completion.build(tenant, l, signatures.signatures(id), Map.of(), now);
            TransitionOutcome o = d.complete(built.stamp(), l.check());
            if (!(o instanceof TransitionOutcome.Applied)) {
                throw new IllegalStateException("completion of " + id + " was rejected after its preview passed");
            }
            store.save(d);
            completed(actor, d, built);
            return new Committed(new Outcome(id, d.status(), List.of(), Optional.empty(), true, o.results(), false), allLocked(id),
                    built.stamp().retentionUntil(), actor);
        });
        return afterCommit(tenant, committed.actorOr(caller), committed);
    }

    // ------------------------------------------------------------------ 공통 기록 경로

    private Committed record(TenantId tenant, Actor actor, Loaded l, Draft draft, Instant now, Runnable beforeCompletion) {
        Disclosure d = l.disclosure();
        DisclosureId id = d.id();
        SealStamp seal = d.sealStamp().orElseThrow();
        UUID signatureId = UUID.randomUUID();
        SignSession session = draft.sessionOrNull();
        Sha256 docHash = session == null ? seal.canonicalHash() : session.signedDocHash();
        Sha256 pdfHash = session == null ? seal.pdfHash() : session.signedPdfHash();

        // 증거 객체: 봉인 때의 문서 키, AAD에 서명 ID — 업로드는 잠금 없이(커밋 뒤에 잠근다)
        DocumentCryptoPort.StoredKey key = stored.liveKey(id);
        List<SignatureEvidenceRecord> evidence = new ArrayList<>();
        for (Map.Entry<SignatureEvidenceKind, byte[]> e : draft.evidence().entrySet()) {
            byte[] cipher = crypto.encryptEvidence(tenant, id, key, signatureId, e.getKey(), e.getValue());
            Sha256 cipherHash = Sha256.of(com.ga.platform.canonical.Sha256.of(cipher));
            String storageKey = SignatureEvidenceRecord.storageKey(tenant.value(), id, signatureId, e.getKey(), cipherHash);
            storage.put(storageKey, cipher);
            evidence.add(new SignatureEvidenceRecord(id, signatureId, e.getKey(), storageKey, Sha256.of(com.ga.platform.canonical.Sha256.of(e.getValue())),
                    e.getValue().length, cipherHash, cipher.length, key.keyId(), now, null));
        }
        StoredSignature signature = new StoredSignature(signatureId, id, draft.role(), draft.subjectOrNull(), draft.channel(), draft.method(), docHash,
                pdfHash, session == null ? null : session.sessionId(), draft.identity(), session == null ? null : session.viewOrNull(),
                draft.deviceOrNull(), draft.ipOrNull(), draft.acknowledged(), draft.scanOrNull(), now);
        signatures.insert(signature);
        evidence.forEach(records::insertEvidence);
        if (session != null) {
            sessions.update(session.with(session.state().capture(l.rule().identityMethods(session.channel())), now));
        }
        ObjectNode detail = SignSupport.JSON.createObjectNode().put("signatureId", signatureId.toString()).put("signerRole", draft.role().name())
                .put("channel", draft.channel().name()).put("method", draft.method().name()).put("signedDocHash", docHash.hex())
                .put("signedPdfHash", pdfHash.hex());
        if (session != null) {
            detail.put("sessionId", session.sessionId().toString());
        }
        ArrayNode identityRows = detail.putArray("identityCheck");
        draft.identity().forEach(r -> identityRows.addObject().put("type", r.method().name()).put("result", r.passed() ? "PASS" : "FAIL"));
        ArrayNode objects = detail.putArray("evidence");
        evidence.forEach(e -> objects.addObject().put("kind", e.kind().name()).put("sha256", e.sha256().hex()).put("cipherSha256", e.cipherSha256().hex()));
        ArrayNode acks = detail.putArray("acknowledgedFlags");
        draft.acknowledged().forEach(f -> acks.add(f.toString()));
        if (draft.scanOrNull() != null) {
            detail.set("scanMatch", draft.scanOrNull().toJson());
        }
        support.record(actor, AuditAction.SIGNATURE_CAPTURED, id, detail);
        detectProxy(actor, l, draft, signatureId, now);
        if (draft.method() == SignatureMethod.UPLOADED_SCAN && l.rule().channel(SignatureChannel.PAPER_SCAN).requiresManagerReview()) {
            DisclosureFlagPort.RaisedFlag flag = flags.raise(DisclosureFlagPort.Type.PAPER_SCAN_REVIEW, "MEDIUM", id, "SIGNATURE",
                    signatureId.toString(), now);
            support.record(actor, AuditAction.FLAG_RAISE, id, SignSupport.JSON.createObjectNode().put("flagId", flag.flagId().toString())
                    .put("type", DisclosureFlagPort.Type.PAPER_SCAN_REVIEW.name()).put("created", flag.created())
                    .put("signatureId", signatureId.toString()));
        }
        beforeCompletion.run();

        // 완료 조건: COMPLETE 단계 검증 전부 통과 + 열린 종이 스캔 검토 없음 → 같은 트랜잭션에서 완료
        ValidationSubject.SignatureMark mark = SignSupport.mark(draft.role(), now);
        List<ValidationResult> results = d.completionResults(mark, l.check());
        boolean completes = results.stream().allMatch(ValidationResult::passed) && openScanReviews(id).isEmpty();
        Completion.Built built = null;
        if (completes) {
            Map<UUID, byte[]> fresh = new HashMap<>();
            if (draft.evidence().containsKey(SignatureEvidenceKind.IMAGE)) {
                fresh.put(signatureId, draft.evidence().get(SignatureEvidenceKind.IMAGE));
            }
            built = completion.build(tenant, l, signatures.signatures(id), fresh, now);
            TransitionOutcome o = d.sign(mark, built.stamp(), l.check());
            if (!(o instanceof TransitionOutcome.Applied)) {
                throw new IllegalStateException("completion of " + id + " was rejected after its preview passed");
            }
        } else {
            d.sign(mark, null, l.check());
        }
        store.save(d);
        List<String> pending = GateFunction.evaluate(d.status(), l.rule().signerSet(), d.signatures().stream().map(ValidationSubject.SignatureMark::role)
                .toList(), l.rule().gateRequiresManager()).pendingRoles().stream().map(Enum::name).toList();
        outbox.append(EventType.SignatureCaptured, id.toString(), now, OutboxPayloads.signatureCaptured(id.value(), seal.number().value(),
                draft.role().name(), draft.channel().name(), docHash.hex(), now, pending));
        Outcome outcome = new Outcome(id, d.status(), List.of(), Optional.of(signatureId), completes, results, false);
        if (completes) {
            completed(actor, d, built);
            return new Committed(outcome, allLocked(id), built.stamp().retentionUntil(), actor);
        }
        return new Committed(outcome, List.copyOf(evidence), seal.retentionUntil(), actor);
    }

    /** 완료 감사·이벤트(감사 발췌의 경계 다음 행). */
    private void completed(Actor actor, Disclosure d, Completion.Built built) {
        SealStamp seal = d.sealStamp().orElseThrow();
        ObjectNode detail = SignSupport.JSON.createObjectNode().put("completedAt", built.stamp().completedAt().toString())
                .put("retentionUntil", built.stamp().retentionUntil().toString()).put("signedPdfSha256", built.signedPdfSha256())
                .put("evidenceSha256", built.evidenceSha256()).put("manifestSha256", built.manifestSha256())
                .put("auditFromSeq", built.auditFromSeq()).put("auditToSeq", built.auditToSeq());
        ArrayNode signers = detail.putArray("signers");
        d.signatures().forEach(m -> signers.add(m.role().name()));
        support.record(actor, AuditAction.DISCLOSURE_COMPLETED, d.id(), detail);
        outbox.append(EventType.DisclosureCompleted, d.id().toString(), built.stamp().completedAt(), OutboxPayloads.disclosureCompleted(d.id().value(),
                seal.number().value(), built.stamp().completedAt(), built.stamp().retentionUntil()));
    }

    /** 대리 서명 탐지(REMOTE_LINK 고객만, 4 계획 §5): 서명 INSERT 뒤 집계(이번 포함). 감사에는 지표·값·임계치만(지문·IP 원값 없음). */
    private void detectProxy(Actor actor, Loaded l, Draft draft, UUID signatureId, Instant now) {
        if (draft.role() != SignerRole.CUSTOMER || draft.channel() != SignatureChannel.REMOTE_LINK) {
            return;
        }
        EffectiveRule rule = l.rule();
        LocalDate day = ProxySignatureDetector.dayOf(now);
        String fingerprint = draft.deviceOrNull() == null ? null : draft.deviceOrNull().fingerprintOrNull();
        OptionalInt byDevice = fingerprint == null ? OptionalInt.empty() : OptionalInt.of(signatures.distinctCustomersByDevice(fingerprint, day));
        OptionalInt byIp = draft.ipOrNull() == null || rule.sameIpDistinctCustomersPerDay().isEmpty() ? OptionalInt.empty()
                : OptionalInt.of(signatures.distinctCustomersByIp(draft.ipOrNull(), day));
        List<ProxyIndicator> indicators = ProxySignatureDetector.evaluate(draft.channel(), byDevice, byIp,
                draft.sessionOrNull().sentAt().orElse(null), now, new ProxyThresholds(rule.sameDeviceDistinctCustomersPerDay(),
                        rule.sameIpDistinctCustomersPerDay(), rule.minSecondsFromSendToSign()));
        if (indicators.isEmpty()) {
            return;
        }
        DisclosureId id = l.disclosure().id();
        DisclosureFlagPort.RaisedFlag flag = flags.raise(DisclosureFlagPort.Type.SIGNATURE_DEVICE_REUSE, "HIGH", id, "SIGNATURE", signatureId.toString(),
                now);
        ObjectNode detail = SignSupport.JSON.createObjectNode().put("flagId", flag.flagId().toString())
                .put("type", DisclosureFlagPort.Type.SIGNATURE_DEVICE_REUSE.name()).put("created", flag.created())
                .put("signatureId", signatureId.toString());
        ArrayNode rows = detail.putArray("indicators");
        indicators.forEach(i -> rows.addObject().put("kind", i.kind().name()).put("value", i.value()).put("threshold", i.threshold()));
        support.record(actor, AuditAction.FLAG_RAISE, id, detail);
    }

    private List<DisclosureFlagPort.OpenFlag> openScanReviews(DisclosureId id) {
        return flags.openFor(id).stream().filter(f -> f.type() == DisclosureFlagPort.Type.PAPER_SCAN_REVIEW).toList();
    }

    private void resolveScanReviews(Actor actor, DisclosureId id) {
        Instant now = clock.instant();
        for (DisclosureFlagPort.OpenFlag f : openScanReviews(id)) {
            if (flags.resolve(f.flagId(), DisclosureFlagPort.Resolution.PAPER_SCAN_REVIEWED, actor.subject(), now)) {
                support.record(actor, AuditAction.FLAG_RESOLVE, id, SignSupport.JSON.createObjectNode().put("flagId", f.flagId().toString())
                        .put("type", f.type().name()).put("resolution", DisclosureFlagPort.Resolution.PAPER_SCAN_REVIEWED.name())
                        .put("resolvedBy", actor.subject()));
            }
        }
    }

    /** 완료 뒤 잠금 대상: 확인서의 산출물·서명 증거 전부(연장된 기한으로 다시 건다). */
    private List<LockedObject> allLocked(DisclosureId id) {
        List<LockedObject> all = new ArrayList<>(records.artifacts(id));
        all.addAll(records.evidence(id));
        return List.copyOf(new LinkedHashSet<>(all));
    }

    private Committed reject(Actor actor, Disclosure d, String command, List<SignRejection> rejections, ObjectNode extra) {
        support.reject(actor, d, command, rejections, extra);
        return Committed.rejected(new Outcome(d.id(), d.status(), rejections, Optional.empty(), false, List.of(), false), actor);
    }

    private Outcome afterCommit(TenantId tenant, Actor actor, Committed c) {
        if (c.toLock().isEmpty()) {
            return c.outcome();
        }
        boolean pending = locks.apply(tenant, actor, c.toLock(), c.retentionUntil());
        Outcome o = c.outcome();
        return new Outcome(o.id(), o.status(), o.rejections(), o.signatureId(), o.completed(), o.completionResults(), pending);
    }
}

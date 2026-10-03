package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.disclosure.DisclosureCommand;
import com.ga.disclosure.domain.disclosure.DisclosureStateTable;
import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.pii.BirthDate;
import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.sign.identity.IdentityPolicy;
import com.ga.disclosure.sign.session.SessionEvent;
import com.ga.disclosure.sign.session.SessionStatus;
import com.ga.disclosure.sign.session.SessionWindow;
import com.ga.disclosure.sign.session.SignSessionState;
import com.ga.disclosure.sign.token.SignToken;
import com.ga.disclosure.sign.token.TokenSource;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.artifact.ArtifactRecord;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.customer.Customer;
import com.ga.disclosure.workflow.customer.CustomerVault;
import com.ga.disclosure.workflow.customer.NotificationPurpose;
import com.ga.disclosure.workflow.disclosure.DisclosureLoader.Loaded;
import com.ga.disclosure.workflow.disclosure.SignSupport.Access;
import com.ga.disclosure.workflow.sign.IdentityInputs;
import com.ga.disclosure.workflow.sign.IdentityResult;
import com.ga.disclosure.workflow.sign.NotifyPort;
import com.ga.disclosure.workflow.sign.SignRejection;
import com.ga.disclosure.workflow.sign.SignSession;
import com.ga.disclosure.workflow.sign.SignSessionStore;
import com.ga.disclosure.workflow.sign.ViewEvidence;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 고객 서명 세션 유스케이스(설계서 §6.5, 4 계획 §2): 발급·재발급(설계사), 원격 링크 발송, 열람(서명 대상 PDF), 열람 증거, 본인확인(고객 입력·설계사 대면
 * 확인). 서명 수집은 {@link SignService}.
 *
 * <ul>
 *   <li><b>발급</b>: 담당 설계사만(identity_link). 부모가 SEALED·PARTIALLY_SIGNED(상태표 SIGN이 허용하는 상태)이고, 채널이 룰에서 켜져 있고, 고객이
 *       서명자 집합에 있고 아직 서명하지 않았고, SEQUENTIAL 순서상 고객 차례이고, 서명 기한 전이어야 한다(업무 거부 — 단락 없이 전부). 열린 세션이
 *       있으면 닫는다(TTL 경과면 EXPIRED, 아니면 REVOKED(REISSUED)). 토큰 256비트, 저장은 해시, 만료 = min(발급 + TTL, 서명 기한 끝), 두 해시 고정.
 *       TOUCH_PAD·PAPER_SCAN 토큰은 설계사 기기에 돌려주고, REMOTE_LINK 토큰은 커밋 뒤 고객 번호로만 나간다(결과에 토큰 없음).</li>
 *   <li><b>토큰 경로</b>(열람·열람 증거·본인확인): 모르는 토큰·닫힌 세션·TTL 경과는 한 예외 타입({@code SignTokenRejected}, 승인 B2), 감사에는 사유
 *       코드만. 실패는 세션 상태를 바꾸지 않는다(본인확인 실패 횟수는 상태표의 사건이라 예외).</li>
 *   <li><b>본인확인</b>: 룰 {@code identityCheck[channel]}의 수단만 다룬다. LINK_POSSESSION = 토큰 제시, BIRTH_DATE = 저장된 생년월일 복호화(감사
 *       {@code CUSTOMER_VIEW} 사유 IDENTITY_CHECK) 후 상수 시간 대조, AGENT_FACE_TO_FACE = 담당 설계사의 대면 확인 기록, SCROLL_COMPLETE = 열람 증거.
 *       입력값은 지역 변수로만 다니고 결과만 남는다. 실패가 {@code maxFailures}에 닿으면 세션 REVOKED(IDENTITY_FAILED) + 플래그 IDENTITY_FAILED.</li>
 * </ul>
 */
public final class SignSessionService {

    /** 발급 결과. 거부면 세션·토큰이 없다. REMOTE_LINK는 토큰을 돌려주지 않는다({@code sent}가 발송 여부). */
    public record IssueOutcome(DisclosureId id, List<SignRejection> rejections, Optional<UUID> sessionId, Optional<SignToken> token,
                               Optional<Instant> expiresAt, boolean sent) {
        public IssueOutcome {
            rejections = List.copyOf(rejections);
        }

        public boolean issued() {
            return rejections.isEmpty();
        }
    }

    /** 본인확인 결과(입력값 없음). */
    public record IdentityOutcome(UUID sessionId, List<IdentityResult> results, List<IdentityMethod> missing, int failures, boolean revoked,
                                  List<SignRejection> rejections) {
        public IdentityOutcome {
            results = List.copyOf(results);
            missing = List.copyOf(missing);
            rejections = List.copyOf(rejections);
        }
    }

    private final SignSessionStore sessions;
    private final CustomerVault customers;
    private final DisclosureFlagPort flags;
    private final AuditPort audit;
    private final Clock clock;
    private final TokenSource tokens;
    private final NotifyPort notify;
    private final CommandRunner runner;
    private final DisclosureLoader loader;
    private final SignSupport support;
    private final StoredArtifacts stored;
    private final SessionClosing closing;
    private final AuthorizationPort authz;

    public SignSessionService(DisclosureServiceDeps deps, SignSessionStore sessions, DocumentRecordStore records, DocumentCryptoPort crypto,
                              ArtifactStore storage, TokenSource tokens, NotifyPort notify) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.notify = Objects.requireNonNull(notify, "notify");
        this.customers = deps.customers();
        this.flags = deps.flags();
        this.audit = deps.audit();
        this.clock = deps.clock();
        this.runner = new CommandRunner(deps.transactions(), audit, clock);
        this.loader = deps.loader();
        this.support = new SignSupport(sessions, loader, deps.agents(), audit, deps.transactions(), clock, deps.tenants());
        this.stored = new StoredArtifacts(records, crypto, storage);
        this.closing = new SessionClosing(sessions, audit, clock);
        this.authz = deps.authz();
    }

    // ------------------------------------------------------------------ 발급

    @UseCaseEntry(Action.SIGN_SESSION_ISSUE)
    public IssueOutcome issue(Caller caller, DisclosureId id, SignatureChannel channel) {
        TenantId tenant = caller.tenant();
        Objects.requireNonNull(channel, "channel");
        if (channel != SignatureChannel.TOUCH_PAD && channel != SignatureChannel.REMOTE_LINK && channel != SignatureChannel.PAPER_SCAN) {
            throw new IllegalArgumentException("customer sessions are TOUCH_PAD, REMOTE_LINK or PAPER_SCAN, not " + channel);
        }
        record Issued(IssueOutcome outcome, SignToken tokenOrNull, Actor agent) {
        }
        Issued issued = runner.inTransaction(caller, "SIGN_SESSION_ISSUE", id.toString(), attempt -> {
            Actor agent = attempt.granted(authz.require(caller, Action.SIGN_SESSION_ISSUE, Target.disclosure(id)));
            Loaded l = loader.load(tenant, id);
            Disclosure d = l.disclosure();
            DisclosureStateTable.require(d.status(), DisclosureCommand.SIGN);
            EffectiveRule rule = l.rule();
            Instant now = clock.instant();
            List<SignRejection> rejections = new ArrayList<>();
            if (!support.assigned(agent, d)) {
                rejections.add(SignRejection.AGENT_NOT_ASSIGNED);
            }
            if (!SignSupport.channelEnabled(rule, channel)) {
                rejections.add(SignRejection.CHANNEL_DISABLED);
            }
            if (!SignSupport.inSignerSet(rule, SignerRole.CUSTOMER)) {
                rejections.add(SignRejection.NOT_IN_SIGNER_SET);
            }
            if (SignSupport.signed(d, SignerRole.CUSTOMER)) {
                rejections.add(SignRejection.ALREADY_SIGNED);
            }
            if (SignSupport.outOfOrder(rule, d, SignerRole.CUSTOMER)) {
                rejections.add(SignRejection.ORDER_VIOLATION);
            }
            if (d.deadline().orElseThrow().passed(now)) {
                rejections.add(SignRejection.DEADLINE_PASSED);
            }
            if (!rejections.isEmpty()) {
                support.reject(agent, d, "SIGN_SESSION_ISSUE", rejections, SignSupport.JSON.createObjectNode().put("channel", channel.name()));
                return new Issued(new IssueOutcome(id, rejections, Optional.empty(), Optional.empty(), Optional.empty(), false), null, agent);
            }
            List<String> closed = closing.closeOpen(agent, id, SessionEvent.REISSUE, now).stream().map(UUID::toString).toList();
            SignToken token = SignToken.issue(tenant, tokens);
            Duration ttl = channel == SignatureChannel.REMOTE_LINK ? Duration.ofHours(rule.remoteLinkTtlHours())
                    : Duration.ofMinutes(rule.sessionTtlMinutes(channel));
            Instant expires = SessionWindow.expiresAt(now, ttl, d.deadline().orElseThrow().lastInstant());
            SealStamp seal = d.sealStamp().orElseThrow();
            SignSession s = SignSession.issued(UUID.randomUUID(), id, channel, token.hash(), agent.subject(), now, expires, seal.canonicalHash(),
                    seal.pdfHash());
            sessions.insert(s);
            ObjectNode detail = SignSupport.JSON.createObjectNode().put("sessionId", s.sessionId().toString()).put("channel", channel.name())
                    .put("signerRole", SignerRole.CUSTOMER.name()).put("expiresAt", expires.toString());
            ArrayNode reissued = detail.putArray("closedSessions");
            closed.forEach(reissued::add);
            support.record(agent, AuditAction.SIGN_SESSION_ISSUE, id, detail);
            boolean remote = channel == SignatureChannel.REMOTE_LINK;
            return new Issued(new IssueOutcome(id, List.of(), Optional.of(s.sessionId()), remote ? Optional.empty() : Optional.of(token),
                    Optional.of(expires), false), token, agent);
        });
        IssueOutcome o = issued.outcome();
        if (!o.issued() || channel != SignatureChannel.REMOTE_LINK) {
            return o;
        }
        boolean sent = send(caller, issued.agent(), id, o.sessionId().orElseThrow(), issued.tokenOrNull());
        return new IssueOutcome(id, List.of(), o.sessionId(), Optional.empty(), o.expiresAt(), sent);
    }

    /**
     * 커밋 뒤 원격 링크 발송: 번호는 {@code customer_ref.phone_enc}에서만(감사 {@code CUSTOMER_PHONE_READ}, 목적 REMOTE_LINK, 참조 = 세션 ID). 발송 시각을
     * 세션에 1회 기록한다(대리 서명 탐지의 발송→서명 지표). 번호가 없으면 보내지 않고 사실만 남긴다(대면 서명 전용 고객).
     */
    private boolean send(Caller caller, Actor agent, DisclosureId id, UUID sessionId, SignToken token) {
        TenantId tenant = caller.tenant();
        return runner.inTransaction(caller, "SIGN_SESSION_SEND", id.toString(), attempt -> {
            attempt.granted(agent);                                         // 발급에서 인가된 행위자(같은 호출)
            Disclosure d = loader.load(tenant, id).disclosure();
            SignSession s = sessions.lock(sessionId).orElseThrow();
            if (s.state().status() != SessionStatus.OPEN || s.sentAt().isPresent()) {
                return false;
            }
            Instant now = clock.instant();
            Optional<Customer> customer = customers.find(d.customerRef());
            Optional<Sensitive<PhoneNumber>> phone = customer.flatMap(Customer::phone);
            customer.ifPresent(c -> audit.append(new AuditEntry(now, agent.subject(), agent.role(), AuditAction.CUSTOMER_PHONE_READ,
                    SealService.CUSTOMER_TARGET, d.customerRef().value(), SignSupport.JSON.createObjectNode()
                    .put("purpose", NotificationPurpose.REMOTE_LINK.name()).put("reference", sessionId.toString()).put("keyId", c.keyId()))));
            ObjectNode detail = SignSupport.JSON.createObjectNode().put("sessionId", sessionId.toString());
            if (phone.isEmpty()) {
                support.record(agent, AuditAction.SIGN_SESSION_SEND, id, detail.put("sent", false).put("reason", "NO_PHONE"));
                return false;
            }
            notify.sendSignLink(phone.get(), token);
            sessions.update(s.sent(now));
            support.record(agent, AuditAction.SIGN_SESSION_SEND, id, detail.put("sent", true).put("sentAt", now.toString()));
            return true;
        });
    }

    // ------------------------------------------------------------------ 토큰 경로

    /** 서명 대상 봉인 PDF(복호화·해시 대조, 감사 {@code ARTIFACT_VIEW} 사유 SIGN). */
    @UseCaseEntry(Action.SIGN_OPEN)
    public byte[] open(String rawToken) {
        SignToken token = SignSupport.parse(rawToken);
        TenantId tenant = token.tenant();
        Object result = runner.inTransaction(SignSupport.anonymous(token), "SIGN_SESSION_OPEN", null, attempt -> {
            Access a = support.access(tenant, token, clock.instant());
            if (!(a instanceof Access.Granted g)) {
                return a;
            }
            Actor customer = attempt.granted(authz.require(SignSupport.customerCaller(token, g.session()), Action.SIGN_OPEN,
                    new Target.Session(g.session().sessionId())));
            StoredArtifacts.Read<ArtifactRecord> pdf = stored.artifact(tenant, g.session().disclosureId(), ArtifactKind.PDF);
            audit.append(new AuditEntry(clock.instant(), customer.subject(), customer.role(), AuditAction.ARTIFACT_VIEW, SealService.ARTIFACT_TARGET,
                    pdf.record().storageKey(), SignSupport.JSON.createObjectNode().put("disclosureId", g.session().disclosureId().toString())
                    .put("kind", ArtifactKind.PDF.name()).put("sha256", pdf.record().sha256().hex()).put("reason", "SIGN")
                    .put("sessionId", g.session().sessionId().toString())));
            return pdf.plaintext();
        });
        if (result instanceof Access.Denied denied) {
            throw support.deny(tenant, "OPEN", denied);
        }
        return (byte[]) result;
    }

    /** 열람 증거 1회 기록(서버 시각). 끝까지 스크롤했으면 SCROLL_COMPLETE 통과. 이미 기록됐으면 그대로 둔다. */
    @UseCaseEntry(Action.SIGN_VIEW_RECORD)
    public void recordView(String rawToken, boolean scrollComplete, int viewSeconds) {
        SignToken token = SignSupport.parse(rawToken);
        TenantId tenant = token.tenant();
        Access result = runner.inTransaction(SignSupport.anonymous(token), "SIGN_SESSION_VIEW", null, attempt -> {
            Instant now = clock.instant();
            Access a = support.access(tenant, token, now);
            if (!(a instanceof Access.Granted g)) {
                return a;
            }
            attempt.granted(authz.require(SignSupport.customerCaller(token, g.session()), Action.SIGN_VIEW_RECORD,
                    new Target.Session(g.session().sessionId())));
            if (g.session().view().isPresent()) {
                return a;
            }
            SignSession s = g.session().viewed(new ViewEvidence(now, scrollComplete, viewSeconds));
            if (scrollComplete) {
                s = s.with(s.state().passIdentity(IdentityMethod.SCROLL_COMPLETE), now);
            }
            sessions.update(s);
            support.record(SignSupport.customer(s), AuditAction.SIGN_SESSION_VIEW, s.disclosureId(), SignSupport.JSON.createObjectNode()
                    .put("sessionId", s.sessionId().toString()).put("scrollComplete", scrollComplete).put("viewSeconds", viewSeconds));
            return a;
        });
        if (result instanceof Access.Denied denied) {
            throw support.deny(tenant, "VIEW", denied);
        }
    }

    /**
     * 고객 본인확인: 토큰 제시(LINK_POSSESSION)와 입력값 수단(BIRTH_DATE)을 룰이 요구할 때만 확인한다. 생년월일 불일치는 실패 1회이고 한도에 닿으면 세션
     * 취소 + 플래그. 입력값은 이 메서드 밖으로 나가지 않는다.
     */
    @UseCaseEntry(Action.SIGN_VERIFY_IDENTITY)
    public IdentityOutcome verify(String rawToken, IdentityInputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        SignToken token = SignSupport.parse(rawToken);
        TenantId tenant = token.tenant();
        Object result = runner.inTransaction(SignSupport.anonymous(token), "SIGN_IDENTITY_CHECK", null, attempt -> {
            Instant now = clock.instant();
            Access a = support.access(tenant, token, now);
            if (!(a instanceof Access.Granted g)) {
                return a;
            }
            attempt.granted(authz.require(SignSupport.customerCaller(token, g.session()), Action.SIGN_VERIFY_IDENTITY,
                    new Target.Session(g.session().sessionId())));
            SignSession s = g.session();
            Disclosure d = g.loaded().disclosure();
            EffectiveRule rule = g.loaded().rule();
            List<IdentityMethod> required = rule.identityMethods(s.channel());
            List<IdentityResult> results = new ArrayList<>();
            SignSessionState state = s.state();
            if (required.contains(IdentityMethod.LINK_POSSESSION) && !state.identityPassed().contains(IdentityMethod.LINK_POSSESSION)) {
                state = state.passIdentity(IdentityMethod.LINK_POSSESSION);
                results.add(new IdentityResult(IdentityMethod.LINK_POSSESSION, true, now));
            }
            if (required.contains(IdentityMethod.BIRTH_DATE) && !state.identityPassed().contains(IdentityMethod.BIRTH_DATE)
                    && inputs.birthDateInput().isPresent()) {
                Optional<Customer> customer = customers.find(d.customerRef());
                customer.ifPresent(c -> audit.append(new AuditEntry(now, SignSupport.customer(s).subject(), "CUSTOMER", AuditAction.CUSTOMER_VIEW,
                        SealService.CUSTOMER_TARGET, d.customerRef().value(), SignSupport.JSON.createObjectNode().put("reason", "IDENTITY_CHECK")
                        .put("disclosureId", d.id().toString()).put("sessionId", s.sessionId().toString()).put("keyId", c.keyId()))));
                boolean matched = customer.flatMap(Customer::birthDate)
                        .map(stored -> BirthDate.matches(stored, inputs.birthDateInput().get())).orElse(false);
                if (matched) {
                    state = state.passIdentity(IdentityMethod.BIRTH_DATE);
                } else {
                    state = state.failIdentity(rule.identityCheckMaxFailures());
                }
                results.add(new IdentityResult(IdentityMethod.BIRTH_DATE, matched, now));
            }
            return finishIdentity(SignSupport.customer(s), s, d, state, results, required, now);
        });
        if (result instanceof Access.Denied denied) {
            throw support.deny(tenant, "VERIFY", denied);
        }
        return (IdentityOutcome) result;
    }

    /** 설계사 대면 확인(AGENT_FACE_TO_FACE): 담당 설계사가 자기 계정으로 기록한다. 담당이 아니면 업무 거부(고객 실패 횟수가 아니다). */
    @UseCaseEntry(Action.FACE_TO_FACE_CONFIRM)
    public IdentityOutcome confirmFaceToFace(Caller caller, String rawToken) {
        Objects.requireNonNull(caller, "caller");
        SignToken token = SignSupport.parse(rawToken);
        SignSupport.sameTenant(caller, token);
        TenantId tenant = token.tenant();
        Object result = runner.inTransaction(caller, "SIGN_IDENTITY_CHECK", null, attempt -> {
            Instant now = clock.instant();
            Access a = support.access(tenant, token, now);
            if (!(a instanceof Access.Granted g)) {
                return a;
            }
            Actor agent = attempt.granted(authz.require(caller, Action.FACE_TO_FACE_CONFIRM, new Target.Session(g.session().sessionId())));
            SignSession s = g.session();
            Disclosure d = g.loaded().disclosure();
            List<IdentityMethod> required = g.loaded().rule().identityMethods(s.channel());
            if (!support.assigned(agent, d)) {
                support.reject(agent, d, "SIGN_IDENTITY_CHECK", List.of(SignRejection.AGENT_NOT_ASSIGNED),
                        SignSupport.JSON.createObjectNode().put("sessionId", s.sessionId().toString()));
                return new IdentityOutcome(s.sessionId(), List.of(), IdentityPolicy.missing(required, s.state().identityPassed()),
                        s.state().identityFailures(), false, List.of(SignRejection.AGENT_NOT_ASSIGNED));
            }
            List<IdentityResult> results = new ArrayList<>();
            SignSessionState state = s.state();
            if (required.contains(IdentityMethod.AGENT_FACE_TO_FACE) && !state.identityPassed().contains(IdentityMethod.AGENT_FACE_TO_FACE)) {
                state = state.passIdentity(IdentityMethod.AGENT_FACE_TO_FACE);
                results.add(new IdentityResult(IdentityMethod.AGENT_FACE_TO_FACE, true, now));
            }
            return finishIdentity(agent, s, d, state, results, required, now);
        });
        if (result instanceof Access.Denied denied) {
            throw support.deny(tenant, "FACE_TO_FACE", denied);
        }
        return (IdentityOutcome) result;
    }

    private IdentityOutcome finishIdentity(Actor actor, SignSession s, Disclosure d, SignSessionState state, List<IdentityResult> results,
                                           List<IdentityMethod> required, Instant now) {
        boolean revoked = state.status() == SessionStatus.REVOKED;
        if (!results.isEmpty()) {
            sessions.update(s.with(state, now));
            ObjectNode detail = SignSupport.JSON.createObjectNode().put("sessionId", s.sessionId().toString())
                    .put("failures", state.identityFailures()).put("revoked", revoked);
            ArrayNode rows = detail.putArray("results");
            results.forEach(r -> rows.addObject().put("type", r.method().name()).put("result", r.passed() ? "PASS" : "FAIL"));
            support.record(actor, AuditAction.SIGN_IDENTITY_CHECK, d.id(), detail);
            if (revoked) {
                DisclosureFlagPort.RaisedFlag flag = flags.raise(DisclosureFlagPort.Type.IDENTITY_FAILED, "HIGH", d.id(), SignSupport.SESSION_TARGET,
                        s.sessionId().toString(), now);
                support.record(actor, AuditAction.FLAG_RAISE, d.id(), SignSupport.JSON.createObjectNode().put("flagId", flag.flagId().toString())
                        .put("type", DisclosureFlagPort.Type.IDENTITY_FAILED.name()).put("created", flag.created())
                        .put("sessionId", s.sessionId().toString()).put("failures", state.identityFailures()));
            }
        }
        return new IdentityOutcome(s.sessionId(), results, revoked ? List.of() : IdentityPolicy.missing(required, state.identityPassed()),
                state.identityFailures(), revoked, List.of());
    }
}

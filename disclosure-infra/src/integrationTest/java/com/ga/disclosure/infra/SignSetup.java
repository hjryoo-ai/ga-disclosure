package com.ga.disclosure.infra;

import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.pii.BirthDate;
import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.infra.persistence.SignSessionRepository;
import com.ga.disclosure.infra.persistence.SignatureRepository;
import com.ga.disclosure.seal.renderer.SignedPdfAppender;
import com.ga.disclosure.sign.token.SignToken;
import com.ga.disclosure.sign.token.TokenSource;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.customer.CustomerRefService;
import com.ga.disclosure.workflow.customer.NewCustomer;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.workflow.disclosure.SealService;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.disclosure.SignSessionService;
import com.ga.disclosure.workflow.sign.DeviceInfo;
import com.ga.disclosure.workflow.sign.NotifyPort;
import com.ga.disclosure.workflow.sign.SignatureCapture;
import com.ga.disclosure.workflow.sign.StoredSignature;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 4 통합 테스트 조립: {@link SealSetup}(봉인까지 실제 어댑터) 위에 서명 세션·서명 저장소({@link SignSessionRepository}·
 * {@link SignatureRepository})와 서명 유스케이스를 만든다. 시계는 앞으로만 움직이는 {@link MovableClock}(봉인 1시간 뒤에서 시작), 토큰 난수는 시드 고정
 * (센티널 토큰 스캔이 원문을 안다), 통지는 받은 토큰만 기억한다. 서명할 고객은 연락처·생년월일(허구 센티널)이 있는 별도 고객이다.
 */
final class SignSetup implements AutoCloseable {

    /** 허구 센티널 생년월일(평문 스캔이 찾는 값). */
    static final String BIRTH = "1971-03-29";
    static final String BIRTH_COMPACT = "19710329";
    static final String PHONE = "010-5550-0329";
    static final Actor AGENT = WorkflowSetup.AGENT;
    static final Actor MANAGER = WorkflowSetup.MANAGER;
    static final Actor STRANGER = new Actor("agent-2@test", "AGENT");
    /** 봉인 시각(SealSetup 시계) = 2026-09-23T01:00Z, 서명 기한 끝 = 2026-09-30 23:59:59.999999 KST. */
    static final Instant DEADLINE_END = Instant.parse("2026-09-30T14:59:59.999999Z");

    final SealSetup s;
    final WorkflowSetup w;
    final MovableClock clock = new MovableClock(Instant.parse("2026-09-23T02:00:00Z"), Governance.SEOUL);
    final SeededTokens tokens = new SeededTokens("sign-setup");
    final CapturingNotify notify = new CapturingNotify();
    final SignSessionRepository sessions;
    final SignatureRepository signatures;
    final SignSessionService sessionService;
    final SignService signService;
    final CustomerRef signer;

    SignSetup() {
        this(new SealSetup());
    }

    SignSetup(SealSetup s) {
        this.s = s;
        this.w = s.w;
        this.sessions = new SignSessionRepository(w.gateway);
        this.signatures = new SignatureRepository(w.gateway);
        this.sessionService = new SignSessionService(w.deps(clock), sessions, s.recordPort, s.cipher, s.store, tokens, notify);
        this.signService = signServiceWith(s.recordPort, s.store);
        this.signer = new CustomerRefService(w.vault, w.audit, w.tx, w.clock).register(w.tenant, CatalogCustomerSetup.OPERATOR,
                new NewCustomer(CustomerName.of("가상서명고객"), PhoneNumber.of(PHONE), BirthDate.of(java.time.LocalDate.parse(BIRTH))));
        w.db.seed(w.tenant.value(), c -> com.ga.disclosure.infra.testing.SeedData.identityLink(c, w.tenant.value(), STRANGER.subject(), "AGENT-2",
                "AGENT"));
    }

    SignService signServiceWith(com.ga.disclosure.workflow.artifact.DocumentRecordStore records, com.ga.disclosure.workflow.artifact.ArtifactStore store) {
        return new SignService(w.deps(clock), sessions, signatures, records, s.cipher, store, new SignedPdfAppender(),
                SealService.DEFAULT_TRANSACTION_TIMEOUT);
    }

    /** 연락처·생년월일(같은 센티널)이 있는 고객을 하나 더 등록한다(대리 서명 탐지 — 서로 다른 고객). */
    CustomerRef newSigner(String name) {
        return new CustomerRefService(w.vault, w.audit, w.tx, w.clock).register(w.tenant, CatalogCustomerSetup.OPERATOR,
                new NewCustomer(CustomerName.of(name), PhoneNumber.of(PHONE), BirthDate.of(java.time.LocalDate.parse(BIRTH))));
    }

    /** 서명할 고객의 확인서를 봉인까지(SealSetup 시계). */
    DisclosureId sealed() {
        return sealedFor(signer);
    }

    DisclosureId sealedFor(CustomerRef who) {
        DisclosureId id = w.reasoned(who);
        SealService.Outcome o = s.seal.seal(w.tenant, AGENT, id);
        if (!o.sealed()) {
            throw new IllegalStateException("seal rejected: " + o.rejections());
        }
        return id;
    }

    /** 담당 설계사가 세션을 발급한다(TOUCH_PAD·PAPER_SCAN은 토큰 원문, REMOTE_LINK는 통지가 받은 토큰). */
    String issue(DisclosureId id, SignatureChannel channel) {
        SignSessionService.IssueOutcome o = sessionService.issue(w.tenant, AGENT, id, channel);
        if (!o.issued()) {
            throw new IllegalStateException("session rejected: " + o.rejections());
        }
        return channel == SignatureChannel.REMOTE_LINK ? notify.last().reveal() : o.token().orElseThrow().reveal();
    }

    /** TOUCH_PAD 본인확인 준비: 끝까지 열람 + 설계사 대면 확인. */
    void readyTouchPad(String token) {
        sessionService.recordView(token, true, 42);
        sessionService.confirmFaceToFace(token, AGENT);
    }

    SignService.Outcome customerSignsOnTouchPad(DisclosureId id) {
        String token = issue(id, SignatureChannel.TOUCH_PAD);
        readyTouchPad(token);
        return signService.capture(token, capture("tablet-1", "10.0.0.7"));
    }

    SignService.Outcome agentSigns(DisclosureId id) {
        return signService.agentSign(w.tenant, AGENT, id, capture("agent-device", null));
    }

    SignService.Outcome managerConfirms(DisclosureId id) {
        return signService.managerConfirm(w.tenant, MANAGER, id, allFlags(id));
    }

    /** 확인서에 걸린 플래그 전부(관리자 사유 확인 대상). */
    Set<UUID> allFlags(DisclosureId id) {
        Set<UUID> out = new HashSet<>();
        w.in(() -> w.flags.allFor(id)).forEach(f -> out.add(f.flagId()));
        return out;
    }

    List<DisclosureFlagPort.OpenFlag> openFlags(DisclosureId id) {
        return w.in(() -> w.flags.openFor(id));
    }

    List<StoredSignature> signaturesOf(DisclosureId id) {
        return w.in(() -> signatures.signatures(id));
    }

    String status(DisclosureId id) {
        return s.text("SELECT status FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", w.tenant.value(), id.value());
    }

    static SignatureCapture capture(String fingerprint, String ip) {
        return new SignatureCapture(STROKES.getBytes(StandardCharsets.UTF_8), png(), new DeviceInfo(fingerprint, "TestAgent/1.0"), ip);
    }

    /** 좌표·시각 시퀀스(허구). 평문 스캔은 좌표 문자열 {@code "x":131}을 찾는다. */
    static final String STROKES = "[[{\"x\":131,\"y\":57,\"t\":0},{\"x\":140,\"y\":61,\"t\":16},{\"x\":152,\"y\":66,\"t\":33}],"
            + "[{\"x\":160,\"y\":40,\"t\":120},{\"x\":161,\"y\":72,\"t\":150}]]";

    private static byte[] cachedPng;

    /** 작은 서명 PNG(검은 획 하나). */
    static synchronized byte[] png() {
        if (cachedPng == null) {
            BufferedImage img = new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 120; x++) {
                for (int y = 0; y < 40; y++) {
                    img.setRGB(x, y, (y == 20 + (x % 7) - 3) ? 0x000000 : 0xFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                ImageIO.write(img, "png", out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            cachedPng = out.toByteArray();
        }
        return cachedPng.clone();
    }

    @Override
    public void close() {
        s.close();
    }

    /** 시드 고정 토큰 난수: SHA-256(seed ‖ 순번). */
    static final class SeededTokens implements TokenSource {
        private final String seed;
        private final AtomicLong n = new AtomicLong();
        final List<String> issued = new ArrayList<>();

        SeededTokens(String seed) {
            this.seed = seed;
        }

        @Override
        public byte[] next256Bits() {
            try {
                return MessageDigest.getInstance("SHA-256").digest((seed + "|" + n.incrementAndGet()).getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** 받은 토큰만 기억하는 통지(번호는 기억하지 않는다). */
    static final class CapturingNotify implements NotifyPort {
        final List<SignToken> sent = new ArrayList<>();

        @Override
        public void sendSignLink(Sensitive<PhoneNumber> to, SignToken token) {
            sent.add(token);
        }

        SignToken last() {
            return sent.getLast();
        }
    }
}

package com.ga.disclosure.workflow.sign;

import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.Sha256;
import com.ga.disclosure.sign.session.SessionStatus;
import com.ga.disclosure.sign.session.SignSessionState;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 고객 서명 세션 1행(V8 {@code sign_session}, 4 계획 §2). 역할은 언제나 CUSTOMER다(설계사·관리자는 SSO로 직접 서명, 승인 Q3). 토큰은 해시만 있다.
 * (6A 승인 Q3, V12) 원격 링크 세션은 토큰 없이 발급되고 통지 디스패처가 보낼 때 토큰 해시와 발송 시각을 함께 1회 기록한다({@link #sent}) — 발송 전에는
 * 토큰이 없으므로 이 세션에 닿는 링크도 없다.
 * 발급 때 두 해시를 고정한다(GD101) — 서명은 이 해시에만 붙는다. 가변 부분({@link SignSessionState}·열람 증거·발송·사용·취소 시각)은
 * {@link #with}로만 바꾸고 상태 규칙은 상태표가 지킨다.
 */
public record SignSession(UUID sessionId, DisclosureId disclosureId, SignatureChannel channel, Sha256 tokenHashOrNull, String issuedBy, Instant issuedAt,
                          Instant expiresAt, Sha256 signedDocHash, Sha256 signedPdfHash, SignSessionState state, ViewEvidence viewOrNull,
                          Instant sentAtOrNull, Instant usedAtOrNull, Instant revokedAtOrNull) {

    public SignSession {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(disclosureId, "disclosureId");
        Objects.requireNonNull(channel, "channel");
        if (tokenHashOrNull == null && (channel != SignatureChannel.REMOTE_LINK || sentAtOrNull != null)) {
            throw new IllegalArgumentException("only an unsent REMOTE_LINK session has no token yet (V12 ck_sign_session_token_at_send)");
        }
        Objects.requireNonNull(issuedBy, "issuedBy");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(signedDocHash, "signedDocHash");
        Objects.requireNonNull(signedPdfHash, "signedPdfHash");
        Objects.requireNonNull(state, "state");
        if (channel == SignatureChannel.SSO || channel == SignatureChannel.CERTIFIED_ESIGN) {
            throw new IllegalArgumentException("customer sessions are TOUCH_PAD, REMOTE_LINK or PAPER_SCAN, not " + channel);
        }
        if ((state.status() == SessionStatus.USED) != (usedAtOrNull != null)
                || (state.status() == SessionStatus.REVOKED) != (revokedAtOrNull != null)
                || state.viewed() != (viewOrNull != null)) {
            throw new IllegalArgumentException("session timestamps and view evidence must match its state");
        }
    }

    /** 발급 직후(OPEN, 실패 0, 통과 없음, 열람·발송 없음) — 설계사 기기에 토큰을 넘기는 TOUCH_PAD·PAPER_SCAN. */
    public static SignSession issued(UUID sessionId, DisclosureId disclosureId, SignatureChannel channel, Sha256 tokenHash, String issuedBy,
                                     Instant issuedAt, Instant expiresAt, Sha256 signedDocHash, Sha256 signedPdfHash) {
        return new SignSession(sessionId, disclosureId, channel, Objects.requireNonNull(tokenHash, "tokenHash"), issuedBy, issuedAt, expiresAt,
                signedDocHash, signedPdfHash, SignSessionState.issued(), null, null, null, null);
    }

    /** 원격 링크 발급 직후 — 토큰은 아직 없다(보낼 때 {@link #sent}). */
    public static SignSession issuedForLink(UUID sessionId, DisclosureId disclosureId, String issuedBy, Instant issuedAt, Instant expiresAt,
                                            Sha256 signedDocHash, Sha256 signedPdfHash) {
        return new SignSession(sessionId, disclosureId, SignatureChannel.REMOTE_LINK, null, issuedBy, issuedAt, expiresAt, signedDocHash,
                signedPdfHash, SignSessionState.issued(), null, null, null, null);
    }

    /** 새 상태(상태표를 거친 값)와 그 사건 시각. USED·REVOKED로 옮길 때 사용·취소 시각을 채운다. */
    public SignSession with(SignSessionState next, Instant at) {
        Instant used = next.status() == SessionStatus.USED && usedAtOrNull == null ? at : usedAtOrNull;
        Instant revoked = next.status() == SessionStatus.REVOKED && revokedAtOrNull == null ? at : revokedAtOrNull;
        return new SignSession(sessionId, disclosureId, channel, tokenHashOrNull, issuedBy, issuedAt, expiresAt, signedDocHash, signedPdfHash, next,
                viewOrNull, sentAtOrNull, used, revoked);
    }

    /** 열람 증거 1회 기록(상태표 OPEN_VIEW). */
    public SignSession viewed(ViewEvidence view) {
        if (viewOrNull != null) {
            throw new IllegalStateException("view evidence of session " + sessionId + " is recorded once");
        }
        return new SignSession(sessionId, disclosureId, channel, tokenHashOrNull, issuedBy, issuedAt, expiresAt, signedDocHash, signedPdfHash,
                state.view(), Objects.requireNonNull(view, "view"), sentAtOrNull, usedAtOrNull, revokedAtOrNull);
    }

    /** 원격 링크 발송: 토큰 해시와 발송 시각을 함께 1회 기록(REMOTE_LINK·OPEN만, V12 GD123). */
    public SignSession sent(Instant at, Sha256 tokenHash) {
        if (channel != SignatureChannel.REMOTE_LINK || sentAtOrNull != null || tokenHashOrNull != null || state.status() != SessionStatus.OPEN) {
            throw new IllegalStateException("a link is sent once, for an open REMOTE_LINK session without a token");
        }
        return new SignSession(sessionId, disclosureId, channel, Objects.requireNonNull(tokenHash, "tokenHash"), issuedBy, issuedAt, expiresAt,
                signedDocHash, signedPdfHash, state, viewOrNull, Objects.requireNonNull(at, "at"), usedAtOrNull, revokedAtOrNull);
    }

    public Optional<Sha256> tokenHash() {
        return Optional.ofNullable(tokenHashOrNull);
    }

    public Optional<ViewEvidence> view() {
        return Optional.ofNullable(viewOrNull);
    }

    public Optional<Instant> sentAt() {
        return Optional.ofNullable(sentAtOrNull);
    }
}

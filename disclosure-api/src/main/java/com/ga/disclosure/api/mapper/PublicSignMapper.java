package com.ga.disclosure.api.mapper;

import com.ga.disclosure.api.dto.ClientContext;
import com.ga.disclosure.api.dto.PublicCaptureReceipt;
import com.ga.disclosure.api.dto.PublicCaptureRequest;
import com.ga.disclosure.api.dto.PublicIdentityResult;
import com.ga.disclosure.api.dto.PublicStatusView;
import com.ga.disclosure.api.dto.PublicVerifyRequest;
import com.ga.disclosure.api.dto.PublicViewRequest;
import com.ga.disclosure.api.error.Problem;
import com.ga.disclosure.api.error.RejectedOutcomeException;
import com.ga.disclosure.workflow.RejectionCategory;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.disclosure.SignSessionService;
import com.ga.disclosure.workflow.sign.DeviceInfo;
import com.ga.disclosure.workflow.sign.IdentityInputs;
import com.ga.disclosure.workflow.sign.SignatureCapture;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * 공개 서명 경로의 변환(6A 계획 §5.1). 형식이 틀린 입력은 예외 — 공개 경로에서는 거부(404 같은 바이트)가 된다(유효 토큰 보유자에게도 형식 오류를 따로
 * 알리지 않는다). 업무 거부만 422.
 */
public final class PublicSignMapper {

    private PublicSignMapper() {
    }

    public static boolean scrollComplete(PublicViewRequest r) {
        return r != null && Boolean.TRUE.equals(r.scrollComplete());
    }

    public static int viewSeconds(PublicViewRequest r) {
        int seconds = r == null || r.viewSeconds() == null ? 0 : r.viewSeconds();
        if (seconds < 0) {
            throw new IllegalArgumentException("viewSeconds");
        }
        return seconds;
    }

    public static IdentityInputs inputs(PublicVerifyRequest r) {
        return r == null || r.birthDate() == null ? IdentityInputs.none() : IdentityInputs.birthDate(r.birthDate());
    }

    public static SignatureCapture capture(PublicCaptureRequest r, ClientContext client) {
        if (r == null || r.strokes() == null || r.imagePngBase64() == null) {
            throw new IllegalArgumentException("capture");
        }
        return new SignatureCapture(r.strokes().toString().getBytes(StandardCharsets.UTF_8), Base64.getDecoder().decode(r.imagePngBase64()),
                new DeviceInfo(r.deviceFingerprint(), client.userAgentOrNull()), client.ipOrNull());
    }

    public static PublicIdentityResult identity(SignSessionService.IdentityOutcome o) {
        if (!o.rejections().isEmpty()) {
            throw new RejectedOutcomeException(RejectionCategory.INVALID, o.rejections().stream().map(r -> new Problem.Rejection(r.name(), null)).toList());
        }
        return new PublicIdentityResult(o.missing().isEmpty(), o.missing().stream().map(Enum::name).toList());
    }

    public static PublicCaptureReceipt captured(SignService.Outcome o) {
        if (!o.rejections().isEmpty()) {
            throw new RejectedOutcomeException(RejectionCategory.INVALID, o.rejections().stream().map(r -> new Problem.Rejection(r.name(), null)).toList());
        }
        return new PublicCaptureReceipt(o.signatureId().map(UUID::toString).orElse(null));
    }

    public static PublicStatusView status(SignSessionService.StatusView v) {
        return new PublicStatusView("OPEN", v.identityRequired().stream().map(Enum::name).toList(), v.identityPassed().stream().map(Enum::name).toList(),
                v.viewed(), v.expiresAt().toString());
    }
}

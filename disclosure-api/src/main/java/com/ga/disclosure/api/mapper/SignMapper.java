package com.ga.disclosure.api.mapper;

import com.ga.disclosure.api.dto.AgentSignatureRequest;
import com.ga.disclosure.api.dto.ClientContext;
import com.ga.disclosure.api.dto.IdentityReceipt;
import com.ga.disclosure.api.dto.ManagerConfirmationRequest;
import com.ga.disclosure.api.dto.PaperScanRequest;
import com.ga.disclosure.api.dto.SessionIssueReceipt;
import com.ga.disclosure.api.dto.SignReceipt;
import com.ga.disclosure.api.error.MalformedRequestException;
import com.ga.disclosure.api.error.Problem;
import com.ga.disclosure.api.error.RejectedOutcomeException;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.workflow.RejectionCategory;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.disclosure.SignSessionService;
import com.ga.disclosure.workflow.sign.DeviceInfo;
import com.ga.disclosure.workflow.sign.PaperScan;
import com.ga.disclosure.workflow.sign.SignRejection;
import com.ga.disclosure.workflow.sign.SignatureCapture;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 서명 경로의 변환(6A 계획 §4.1). 거부는 {@link SignRejection}의 범주로(409·422). 토큰은 본문으로만 받고 응답에는 현장 기기 토큰만 싣는다. */
public final class SignMapper {

    private SignMapper() {
    }

    public static SignatureChannel channel(String channel) {
        try {
            return SignatureChannel.valueOf(CommandMapper.required("channel", channel));
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException("channel");
        }
    }

    public static String token(String token) {
        return CommandMapper.required("token", token);
    }

    public static PaperScan scan(PaperScanRequest r) {
        if (r == null) {
            throw new MalformedRequestException("token");
        }
        byte[] image;
        try {
            image = Base64.getDecoder().decode(CommandMapper.required("imageBase64", r.imageBase64()));
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException("imageBase64");
        }
        try {
            return new PaperScan(image, CommandMapper.required("disclosureNo", r.disclosureNo()), CommandMapper.required("hashPrefix", r.hashPrefix()));
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException("imageBase64");
        }
    }

    /** DRAWN 입력이 없으면 {@code null}(SSO_APPROVAL). */
    public static SignatureCapture capture(AgentSignatureRequest r, ClientContext client) {
        if (r == null || (r.strokes() == null && r.imagePngBase64() == null)) {
            return null;
        }
        try {
            byte[] strokes = r.strokes() == null ? null : r.strokes().toString().getBytes(StandardCharsets.UTF_8);
            byte[] image = r.imagePngBase64() == null ? null : Base64.getDecoder().decode(r.imagePngBase64());
            return new SignatureCapture(strokes, image, new DeviceInfo(r.deviceFingerprint(), client.userAgentOrNull()), client.ipOrNull());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new MalformedRequestException("imagePngBase64");
        }
    }

    public static Set<UUID> flags(ManagerConfirmationRequest r) {
        Set<UUID> out = new LinkedHashSet<>();
        for (String f : r == null || r.acknowledgedFlags() == null ? List.<String>of() : r.acknowledgedFlags()) {
            try {
                out.add(UUID.fromString(f));
            } catch (IllegalArgumentException e) {
                throw new MalformedRequestException("acknowledgedFlags");
            }
        }
        return out;
    }

    public static SessionIssueReceipt issued(SignSessionService.IssueOutcome o, SignatureChannel channel) {
        reject(o.rejections());
        return new SessionIssueReceipt(o.sessionId().orElseThrow().toString(), o.id().value().toString(), channel.name(),
                o.expiresAt().map(Object::toString).orElse(null), o.token().map(t -> t.reveal()).orElse(null),
                o.link().map(l -> l.reveal()).orElse(null));
    }

    public static IdentityReceipt identity(SignSessionService.IdentityOutcome o) {
        reject(o.rejections());
        return new IdentityReceipt(o.sessionId().toString(), o.missing().stream().map(Enum::name).toList(), o.failures(), o.revoked());
    }

    public static SignReceipt signed(SignService.Outcome o) {
        reject(o.rejections());
        return new SignReceipt(o.id().value().toString(), o.status().name(), o.signatureId().map(UUID::toString).orElse(null), o.completed(),
                o.retentionPending());
    }

    private static void reject(List<SignRejection> rejections) {
        if (!rejections.isEmpty()) {
            throw new RejectedOutcomeException(RejectionCategory.of(rejections),
                    rejections.stream().map(r -> new Problem.Rejection(r.name(), null)).toList());
        }
    }
}

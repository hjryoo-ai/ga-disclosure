package com.ga.disclosure.workflow.sign;

import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignatureMethod;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.Sha256;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 서명 레코드 1건(V8 {@code signature}, append-only): 두 귀속 해시, 고객이면 세션, 설계사·관리자면 SSO 주체, 결과만 남는 본인확인, 열람 증거, 기기·IP,
 * 관리자가 사유를 확인한 플래그, 종이 스캔 대조. 증거 객체(스트로크·이미지·스캔)는 {@code signature_evidence}에 따로 있다.
 */
public record StoredSignature(UUID signatureId, DisclosureId disclosureId, SignerRole role, String signerSubjectOrNull, SignatureChannel channel,
                              SignatureMethod method, Sha256 signedDocHash, Sha256 signedPdfHash, UUID sessionIdOrNull,
                              List<IdentityResult> identityCheck, ViewEvidence viewOrNull, DeviceInfo deviceOrNull, String ipOrNull,
                              List<UUID> acknowledgedFlags, ScanMatch scanMatchOrNull, Instant signedAt) {

    public StoredSignature {
        Objects.requireNonNull(signatureId, "signatureId");
        Objects.requireNonNull(disclosureId, "disclosureId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(signedDocHash, "signedDocHash");
        Objects.requireNonNull(signedPdfHash, "signedPdfHash");
        Objects.requireNonNull(signedAt, "signedAt");
        identityCheck = List.copyOf(identityCheck);
        acknowledgedFlags = List.copyOf(acknowledgedFlags);
        boolean customer = role == SignerRole.CUSTOMER;
        if (customer != (sessionIdOrNull != null) || customer == (signerSubjectOrNull != null) || customer == (channel == SignatureChannel.SSO)) {
            throw new IllegalArgumentException("customers sign through a session, agents and managers as SSO subjects (V8 ck_signature_who)");
        }
        if ((method == SignatureMethod.UPLOADED_SCAN) != (channel == SignatureChannel.PAPER_SCAN) || (scanMatchOrNull == null) != (channel != SignatureChannel.PAPER_SCAN)) {
            throw new IllegalArgumentException("paper scans and only they carry UPLOADED_SCAN and a scan match");
        }
        if (!acknowledgedFlags.isEmpty() && role != SignerRole.MANAGER) {
            throw new IllegalArgumentException("only a manager acknowledges flags");
        }
    }
}

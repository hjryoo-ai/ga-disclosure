package com.ga.disclosure.audit.outbox;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 계약 payload 생성(contracts/events/v1/payloads, 버전 1). 필드 이름·필수 여부는 계약 그대로이고 고객 개인정보·사유 텍스트는 싣지 않는다
 * (고객은 {@code customerRef}로만). 적재 시 스키마 검증이 이 생성기의 실수도 잡는다.
 */
public final class OutboxPayloads {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private OutboxPayloads() {
    }

    public static JsonNode disclosureCreated(UUID disclosureId, int version, String agentId, String customerRef, String groupCode,
                                             LocalDate consultDate, String templateId, int templateVersion, String issuerMode, UUID supersedesIdOrNull) {
        ObjectNode p = JSON.objectNode();
        p.put("disclosureId", disclosureId.toString());
        p.put("version", version);
        p.put("agentId", agentId);
        p.put("customerRef", customerRef);
        p.put("groupCode", groupCode);
        p.put("consultDate", consultDate.toString());
        p.putObject("template").put("templateId", templateId).put("version", templateVersion);
        p.put("issuerMode", issuerMode);
        nullable(p, "supersedesId", supersedesIdOrNull == null ? null : supersedesIdOrNull.toString());
        return p;
    }

    public static JsonNode disclosureSealed(UUID disclosureId, String disclosureNo, int version, String ruleVersionId, String gradeSnapshotIdOrNull,
                                            String canonicalHash, String pdfHash, String chainHash, long chainSeq, Instant sealedAt) {
        ObjectNode p = JSON.objectNode();
        p.put("disclosureId", disclosureId.toString());
        p.put("disclosureNo", disclosureNo);
        p.put("version", version);
        p.put("ruleVersionId", ruleVersionId);
        if (gradeSnapshotIdOrNull != null) {
            p.put("gradeSnapshotId", gradeSnapshotIdOrNull);
        }
        p.put("canonicalHash", canonicalHash);
        p.put("pdfHash", pdfHash);
        p.put("chainHash", chainHash);
        p.put("chainSeq", chainSeq);
        p.put("sealedAt", sealedAt.toString());
        return p;
    }

    public static JsonNode signatureCaptured(UUID disclosureId, String disclosureNo, String signerRole, String channel, String signedDocHash,
                                             Instant signedAt, List<String> pendingRoles) {
        ObjectNode p = JSON.objectNode();
        p.put("disclosureId", disclosureId.toString());
        p.put("disclosureNo", disclosureNo);
        p.put("signerRole", signerRole);
        p.put("channel", channel);
        p.put("signedDocHash", signedDocHash);
        p.put("signedAt", signedAt.toString());
        ArrayNode pending = p.putArray("pendingRoles");
        pendingRoles.forEach(pending::add);
        return p;
    }

    public static JsonNode disclosureCompleted(UUID disclosureId, String disclosureNo, Instant completedAt, LocalDate retentionUntil) {
        return JSON.objectNode().put("disclosureId", disclosureId.toString()).put("disclosureNo", disclosureNo)
                .put("completedAt", completedAt.toString()).put("retentionUntil", retentionUntil.toString());
    }

    /** 무효. 사유(코드·텍스트)는 싣지 않는다 — 계약 설명(자유 입력에 개인정보가 섞일 수 있다). */
    public static JsonNode disclosureVoided(UUID disclosureId, String disclosureNoOrNull, String previousStatus, Instant voidedAt) {
        ObjectNode p = JSON.objectNode();
        p.put("disclosureId", disclosureId.toString());
        nullable(p, "disclosureNo", disclosureNoOrNull);
        p.put("previousStatus", previousStatus);
        p.put("voidedAt", voidedAt.toString());
        return p;
    }

    public static JsonNode disclosureSuperseded(UUID disclosureId, String disclosureNo, UUID supersededById, int newVersion, Instant supersededAt) {
        return JSON.objectNode().put("disclosureId", disclosureId.toString()).put("disclosureNo", disclosureNo)
                .put("supersededById", supersededById.toString()).put("newVersion", newVersion).put("supersededAt", supersededAt.toString());
    }

    /** 파기(Phase 5): 식별자·번호·시각만 — 지운 값의 해시는 감사에만 남는다. */
    public static JsonNode disclosureDestroyed(UUID disclosureId, String disclosureNo, Instant destroyedAt) {
        return JSON.objectNode().put("disclosureId", disclosureId.toString()).put("disclosureNo", disclosureNo).put("destroyedAt", destroyedAt.toString());
    }

    /** 초안 폐기(6B): 식별자·시각만 — 번호는 없고(봉인 전) 지운 값의 해시는 감사에만 남는다. */
    public static JsonNode disclosureAbandoned(UUID disclosureId, Instant abandonedAt) {
        return JSON.objectNode().put("disclosureId", disclosureId.toString()).put("abandonedAt", abandonedAt.toString());
    }

    public static JsonNode policyLinked(UUID disclosureId, String disclosureNo, String policyNo, LocalDate contractDate) {
        return JSON.objectNode().put("disclosureId", disclosureId.toString()).put("disclosureNo", disclosureNo).put("policyNo", policyNo)
                .put("contractDate", contractDate.toString());
    }

    public static JsonNode complianceFlagRaised(UUID flagId, String flagType, String severity, UUID disclosureIdOrNull, String policyNoOrNull,
                                                String agentIdOrNull, Instant raisedAt) {
        ObjectNode p = JSON.objectNode();
        p.put("flagId", flagId.toString());
        p.put("flagType", flagType);
        p.put("severity", severity);
        nullable(p, "disclosureId", disclosureIdOrNull == null ? null : disclosureIdOrNull.toString());
        nullable(p, "policyNo", policyNoOrNull);
        nullable(p, "agentId", agentIdOrNull);
        p.put("raisedAt", raisedAt.toString());
        return p;
    }

    private static void nullable(ObjectNode p, String key, String value) {
        if (value == null) {
            p.putNull(key);
        } else {
            p.put(key, value);
        }
    }
}

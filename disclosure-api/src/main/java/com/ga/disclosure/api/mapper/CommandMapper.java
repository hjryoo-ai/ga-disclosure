package com.ga.disclosure.api.mapper;

import com.ga.disclosure.api.dto.CreateDisclosureRequest;
import com.ga.disclosure.api.dto.DisclosureReceipt;
import com.ga.disclosure.api.dto.Download;
import com.ga.disclosure.api.dto.ExceptionApprovalReceipt;
import com.ga.disclosure.api.dto.ItemsRequest;
import com.ga.disclosure.api.dto.AbandonRequest;
import com.ga.disclosure.api.dto.LifecycleReceipt;
import com.ga.disclosure.api.dto.LifecycleRequest;
import com.ga.disclosure.api.dto.RecommendationsRequest;
import com.ga.disclosure.api.dto.SealReceipt;
import com.ga.disclosure.api.dto.ValidationReceipt;
import com.ga.disclosure.api.error.MalformedRequestException;
import com.ga.disclosure.api.error.NotFoundException;
import com.ga.disclosure.api.error.Problem;
import com.ga.disclosure.api.error.RejectedOutcomeException;
import com.ga.disclosure.domain.disclosure.AgentReason;
import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.enums.ValidationStage;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.workflow.RejectionCategory;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.CommandResult;
import com.ga.disclosure.workflow.disclosure.ItemInput;
import com.ga.disclosure.workflow.disclosure.LifecycleReason;
import com.ga.disclosure.workflow.disclosure.LifecycleService;
import com.ga.disclosure.workflow.disclosure.Review;
import com.ga.disclosure.workflow.disclosure.SealService;
import com.ga.disclosure.workflow.verify.ReceiptExporter;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 확인서 쓰기 경로의 변환(6A 계획 §4.1): 요청 → 유스케이스 인자(형식 오류 400 {@code field}), 결과 → 닫힌 영수증 또는 범주가 붙은 거부
 * ({@link RejectedOutcomeException} — CONFLICT 409·INVALID 422). 거부 본문은 코드(와 검증 규칙 ID)만.
 */
public final class CommandMapper {

    private CommandMapper() {
    }

    // ------------------------------------------------------------------ 요청

    /** (6B) 선택 청약번호 — 형식(공백 없는 1~64자)이 틀리면 400(값은 응답에 싣지 않는다). */
    public static java.util.Optional<String> applicationNo(CreateDisclosureRequest r) {
        if (r.applicationNo() == null) {
            return java.util.Optional.empty();
        }
        if (!r.applicationNo().matches("\\S{1,64}")) {
            throw new MalformedRequestException("applicationNo");
        }
        return java.util.Optional.of(r.applicationNo());
    }

    public static CustomerRef customerRef(CreateDisclosureRequest r) {
        return parse("customerRef", () -> CustomerRef.of(required("customerRef", r == null ? null : r.customerRef())));
    }

    public static GroupCode groupCode(CreateDisclosureRequest r) {
        return parse("groupCode", () -> GroupCode.of(required("groupCode", r.groupCode())));
    }

    public static LocalDate consultDate(CreateDisclosureRequest r) {
        try {
            return LocalDate.parse(required("consultDate", r.consultDate()));
        } catch (DateTimeParseException e) {
            throw new MalformedRequestException("consultDate");
        }
    }

    public static TemplateType templateType(CreateDisclosureRequest r) {
        return parse("templateType", () -> TemplateType.valueOf(required("templateType", r.templateType())));
    }

    public static List<ItemInput> items(ItemsRequest r) {
        if (r == null || r.items() == null) {
            throw new MalformedRequestException("items");
        }
        List<ItemInput> out = new ArrayList<>();
        for (ItemsRequest.Item i : r.items()) {
            Map<String, tools.jackson.databind.JsonNode> values = i.agentValues() == null ? Map.of() : i.agentValues();
            boolean recommended = Boolean.TRUE.equals(i.recommended());
            boolean requested = Boolean.TRUE.equals(i.requestedByCustomer());
            out.add(parse("items", () -> i.productKey() != null
                    ? new ItemInput.Catalog(ProductKey.parse(i.productKey()), recommended, requested, values)
                    : new ItemInput.Temp(InsurerCode.of(required("insurerCode", i.insurerCode())), i.productName(), i.quoteDocNo(), recommended,
                    requested, values)));
        }
        return out;
    }

    public static List<AgentReason> reasons(RecommendationsRequest r) {
        if (r == null || r.reasons() == null) {
            throw new MalformedRequestException("reasons");
        }
        return r.reasons().stream().map(x -> parse("reasons", () -> new AgentReason(x.itemNo(),
                (x.codes() == null ? List.<String>of() : x.codes()).stream().map(ReasonCode::of).toList(), x.text()))).toList();
    }

    public static ValidationStage stage(String stage) {
        return parse("stage", () -> ValidationStage.valueOf(required("stage", stage)));
    }

    /** 폐기 사유 코드 — 형식은 무효 사유 코드와 같다(목록 대조는 유스케이스가 고정 룰로). */
    public static String abandonReason(AbandonRequest r) {
        String code = required("reasonCode", r == null ? null : r.reasonCode());
        return parse("reasonCode", () -> new LifecycleReason(code, null)).code();
    }

    public static LifecycleReason reason(LifecycleRequest r) {
        return parse("reasonCode", () -> new LifecycleReason(required("reasonCode", r == null ? null : r.reasonCode()), r.reasonText()));
    }

    public static ArtifactKind artifactKind(String path) {
        try {
            return ArtifactKind.valueOf(path);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException();
        }
    }

    public static String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new MalformedRequestException(field);
        }
        return value;
    }

    private static <T> T parse(String field, java.util.function.Supplier<T> parse) {
        try {
            return parse.get();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new MalformedRequestException(field);
        }
    }

    // ------------------------------------------------------------------ 결과

    public static DisclosureReceipt created(DisclosureId id) {
        return new DisclosureReceipt(id.value().toString(), "DRAFT");
    }

    public static DisclosureReceipt receipt(CommandResult r) {
        if (!r.applied()) {
            List<Problem.Rejection> rejections = new ArrayList<>();
            failed(r.results()).forEach(ruleId -> rejections.add(new Problem.Rejection(r.rejectionOrNull().name(), ruleId)));
            if (rejections.isEmpty()) {
                rejections.add(new Problem.Rejection(r.rejectionOrNull().name(), null));
            }
            throw new RejectedOutcomeException(r.rejectionOrNull().category(), rejections);
        }
        return new DisclosureReceipt(r.id().value().toString(), r.status().name());
    }

    public static ValidationReceipt validation(DisclosureId id, List<ValidationResult> results) {
        return new ValidationReceipt(id.value().toString(), results.stream().map(v -> new ValidationReceipt.Result(v.ruleId(), v.passed(),
                v.overridable(), v.subject() == null || v.subject().isNull() ? null : Sha256.of(Canonicalizer.canonicalize(v.subject())))).toList());
    }

    public static SealReceipt seal(SealService.Outcome o) {
        if (!o.rejections().isEmpty()) {
            List<String> failed = failed(o.results());
            List<Problem.Rejection> rejections = new ArrayList<>();
            for (SealService.Rejection r : o.rejections()) {
                if (r == SealService.Rejection.VALIDATION_BLOCKED && !failed.isEmpty()) {
                    failed.forEach(ruleId -> rejections.add(new Problem.Rejection(r.name(), ruleId)));
                } else {
                    rejections.add(new Problem.Rejection(r.name(), null));
                }
            }
            throw new RejectedOutcomeException(RejectionCategory.of(o.rejections()), rejections);
        }
        return new SealReceipt(o.id().value().toString(), o.status().name(), o.number().map(n -> n.value()).orElse(null), o.retentionPending());
    }

    public static LifecycleReceipt lifecycle(LifecycleService.Outcome o) {
        if (o.rejection().isPresent()) {
            throw new RejectedOutcomeException(o.rejection().get().category(), List.of(new Problem.Rejection(o.rejection().get().name(), null)));
        }
        return new LifecycleReceipt(o.id().value().toString(), o.status().name(), o.newVersion().map(v -> v.value().toString()).orElse(null));
    }

    public static ExceptionApprovalReceipt approval(Review r) {
        return new ExceptionApprovalReceipt(r.reviewId().toString(), r.disclosureId().value().toString(), r.ruleId());
    }

    public static Download artifact(ArtifactKind kind, ArtifactService.View view) {
        return switch (view) {
            case ArtifactService.View.Granted g -> new Download(g.plaintext(), switch (kind) {
                case PDF, SIGNED_PDF -> "application/pdf";
                case CANONICAL_JSON -> "application/json";
                case EVIDENCE_ZIP -> "application/zip";
            });
            case ArtifactService.View.Denied d ->
                    throw new RejectedOutcomeException(d.reason().category(), List.of(new Problem.Rejection(d.reason().name(), null)));
        };
    }

    public static Download anchorReceipt(ReceiptExporter.Result result) {
        return switch (result) {
            case ReceiptExporter.Result.Exported e -> new Download(e.bytes(), "application/json");
            case ReceiptExporter.Result.NotAvailable n ->
                    throw new RejectedOutcomeException(n.reason().category(), List.of(new Problem.Rejection(n.reason().name(), null)));
        };
    }

    private static List<String> failed(List<ValidationResult> results) {
        return new ArrayList<>(results.stream().filter(v -> !v.passed()).map(ValidationResult::ruleId).toList());
    }
}

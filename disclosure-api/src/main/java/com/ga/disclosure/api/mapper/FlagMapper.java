package com.ga.disclosure.api.mapper;

import com.ga.disclosure.api.dto.FlagAssignRequest;
import com.ga.disclosure.api.dto.FlagList;
import com.ga.disclosure.api.dto.FlagPage;
import com.ga.disclosure.api.dto.FlagReceipt;
import com.ga.disclosure.api.dto.FlagResolveRequest;
import com.ga.disclosure.api.dto.FlagView;
import com.ga.disclosure.api.error.MalformedRequestException;
import com.ga.disclosure.api.error.NotFoundException;
import com.ga.disclosure.workflow.flag.FlagCommandService;
import com.ga.disclosure.workflow.flag.FlagLookup;
import com.ga.disclosure.workflow.page.Page;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** 준법 플래그 DTO 변환. 필터 형식 오류는 400(유스케이스 전). */
public final class FlagMapper {

    private static final Pattern TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private FlagMapper() {
    }

    public static Optional<FlagLookup.FlagStatus> status(String statusOrNull) {
        if (statusOrNull == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(FlagLookup.FlagStatus.valueOf(statusOrNull));
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException("status");
        }
    }

    public static Optional<String> type(String typeOrNull) {
        if (typeOrNull != null && !TYPE.matcher(typeOrNull).matches()) {
            throw new MalformedRequestException("type");
        }
        return Optional.ofNullable(typeOrNull);
    }

    /** 목록 필터: 상태·유형·담당 역할(COMPLIANCE|MANAGER)·기한 상한(ISO-8601 시각). */
    public static FlagLookup.Filter filter(String status, String type, String assignedRole, String dueBefore) {
        if (assignedRole != null && !assignedRole.equals("COMPLIANCE") && !assignedRole.equals("MANAGER")) {
            throw new MalformedRequestException("assignedRole");
        }
        Optional<java.time.Instant> due;
        try {
            due = Optional.ofNullable(dueBefore).map(java.time.Instant::parse);
        } catch (java.time.format.DateTimeParseException e) {
            throw new MalformedRequestException("dueBefore");
        }
        return new FlagLookup.Filter(status(status), type(type), Optional.ofNullable(assignedRole), due);
    }

    public static java.util.UUID flagId(String path) {
        try {
            return java.util.UUID.fromString(path);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException();
        }
    }

    public static String assignee(FlagAssignRequest r) {
        if (r == null || r.assignee() == null || r.assignee().isBlank() || r.assignee().length() > 256) {
            throw new MalformedRequestException("assignee");
        }
        return r.assignee();
    }

    public static String resolutionCode(FlagResolveRequest r) {
        if (r == null || r.resolutionCode() == null || !TYPE.matcher(r.resolutionCode()).matches()) {
            throw new MalformedRequestException("resolutionCode");
        }
        return r.resolutionCode();
    }

    /** 근거 → 닫힌 모양 JSON(값 형식 판정은 유스케이스 — 모양이 틀리면 422). */
    public static Optional<tools.jackson.databind.JsonNode> evidence(FlagResolveRequest r) {
        if (r.evidence() == null) {
            return Optional.empty();
        }
        tools.jackson.databind.node.ObjectNode e = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
        if (r.evidence().verifyRunJobId() != null) {
            e.put("verifyRunJobId", r.evidence().verifyRunJobId());
        }
        return Optional.of(e);
    }

    public static FlagReceipt assigned(FlagCommandService.Assigned a) {
        return new FlagReceipt(a.flagId().toString(), a.type(), FlagLookup.FlagStatus.OPEN.name());
    }

    public static FlagReceipt resolved(FlagCommandService.Resolved r) {
        return new FlagReceipt(r.flagId().toString(), r.type(), FlagLookup.FlagStatus.RESOLVED.name());
    }

    public static FlagPage page(Page<FlagLookup.Listed> page) {
        return new FlagPage(page.items().stream().map(FlagMapper::view).toList(), page.next().orElse(null));
    }

    public static FlagList list(List<FlagLookup.Listed> flags) {
        return new FlagList(flags.stream().map(FlagMapper::view).toList());
    }

    static FlagView view(FlagLookup.Listed f) {
        return new FlagView(f.flagId().toString(), f.type(), f.status().name(), f.raisedAt().toString(),
                f.disclosureId().map(d -> d.value().toString()).orElse(null), f.disclosureNo().orElse(null));
    }
}

package com.ga.disclosure.api.rest;

import com.ga.disclosure.api.dto.FlagAssignRequest;
import com.ga.disclosure.api.dto.FlagList;
import com.ga.disclosure.api.dto.FlagPage;
import com.ga.disclosure.api.dto.FlagReceipt;
import com.ga.disclosure.api.dto.FlagResolveRequest;
import com.ga.disclosure.api.mapper.DisclosureMapper;
import com.ga.disclosure.api.mapper.FlagMapper;
import com.ga.disclosure.api.mapper.PageMapper;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.flag.FlagCommandService;
import com.ga.disclosure.workflow.flag.FlagQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 준법 플래그(6A 수용심사 §2 ①, 6B 준법 큐): 조회는 관리자(조직)·준법(테넌트) 전용 — 설계사는 404. 배정은 준법·관리자(자기 담당 역할의 플래그), 수동
 * 해소는 준법. 업무 거부는 범주대로 409/422 {@code REJECTED}.
 */
@RestController
@RequestMapping("/api/v1")
public class FlagsController {

    private final FlagQueryService queries;
    private final FlagCommandService commands;

    public FlagsController(FlagQueryService queries, FlagCommandService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @GetMapping("/flags")
    public FlagPage list(Caller caller, @RequestParam(name = "limit", required = false) Integer limit,
                         @RequestParam(name = "after", required = false) String after,
                         @RequestParam(name = "status", required = false) String status,
                         @RequestParam(name = "type", required = false) String type,
                         @RequestParam(name = "assignedRole", required = false) String assignedRole,
                         @RequestParam(name = "dueBefore", required = false) String dueBefore) {
        return FlagMapper.page(queries.list(caller, PageMapper.limit(limit), PageMapper.after(after),
                FlagMapper.filter(status, type, assignedRole, dueBefore)));
    }

    @PostMapping("/flags/{flagId}/assign")
    public FlagReceipt assign(Caller caller, @PathVariable("flagId") String flagId, @RequestBody FlagAssignRequest request) {
        return FlagMapper.assigned(commands.assign(caller, FlagMapper.flagId(flagId), FlagMapper.assignee(request)));
    }

    @PostMapping("/flags/{flagId}/resolve")
    public FlagReceipt resolve(Caller caller, @PathVariable("flagId") String flagId, @RequestBody FlagResolveRequest request) {
        return FlagMapper.resolved(commands.resolve(caller, FlagMapper.flagId(flagId), FlagMapper.resolutionCode(request),
                FlagMapper.evidence(request)));
    }

    @GetMapping("/disclosures/{disclosureId}/flags")
    public FlagList forDisclosure(Caller caller, @PathVariable("disclosureId") String disclosureId) {
        return FlagMapper.list(queries.forDisclosure(caller, DisclosureMapper.id(disclosureId)));
    }
}

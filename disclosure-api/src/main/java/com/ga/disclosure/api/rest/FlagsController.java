package com.ga.disclosure.api.rest;

import com.ga.disclosure.api.dto.FlagList;
import com.ga.disclosure.api.dto.FlagPage;
import com.ga.disclosure.api.mapper.DisclosureMapper;
import com.ga.disclosure.api.mapper.FlagMapper;
import com.ga.disclosure.api.mapper.PageMapper;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.flag.FlagQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 준법 플래그 조회(6A 수용심사 §2 ①): 관리자(조직)·준법(테넌트) 전용 — 설계사는 404. */
@RestController
@RequestMapping("/api/v1")
public class FlagsController {

    private final FlagQueryService queries;

    public FlagsController(FlagQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/flags")
    public FlagPage list(Caller caller, @RequestParam(name = "limit", required = false) Integer limit,
                         @RequestParam(name = "after", required = false) String after,
                         @RequestParam(name = "status", required = false) String status,
                         @RequestParam(name = "type", required = false) String type) {
        return FlagMapper.page(queries.list(caller, PageMapper.limit(limit), PageMapper.after(after), FlagMapper.status(status), FlagMapper.type(type)));
    }

    @GetMapping("/disclosures/{disclosureId}/flags")
    public FlagList forDisclosure(Caller caller, @PathVariable("disclosureId") String disclosureId) {
        return FlagMapper.list(queries.forDisclosure(caller, DisclosureMapper.id(disclosureId)));
    }
}

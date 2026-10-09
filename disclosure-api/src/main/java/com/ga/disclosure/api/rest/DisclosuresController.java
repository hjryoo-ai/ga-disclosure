package com.ga.disclosure.api.rest;

import com.ga.disclosure.api.dto.DisclosureDetail;
import com.ga.disclosure.api.dto.DisclosurePage;
import com.ga.disclosure.api.mapper.DisclosureMapper;
import com.ga.disclosure.api.mapper.PageMapper;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.disclosure.DisclosureQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 확인서 조회(6A 계획 §4.1, {@code DISCLOSURE_READ}): 범위로 걸러진 목록과 상세. 범위 밖·없는 확인서는 같은 404. */
@RestController
@RequestMapping("/api/v1/disclosures")
public class DisclosuresController {

    private final DisclosureQueryService queries;

    public DisclosuresController(DisclosureQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public DisclosurePage list(Caller caller, @RequestParam(name = "limit", required = false) Integer limit,
                               @RequestParam(name = "after", required = false) String after,
                               @RequestParam(name = "status", required = false) String status) {
        return DisclosureMapper.page(queries.list(caller, PageMapper.limit(limit), PageMapper.after(after), DisclosureMapper.status(status)));
    }

    /** 고정 서식의 화면 문구(Phase 7 승인 Q3). {@code ETag} = 내용 해시. */
    @GetMapping("/{disclosureId}/template")
    public org.springframework.http.ResponseEntity<com.ga.disclosure.api.dto.DisclosureTemplateView> template(Caller caller,
            @PathVariable("disclosureId") String disclosureId) {
        com.ga.disclosure.api.dto.DisclosureTemplateView view = DisclosureMapper.template(queries.template(caller, DisclosureMapper.id(disclosureId)));
        return org.springframework.http.ResponseEntity.ok().eTag("\"" + view.bundleHash() + "\"").body(view);
    }

    @GetMapping("/{disclosureId}")
    public DisclosureDetail detail(Caller caller, @PathVariable("disclosureId") String disclosureId) {
        return DisclosureMapper.detail(queries.detail(caller, DisclosureMapper.id(disclosureId)));
    }
}

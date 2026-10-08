package com.ga.disclosure.api.internal;

import com.ga.disclosure.api.dto.JobView;
import com.ga.disclosure.api.mapper.ContractLinkMapper;
import com.ga.disclosure.api.mapper.JobMapper;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.contract.ContractLinkService;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.JobRecord;
import com.ga.disclosure.workflow.job.JobRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 계약 연결 배치(6B 계획 §4, 서비스 주체 {@code CONTRACT_FEED}): 본문 = 계약 {@code contracts/contract-link/v1}. 인가 뒤 해석 — 스키마 위반은 400(위치를
 * 응답에 싣지 않는다), 통과하면 202 + 작업(종류 {@code CONTRACT_LINK_IMPORT}) — 작업 행에는 번호 없는 요약만 남는다.
 */
@RestController
@RequestMapping("/internal/v1/contract-links")
public class InternalContractLinksController {

    private final JobRunner runner;
    private final ContractLinkService links;

    public InternalContractLinksController(JobRunner runner, ContractLinkService links) {
        this.runner = runner;
        this.links = links;
    }

    @PostMapping
    public ResponseEntity<JobView> submit(Caller caller, @RequestBody(required = false) byte[] body) {
        JobRecord job = runner.submitWithBody(caller, JobKind.CONTRACT_LINK_IMPORT, ContractLinkMapper.job(body, links));
        return ResponseEntity.accepted().location(URI.create("/internal/v1/jobs/" + job.jobId())).body(JobMapper.view(job));
    }
}

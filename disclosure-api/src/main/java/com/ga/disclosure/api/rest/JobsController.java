package com.ga.disclosure.api.rest;

import com.ga.disclosure.api.dto.JobPage;
import com.ga.disclosure.api.dto.JobView;
import com.ga.disclosure.api.mapper.JobMapper;
import com.ga.disclosure.api.mapper.PageMapper;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.job.JobQueryService;
import com.ga.disclosure.workflow.job.JobRecord;
import com.ga.disclosure.workflow.job.JobRunner;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;

/** 작업(사람 역할 — 준법의 {@code VERIFY_TENANT}·조회·보고서, 6A 계획 §4.1). 인가는 유스케이스가 한다(역할 칸 밖이면 404). */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobsController {

    private final JobRunner runner;
    private final JobQueryService queries;

    public JobsController(JobRunner runner, JobQueryService queries) {
        this.runner = runner;
        this.queries = queries;
    }

    @PostMapping("/{kind}")
    public ResponseEntity<JobView> submit(Caller caller, @PathVariable("kind") String kind, @RequestBody(required = false) ObjectNode params) {
        JobRecord job = runner.submit(caller, JobMapper.httpKind(kind), JobMapper.params(params));
        return ResponseEntity.accepted().location(URI.create("/api/v1/jobs/" + job.jobId())).body(JobMapper.view(job));
    }

    @GetMapping("/{jobId}")
    public JobView show(Caller caller, @PathVariable("jobId") String jobId) {
        return JobMapper.view(queries.show(caller, JobMapper.jobId(jobId)));
    }

    @GetMapping
    public JobPage list(Caller caller, @RequestParam(name = "limit", required = false) Integer limit,
                        @RequestParam(name = "after", required = false) String after) {
        return JobMapper.page(queries.list(caller, PageMapper.limit(limit), PageMapper.after(after)));
    }

    @GetMapping(value = "/{jobId}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public byte[] report(Caller caller, @PathVariable("jobId") String jobId) {
        return queries.report(caller, JobMapper.jobId(jobId));
    }
}

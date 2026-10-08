package com.ga.disclosure.api.mapper;

import com.ga.disclosure.workflow.contract.ContractLinkBatch;
import com.ga.disclosure.workflow.contract.ContractLinkBatchParser;
import com.ga.disclosure.workflow.contract.ContractLinkService;
import com.ga.disclosure.workflow.job.JobRunner;
import com.ga.disclosure.workflow.job.StandardJobs;

import java.util.function.Supplier;

/** 계약 연결 배치 본문 → 작업(인가 뒤에 해석한다 — 스키마 위반은 400, 위치는 응답에 싣지 않는다). */
public final class ContractLinkMapper {

    private ContractLinkMapper() {
    }

    public static Supplier<JobRunner.BodyJob> job(byte[] body, ContractLinkService links) {
        return () -> {
            ContractLinkBatch batch = ContractLinkBatchParser.parse(body == null ? new byte[0] : body);
            return new JobRunner.BodyJob(StandardJobs.contractLinkSummary(batch), StandardJobs.contractLinkImport(links, batch));
        };
    }
}

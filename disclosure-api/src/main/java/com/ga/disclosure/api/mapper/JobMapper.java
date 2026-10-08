package com.ga.disclosure.api.mapper;

import com.ga.disclosure.api.dto.JobPage;
import com.ga.disclosure.api.dto.JobView;
import com.ga.disclosure.api.error.NotFoundException;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.JobRecord;
import com.ga.disclosure.workflow.page.Page;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

/** 작업 DTO 변환(6A 계획 §4.1). HTTP에 없는 종류(앵커 — 승인 Q7, 모르는 이름)는 라우트가 없는 것과 같은 404다. */
public final class JobMapper {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JobMapper() {
    }

    public static JobKind httpKind(String path) {
        for (JobKind k : JobKind.values()) {
            if (k.name().equals(path) && k != JobKind.ANCHOR && k != JobKind.CONTRACT_LINK_IMPORT) {   // 배치는 /internal/v1/contract-links로만
                return k;
            }
        }
        throw new NotFoundException();
    }

    public static UUID jobId(String path) {
        try {
            return UUID.fromString(path);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException();
        }
    }

    public static ObjectNode params(ObjectNode bodyOrNull) {
        return bodyOrNull == null ? JSON.createObjectNode() : bodyOrNull;
    }

    public static JobPage page(Page<JobRecord> page) {
        return new JobPage(page.items().stream().map(JobMapper::view).toList(), page.next().orElse(null));
    }

    public static JobView view(JobRecord j) {
        return new JobView(j.jobId().toString(), j.kind().name(), j.status().name(), j.channel().name(), j.requestedBy(), j.requestedAt().toString(),
                j.startedAt().map(Instant::toString).orElse(null), j.finishedAt().map(Instant::toString).orElse(null), j.errorCode().orElse(null),
                j.reportSha256().orElse(null));
    }
}

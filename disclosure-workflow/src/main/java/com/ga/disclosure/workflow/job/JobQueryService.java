package com.ga.disclosure.workflow.job;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.ArtifactUnreadableException;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.platform.canonical.Sha256;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 작업 조회(6A 계획 §4): 목록·한 건({@code JOB_READ})과 보고서 열람({@code REPORT_VIEW}). 보고서는 복호화한 평문의 SHA-256이 행의 값과 같을 때만 내준다
 * — 다르면 {@link ReportIntegrityException}(손상된 원문을 반환하지 않는다). 열람은 감사 {@code JOB_REPORT_VIEW}와 같은 트랜잭션이다.
 */
public final class JobQueryService {

    public static final int MAX_PAGE = 100;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JobStore store;
    private final ReportCryptoPort crypto;
    private final ArtifactStore storage;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;
    private final AuthorizationPort authz;

    public JobQueryService(JobStore store, ReportCryptoPort crypto, ArtifactStore storage, AuditPort audit, WorkflowTransactions transactions,
                           Clock clock, AuthorizationPort authz) {
        this.store = Objects.requireNonNull(store, "store");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authz = Objects.requireNonNull(authz, "authz");
    }

    /** 보고서가 아직 없다(SUCCEEDED가 아니다) — 상태를 함께 돌려준다. */
    public static final class ReportNotAvailableException extends RuntimeException {

        private final JobRecord.Status status;

        public ReportNotAvailableException(JobRecord.Status status) {
            super("job report is not available in status " + status);
            this.status = status;
        }

        public JobRecord.Status status() {
            return status;
        }
    }

    /** 저장된 보고서가 행의 해시와 맞지 않는다(변조·손상). */
    public static final class ReportIntegrityException extends RuntimeException {
        public ReportIntegrityException() {
            super("job report does not match its recorded hash");
        }
    }

    /** 최근 순 목록(1..{@value #MAX_PAGE}). */
    @UseCaseEntry(Action.JOB_READ)
    public List<JobRecord> list(Caller caller, int limit, Optional<JobStore.Position> before) {
        Objects.requireNonNull(caller, "caller");
        if (limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_PAGE);
        }
        return transactions.inTenant(caller.tenant(), () -> {
            authz.require(caller, Action.JOB_READ, Target.none());
            return store.recent(limit, before);
        });
    }

    /** 한 건. 없는 작업(다른 테넌트 포함)은 인가 거부(404)다. */
    @UseCaseEntry(Action.JOB_READ)
    public JobRecord show(Caller caller, UUID jobId) {
        Objects.requireNonNull(caller, "caller");
        return transactions.inTenant(caller.tenant(), () -> {
            authz.require(caller, Action.JOB_READ, Target.job(jobId));
            return store.find(jobId).orElseThrow();
        });
    }

    /** 보고서 평문(JCS). */
    @UseCaseEntry(Action.REPORT_VIEW)
    public byte[] report(Caller caller, UUID jobId) {
        Objects.requireNonNull(caller, "caller");
        return transactions.inTenant(caller.tenant(), () -> {
            Actor actor = authz.require(caller, Action.REPORT_VIEW, Target.job(jobId));
            JobRecord job = store.find(jobId).orElseThrow();
            if (job.status() != JobRecord.Status.SUCCEEDED) {
                throw new ReportNotAvailableException(job.status());
            }
            JobStore.ReportKey key = store.reportKey(jobId).orElseThrow();
            byte[] plaintext;
            try {
                plaintext = crypto.open(caller.tenant(), jobId, key, storage.get(job.resultRef().orElseThrow()));
            } catch (ArtifactUnreadableException e) {
                throw new ReportIntegrityException();
            }
            String sha = Sha256.of(plaintext);
            if (!sha.equals(job.reportSha256().orElseThrow())) {
                throw new ReportIntegrityException();
            }
            audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.JOB_REPORT_VIEW, JobRunner.JOB_TARGET,
                    jobId.toString(), JSON.createObjectNode().put("reportSha256", sha)));
            return plaintext;
        });
    }
}

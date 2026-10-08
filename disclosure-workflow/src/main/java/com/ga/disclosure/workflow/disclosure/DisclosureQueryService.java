package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.ListGrant;
import com.ga.disclosure.workflow.authz.Role;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.page.CursorPort;
import com.ga.disclosure.workflow.page.InvalidCursorException;
import com.ga.disclosure.workflow.page.Page;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 확인서 조회(6A 계획 §4.1, {@code DISCLOSURE_READ}): 목록(범위로 걸러진 — 설계사 자기 것, 관리자 조직 아래, 준법 테넌트 전체)과 상세. 상태를 바꾸지 않는다.
 * 준법의 조회는 요청마다 감사 {@code DISCLOSURE_VIEW} 1행(같은 트랜잭션, 설계서 §9). 고객은 가명 참조만 — 개인정보를 읽지 않는다.
 */
public final class DisclosureQueryService {

    public static final int MAX_PAGE = 100;
    static final String STREAM = "disclosures";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DisclosureLookup lookup;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;
    private final AuthorizationPort authz;
    private final CursorPort cursors;

    public DisclosureQueryService(DisclosureLookup lookup, AuditPort audit, WorkflowTransactions transactions, Clock clock, AuthorizationPort authz,
                                  CursorPort cursors) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    /** 상세: 확인서 한 건(항목·스냅샷·봉인·서명 현황), 파기 시각(묘비), 조회 시각. */
    public record Detail(DisclosureRecord disclosure, Optional<Instant> destroyedAt, Instant asOf) {
        public Detail {
            Objects.requireNonNull(disclosure, "disclosure");
            Objects.requireNonNull(destroyedAt, "destroyedAt");
            Objects.requireNonNull(asOf, "asOf");
        }
    }

    /** 목록 한 쪽(1..{@value #MAX_PAGE}). {@code after}는 앞 쪽의 {@link Page#next()}. */
    @UseCaseEntry(Action.DISCLOSURE_READ)
    public Page<DisclosureLookup.Listed> list(Caller caller, int limit, Optional<String> after, Optional<DisclosureStatus> status) {
        Objects.requireNonNull(caller, "caller");
        if (limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_PAGE);
        }
        Optional<DisclosureLookup.Position> from = after.map(c -> position(cursors.open(caller.tenant(), STREAM, c)));
        return transactions.inTenant(caller.tenant(), () -> {
            ListGrant grant = authz.requireList(caller, Action.DISCLOSURE_READ);
            List<DisclosureLookup.Listed> rows = lookup.page(grant.scope(), status, from, limit + 1);
            List<DisclosureLookup.Listed> items = rows.size() > limit ? rows.subList(0, limit) : rows;
            recordComplianceView(grant.actor(), null, JSON.createObjectNode().put("view", "LIST").put("rows", items.size()));
            if (rows.size() <= limit) {
                return new Page<>(items, Optional.empty());
            }
            DisclosureLookup.Listed last = items.getLast();
            return new Page<>(items, Optional.of(cursors.seal(caller.tenant(), STREAM, last.consultDate() + "|" + last.id().value())));
        });
    }

    /** 상세. 범위 밖·없는 확인서(다른 테넌트 포함)는 같은 인가 거부(404). */
    @UseCaseEntry(Action.DISCLOSURE_READ)
    public Detail detail(Caller caller, DisclosureId id) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(id, "id");
        return transactions.inTenant(caller.tenant(), () -> {
            Actor actor = authz.require(caller, Action.DISCLOSURE_READ, Target.disclosure(id));
            DisclosureRecord record = lookup.load(id).orElseThrow(() -> new DisclosureNotFoundException(id));
            recordComplianceView(actor, id, JSON.createObjectNode().put("view", "DETAIL"));
            return new Detail(record, lookup.destroyedAt(id), clock.instant());
        });
    }

    private void recordComplianceView(Actor actor, DisclosureId idOrNull, tools.jackson.databind.node.ObjectNode detail) {
        if (actor.role().equals(Role.COMPLIANCE.name())) {
            audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.DISCLOSURE_VIEW,
                    idOrNull == null ? null : CommandRunner.TARGET, idOrNull == null ? null : idOrNull.toString(), detail));
        }
    }

    private static DisclosureLookup.Position position(String q) {
        int bar = q.indexOf('|');
        try {
            return new DisclosureLookup.Position(LocalDate.parse(q.substring(0, bar)), DisclosureId.of(UUID.fromString(q.substring(bar + 1))));
        } catch (RuntimeException e) {
            throw new InvalidCursorException();
        }
    }
}

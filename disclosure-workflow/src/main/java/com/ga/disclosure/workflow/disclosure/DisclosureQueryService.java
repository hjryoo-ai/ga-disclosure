package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.TemplateRef;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.template.TemplateResolver;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
    private final TemplateResolver templates;

    public DisclosureQueryService(DisclosureLookup lookup, AuditPort audit, WorkflowTransactions transactions, Clock clock, AuthorizationPort authz,
                                  CursorPort cursors, TemplateResolver templates) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
        this.templates = Objects.requireNonNull(templates, "templates");
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

    /**
     * 화면이 쓰는 서식 문구(Phase 7 승인 Q3 — 넓힘): 그 확인서가 <b>고정한</b> 서식 버전의 제목·항목 라벨·필수·순서·섹션과 버전 ID·내용 해시뿐이다 — 상태·검증
     * 결과·업무 판단은 싣지 않는다. 확인서는 초안 생성 때 서식을 고정하므로(설계서 §6.4 1항) 고정 전 초안은 없다 — 응답은 언제나 고정 버전이다. 서식은 개인정보도
     * 문서 본문도 아니라 따로 감사하지 않는다(상세 조회의 {@code DISCLOSURE_VIEW}에 포함 — 승인 Q3).
     */
    @UseCaseEntry(Action.DISCLOSURE_READ)
    public TemplateLabels template(Caller caller, DisclosureId id) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(id, "id");
        return transactions.inTenant(caller.tenant(), () -> {
            authz.require(caller, Action.DISCLOSURE_READ, Target.disclosure(id));
            TemplateRef pinned = lookup.load(id).orElseThrow(() -> new DisclosureNotFoundException(id)).template();
            return TemplateLabels.of(templates.load(caller.tenant(), pinned), templates.contentHash(caller.tenant(), pinned));
        });
    }

    /** 고정 서식의 화면 문구. {@code contentHash} = 번들 해시(번들 출처) 또는 SHA-256(JCS(본문)). */
    public record TemplateLabels(TemplateRef ref, String contentHash, String title, List<FieldLabel> fields, List<SectionLabel> sections) {
        public TemplateLabels {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(contentHash, "contentHash");
            Objects.requireNonNull(title, "title");
            fields = List.copyOf(fields);
            sections = List.copyOf(sections);
        }

        static TemplateLabels of(TemplateResolution resolution, String contentHash) {
            List<FieldLabel> fields = resolution.fields().stream().map(f -> {
                JsonNode unavailable = f.render().path("unavailableText");
                return new FieldLabel(f.code(), f.label(), f.required(), f.order(), f.section().name(),
                        unavailable.isString() ? Optional.of(unavailable.asString()) : Optional.empty());
            }).toList();
            JsonNode layout = resolution.layout();
            List<SectionLabel> sections = new ArrayList<>();
            for (JsonNode s : layout.path("sections")) {
                List<String> codes = new ArrayList<>();
                s.path("fields").forEach(c -> codes.add(c.asString()));
                JsonNode label = s.path("label");
                sections.add(new SectionLabel(s.path("code").asString(), label.isString() ? Optional.of(label.asString()) : Optional.empty(), codes));
            }
            return new TemplateLabels(resolution.ref(), contentHash, layout.path("title").asString(), fields, sections);
        }
    }

    /** 항목 하나의 문구: 코드·라벨·필수·순서·섹션, 산출불가 표기(있으면). 라벨 참조 표식({@code TODO(confirm#N)})은 싣지 않는다 — 데이터에만 있다. */
    public record FieldLabel(String code, String label, boolean required, int order, String section, Optional<String> unavailableText) {
        public FieldLabel {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(section, "section");
            Objects.requireNonNull(unavailableText, "unavailableText");
        }
    }

    /** 배치 섹션: 코드·라벨(있으면)·항목 코드 순서. */
    public record SectionLabel(String code, Optional<String> label, List<String> fields) {
        public SectionLabel {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(label, "label");
            fields = List.copyOf(fields);
        }
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

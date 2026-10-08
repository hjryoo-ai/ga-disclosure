package com.ga.disclosure.workflow.authz;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 역할 × 행위 × 범위(6A 계획 §3.3) — 순수 함수. 정본은 설계서 §9 {@code authz-matrix} 블록이고 {@code AuthzMatrixTest}가 이 표와 양방향으로
 * 대조한다. 칸의 범위:
 * <ul>
 *   <li>{@link Scope#SELF} — 대상 없음, 주체에게 설계사 연결이 있다(초안 작성).</li>
 *   <li>{@link Scope#OWN} — 확인서의 {@code agent_id} = 주체의 {@code agent_id}.</li>
 *   <li>{@link Scope#ORG} — 확인서의 작성 시점 조직이 주체 조직의 세그먼트 접두 아래(조직 없는 V12 이전 확인서는 아니다).</li>
 *   <li>{@link Scope#TENANT} — 대상이 이 테넌트에 있다(대상 없는 행위는 항상).</li>
 *   <li>{@link Scope#SESSION} — 서명 토큰이 가리키는 세션이다(토큰 대조는 유스케이스가 먼저 한다).</li>
 *   <li>{@link Scope#ANY} — 운영자 CLI: 범위 검사 없음(6A 계획 §3.2 — 신뢰 경계는 클러스터·운영자 접근이다). 대상은 있어야 한다.</li>
 * </ul>
 * 역할 하나가 닿는 채널은 하나다({@link Role#channel()}). 주체가 역할을 여럿 가지면 {@link Role} 선언 순서(준법 → 관리자 → 설계사 → …)로 처음
 * 허가하는 역할이 감사 행위자가 된다. 업무 규칙의 역할 요구(예외 승인 역할 등)는 이 표가 아니라 유스케이스가 {@code identity_link}로 본다.
 */
public final class ScopePolicy {

    public enum Scope {
        SELF, OWN, ORG, TENANT, SESSION, ANY
    }

    private static final Map<Action, Map<Role, Scope>> MATRIX = build();

    private ScopePolicy() {
    }

    private static Map<Action, Map<Role, Scope>> build() {
        Map<Action, Map<Role, Scope>> m = new EnumMap<>(Action.class);
        grant(m, Action.DISCLOSURE_CREATE, Role.AGENT, Scope.SELF);
        grant(m, Action.DISCLOSURE_READ, Role.COMPLIANCE, Scope.TENANT, Role.MANAGER, Scope.ORG, Role.AGENT, Scope.OWN);
        // 검증(봉인 차단 목록 포함)은 관리자도 — 예외 승인 전에 무엇을 승인할지 본다(상태를 바꾸지 않는다)
        grant(m, Action.VALIDATE, Role.MANAGER, Scope.ORG, Role.AGENT, Scope.OWN);
        for (Action a : new Action[] {Action.ITEMS_REPLACE, Action.COMPARE, Action.GRADES_REQUEST, Action.RECOMMENDATIONS_SET,
                Action.SEAL, Action.REBASE, Action.SIGN_SESSION_ISSUE, Action.FACE_TO_FACE_CONFIRM, Action.PAPER_SCAN_UPLOAD, Action.AGENT_SIGN}) {
            grant(m, a, Role.AGENT, Scope.OWN);
        }
        grant(m, Action.VOID, Role.MANAGER, Scope.ORG, Role.AGENT, Scope.OWN);
        // 정정은 사람 칸이 없다(운영자 CLI 대리 실행만): 업무 규칙이 예외 승인 역할(룰 exceptionApproval.role)을 요구해 설계사 칸은 업무 거부뿐이고,
        // 관리자 칸은 6B 승인 §2가 거부했다 — 정정 버전의 agent_id는 서명할 설계사여야 하는데 관리자 정정·재배정은 설계되지 않았다(§14 #20)
        grant(m, Action.EXCEPTION_APPROVE, Role.MANAGER, Scope.ORG);
        grant(m, Action.MANAGER_CONFIRM, Role.MANAGER, Scope.ORG);
        grant(m, Action.PAPER_SCAN_REVIEW, Role.MANAGER, Scope.ORG);
        grant(m, Action.COMPLETE, Role.MANAGER, Scope.ORG, Role.AGENT, Scope.OWN);
        grant(m, Action.ARTIFACT_VIEW, Role.COMPLIANCE, Scope.TENANT, Role.MANAGER, Scope.ORG, Role.AGENT, Scope.OWN);
        for (Action a : new Action[] {Action.SIGN_OPEN, Action.SIGN_VIEW_RECORD, Action.SIGN_VERIFY_IDENTITY, Action.SIGN_CAPTURE,
                Action.SIGN_STATUS}) {
            m.computeIfAbsent(a, k -> new EnumMap<>(Role.class)).put(Role.CUSTOMER, Scope.SESSION);
        }
        for (Action a : new Action[] {Action.LEGAL_HOLD_PLACE, Action.LEGAL_HOLD_RELEASE, Action.LEGAL_HOLD_READ, Action.RECEIPT_EXPORT,
                Action.REPORT_VIEW}) {
            grant(m, a, Role.COMPLIANCE, Scope.TENANT);
        }
        // 플래그 목록은 관리자·준법 전용 — 의심받는 설계사가 대리 서명 플래그를 보지 않는다(6A 수용심사 §2 ①)
        grant(m, Action.FLAG_READ, Role.COMPLIANCE, Scope.TENANT, Role.MANAGER, Scope.ORG);
        // 6B 준법 큐(계획 §7): 배정은 준법(테넌트)·관리자(조직 — 업무 규칙이 자기 담당 역할의 플래그로 다시 좁힌다), 수동 해소는 준법만,
        // SLA 경과 표시는 배치
        grant(m, Action.FLAG_ASSIGN, Role.COMPLIANCE, Scope.TENANT, Role.MANAGER, Scope.ORG);
        grant(m, Action.FLAG_RESOLVE, Role.COMPLIANCE, Scope.TENANT);
        grant(m, Action.FLAG_SLA_SWEEP, Role.SCHEDULER, Scope.TENANT);
        // 6B 계약 연결(계획 §4): 배치는 계약 피드 서비스 주체만, 미매칭 보고 행 정리는 배치
        grant(m, Action.CONTRACT_LINK_IMPORT, Role.CONTRACT_FEED, Scope.TENANT);
        grant(m, Action.CONTRACT_LINK_UNMATCHED_PURGE, Role.SCHEDULER, Scope.TENANT);
        grant(m, Action.VERIFY_TENANT, Role.COMPLIANCE, Scope.TENANT, Role.SCHEDULER, Scope.TENANT);
        grant(m, Action.JOB_READ, Role.COMPLIANCE, Scope.TENANT, Role.SCHEDULER, Scope.TENANT);
        // 파기 실행·dry-run은 사람 역할에 없다 — 준법은 보고서 열람만(REPORT_VIEW). 앵커는 플랫폼 배치라 CLI만(6A 승인 Q7)
        for (Action a : new Action[] {Action.DESTROY_DRY_RUN, Action.DESTROY, Action.DISCLOSURE_EXPIRE, Action.ARTIFACT_RECONCILE,
                Action.NOTIFY_DISPATCH, Action.IDEMPOTENCY_PURGE}) {
            grant(m, a, Role.SCHEDULER, Scope.TENANT);
        }
        for (Action a : new Action[] {Action.ARTIFACT_GC, Action.ANCHOR_RUN, Action.CATALOG_IMPORT, Action.CUSTOMER_REGISTER,
                Action.CUSTOMER_REKEY}) {
            m.computeIfAbsent(a, k -> new EnumMap<>(Role.class));
        }
        grant(m, Action.EVENT_FEED_READ, Role.FEED_CONSUMER, Scope.TENANT);
        grant(m, Action.EVENT_FEED_ACK, Role.FEED_CONSUMER, Scope.TENANT);
        // 운영자 CLI는 서명 토큰·피드 밖의 모든 행위(범위 검사 없음)
        for (Action a : Action.values()) {
            Map<Role, Scope> row = m.computeIfAbsent(a, k -> new EnumMap<>(Role.class));
            if (!row.containsKey(Role.CUSTOMER) && !row.containsKey(Role.FEED_CONSUMER)) {
                row.put(Role.OPERATOR, Scope.ANY);
            }
        }
        Map<Action, Map<Role, Scope>> frozen = new EnumMap<>(Action.class);
        m.forEach((a, row) -> frozen.put(a, Collections.unmodifiableMap(new EnumMap<>(row))));
        return Collections.unmodifiableMap(frozen);
    }

    private static void grant(Map<Action, Map<Role, Scope>> m, Action a, Object... roleScope) {
        Map<Role, Scope> row = m.computeIfAbsent(a, k -> new EnumMap<>(Role.class));
        for (int i = 0; i < roleScope.length; i += 2) {
            row.put((Role) roleScope[i], (Scope) roleScope[i + 1]);
        }
    }

    /** 표 전체(행위 → 역할 → 범위, 역할은 선언 순서). */
    public static Map<Action, Map<Role, Scope>> matrix() {
        Map<Action, Map<Role, Scope>> out = new LinkedHashMap<>();
        MATRIX.forEach(out::put);
        return Collections.unmodifiableMap(out);
    }

    /**
     * 허가하는 역할(없으면 빈 값). {@code principal}의 역할 중 이 채널에 닿고 이 행위를 허가하며 범위를 만족하는 첫 역할이다.
     */
    public static Optional<Role> permits(Principal principal, Channel channel, Action action, TargetFacts facts) {
        Map<Role, Scope> row = MATRIX.get(action);
        for (Role role : Role.values()) {
            if (!principal.roles().contains(role) || role.channel() != channel) {
                continue;
            }
            Scope scope = row.get(role);
            if (scope != null && satisfied(scope, principal, facts)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    /** 목록 범위를 주는 역할과 범위(대상 없는 목록 — {@link AuthorizationPort#requireList}). 없으면 빈 값. */
    public static Optional<RoleScope> listScope(Principal principal, Channel channel, Action action) {
        Map<Role, Scope> row = MATRIX.get(action);
        for (Role role : Role.values()) {
            if (!principal.roles().contains(role) || role.channel() != channel || !row.containsKey(role)) {
                continue;
            }
            Optional<ListScope> scope = switch (row.get(role)) {
                case TENANT, ANY -> Optional.of(new ListScope.WholeTenant());
                case ORG -> principal.orgPath().map(ListScope.UnderOrg::new);
                case OWN -> principal.agentId().map(ListScope.OwnedBy::new);
                case SELF, SESSION -> Optional.empty();
            };
            if (scope.isPresent()) {
                return Optional.of(new RoleScope(role, scope.get()));
            }
        }
        return Optional.empty();
    }

    public record RoleScope(Role role, ListScope scope) {
    }

    /** 거부 사유(감사용): 채널에 닿는 역할이 없으면 CHANNEL, 행위를 허가하는 역할이 없으면 ROLE, 대상이 없으면 NOT_FOUND, 나머지는 SCOPE. */
    public static AuthorizationDenied.Reason whyDenied(Principal principal, Channel channel, Action action, TargetFacts facts) {
        if (facts instanceof TargetFacts.Missing) {
            return AuthorizationDenied.Reason.NOT_FOUND;
        }
        boolean reachable = principal.roles().stream().anyMatch(r -> r.channel() == channel);
        if (!reachable) {
            return AuthorizationDenied.Reason.CHANNEL;
        }
        boolean granted = principal.roles().stream().anyMatch(r -> r.channel() == channel && MATRIX.get(action).containsKey(r));
        return granted ? AuthorizationDenied.Reason.SCOPE : AuthorizationDenied.Reason.ROLE;
    }

    private static boolean satisfied(Scope scope, Principal p, TargetFacts facts) {
        return switch (scope) {
            case ANY -> !(facts instanceof TargetFacts.Missing);
            case SELF -> facts instanceof TargetFacts.Tenant && p.agentId().isPresent();
            case TENANT, SESSION -> !(facts instanceof TargetFacts.Missing);
            case OWN -> facts instanceof TargetFacts.OfDisclosure d && p.agentId().map(d.agentId()::equals).orElse(false);
            case ORG -> facts instanceof TargetFacts.OfDisclosure d && p.orgPath().isPresent() && d.orgPath().isPresent()
                    && p.orgPath().get().contains(d.orgPath().get());
        };
    }
}

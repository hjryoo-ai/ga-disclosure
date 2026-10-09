package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.DisclosureId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 방치 초안 조회(6B 지시문 §6 "마지막 변경 + 룰 {@code draft.abandonAfterDays} 경과"). 마지막 변경 시각은 따로 저장하지 않고 그 확인서를 대상으로 한
 * 감사 행 중 <b>변경 행위</b>({@code changeActions})의 가장 늦은 시각이다 — 감사는 업무 트랜잭션과 같이 쓰이므로 변경과 어긋나지 않는다. 열람·거부·
 * 실패 행은 변경이 아니다. 바인딩된 테넌트 트랜잭션 안에서 부른다.
 */
public interface IdleDraftStore {

    /** 봉인 전·미파기 초안 중 마지막 변경이 {@code changedBefore}보다 앞선 것(오래된 순, 최대 {@code limit}). */
    List<Idle> idleSince(Instant changedBefore, Set<String> changeActions, int limit);

    /** 그 확인서의 마지막 변경 시각. 변경 감사가 하나도 없으면 비어 있다(작성 감사는 작성과 같은 트랜잭션이라 정상이면 늘 있다 — 없으면 폐기하지 않는다). */
    Optional<Instant> lastChange(DisclosureId disclosure, Set<String> changeActions);

    record Idle(DisclosureId id, Instant lastChangedAt) {
        public Idle {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(lastChangedAt, "lastChangedAt");
        }
    }
}

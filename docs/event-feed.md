# 이벤트 피드 — 소비자 안내 (ga-disclosure)

> 정본: 계약 `contracts/api/v1/disclosure-internal.openapi.yaml`(`/internal/v1/events`·`/internal/v1/events/ack`)과 envelope 스키마
> `contracts/events/v1/`. 설계서 §4.5·§7. 규약은 포털 설계서 v1.1 §4.1과 같다(정수 커서 — 6A 승인 Q5).

## 누가, 어떻게

- 소비자는 **서비스 주체**다: JWT(`sub`·`tenant_id`)의 주체가 `identity_link`에서 역할 `FEED_CONSUMER`로 연결돼 있어야 한다. 역할은 토큰 클레임이
  아니다. 사람 역할(설계사·관리자·준법)이 부르면 없는 경로와 같은 404다.
- 테넌트 범위는 토큰의 `tenant_id` 하나다. 다른 테넌트의 이벤트는 오지 않는다(쿼리 조건 + PostgreSQL RLS).
- 피드는 **pull**이다. 푸시 어댑터는 인터페이스 자리만 있다(`EventPushPort` — 구현 없음).

## 읽기

```
GET /internal/v1/events?afterSeq={n}&limit={1..1000, 기본 100}
→ 200 {"events":[envelope…], "nextSeq": n, "headSeq": h, "schemaVersion": 1}
```

- `events`는 seq 오름차순, 갭 없음. 각 항목은 `contracts/events/v1/envelope.schema.json` 그대로다(`eventId`·`seq`·`type`·`version`·`occurredAt`·
  `aggregate{kind,id}`·`payload`).
- `nextSeq`는 돌려준 마지막 seq다(빈 쪽이면 시작점 그대로). 다음 호출의 `afterSeq`로 쓴다.
- `headSeq − nextSeq`가 지연이다. 0이 될 때까지 이어 부른다.
- `afterSeq`를 **생략하면 ack한 지점부터**다(처음엔 0). 처리 위치를 소비자가 저장하지 않아도 되지만, 저장한다면 그 값을 `afterSeq`로 보내도 된다.
- `afterSeq > headSeq`면 422 `AFTER_BEYOND_HEAD`다. 소비자가 다른 발행자(예: 복원된 DB)를 보고 있다는 뜻이므로 **소비를 멈추고 운영 알림**을 낸다.

## ack

```
POST /internal/v1/events/ack     Idempotency-Key: <16~128자>
{"upToSeq": n}
→ 200 {"ackedSeq": a, "headSeq": h}
```

- `upToSeq` 이하에서 아직 발행 기록이 없는 이벤트에 발행 시각을 **한 번** 기록한다. 행은 지우지 않는다.
- 이미 ack한 지점 이하를 다시 보내면 아무것도 바뀌지 않는다(200, `ackedSeq`는 그대로).
- `upToSeq > headSeq`면 422 `ACK_BEYOND_HEAD`.
- 모든 POST처럼 `Idempotency-Key`가 필요하다. 같은 키로 다시 보내면 같은 바이트가 `Idempotency-Replayed: true`와 함께 온다.

## 전달 보장과 중복 제거 — at-least-once

- ack하기 전에 다시 읽으면 **같은 이벤트를 다시 받는다**. 처리 도중 죽어도 잃지 않는 대신 중복이 생긴다.
- 소비자는 `eventId`로 중복을 거른다(발행자 안에서 유일).
- seq 규칙: 기대값(마지막 처리 + 1)보다 작으면 무시, 같으면 적용, 크면(갭) 정지하고 알림. 발행자는 갭을 만들지 않는다(DB 트리거가 강제).
- 권장 순서: 읽기 → 처리(멱등) → 처리한 마지막 seq로 ack → 반복.

## 호환성

- 모르는 필드는 무시한다.
- 지원하는 최대 `version`보다 큰 이벤트를 만나면 그 테넌트의 소비를 멈추고 운영 알림을 낸다(필드 추가는 같은 version, 삭제·의미 변경·타입 변경은
  version 증가).
- 이벤트 종류는 envelope 스키마의 `type` 열거가 정본이다. 새 종류가 생기면 계약 버전과 이 문서를 같이 고친다.

## 추가 공지 — `DisclosureDestroyed` (Phase 5, v1)

보존기간이 끝나 확인서의 개인정보 컬럼을 파기하면 `DisclosureDestroyed`가 발행된다(`aggregate.kind = DISCLOSURE`, payload
`{disclosureId, disclosureNo, destroyedAt}`). 확인서 번호·상태·해시·시각·체인은 묘비로 남는다. 소비자(포털 알림·CRM 타임라인)는 그 확인서에 대해
자기 쪽에 복제해 둔 개인정보가 있으면 지우고, 이후 그 확인서의 본문·PDF 열람 링크를 내지 않는다.

## 이벤트에 없는 것

- 고객 개인정보(이름·연락처·생년월일), 사유 텍스트, 서명 토큰은 payload에 없다. 고객은 가명 참조(`customerRef`)뿐이다.
- 수수료율·평균 대비 비율은 없다(등급·순위는 엔진 스냅샷 ID로만 가리킨다).

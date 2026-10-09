package com.ga.disclosure.audit;

/**
 * 감사 행위(설계서 §5 {@code audit_log.action}). 시스템이 스스로 기록하는 닫힌 어휘이며 Phase가 진행되며 늘어난다
 * (CREATE·SEAL·SIGN·VIEW … 는 Phase 3 이후).
 */
public enum AuditAction {
    /** 규제 번들을 테넌트에 복제(또는 같은 번들 재실행 no-op). */
    RULE_DISTRIBUTE,
    /** TENANT 룰 DRAFT → APPROVED. */
    RULE_APPROVE,
    /** 활성화 배치: APPROVED → ACTIVE. */
    RULE_ACTIVATE,
    /** 활성화 배치: ACTIVE → RETIRED. */
    RULE_RETIRE,
    /** 번들 해시 대사 실행(불일치는 RULE_DRIFT 플래그). */
    RULE_RECONCILE,
    /** 대상이 있는 준법 플래그를 올림(같은 대상의 열린 플래그가 있으면 새 행 없이 그 플래그, Phase 2). */
    FLAG_RAISE,
    /** 카탈로그 파일 수입(상품군·상품·패널) 또는 같은 파일 재수입 no-op(detail.outcome=NOOP). */
    CATALOG_IMPORT,
    /** 고객 참조 등록(암호문만 저장). detail에 개인정보 없음. */
    CUSTOMER_REGISTER,
    /** 고객 참조 복호화 조회. */
    CUSTOMER_VIEW,
    /** 원격 서명 링크 발송용 연락처 복호화(사유·참조 ID를 detail에). */
    CUSTOMER_PHONE_READ,
    /** 고객 데이터 키 순환: 활성 키 RETIRED, 새 키 ACTIVE. */
    CUSTOMER_KEY_ROTATE,
    /** 구 키 행을 새 키로 재암호화(배치 1회). */
    CUSTOMER_REKEY,
    /** 쓰는 행이 없어진 구 키의 키 재료 파기(DESTROYED). */
    CUSTOMER_KEY_DESTROY,

    // ---------------------------------------------------------------- Phase 3A: 확인서 워크플로(대상 DISCLOSURE)
    /** 초안 생성: 상담일로 해석한 룰·서식 버전을 고정(detail에 버전 ID·본문 해시). */
    DISCLOSURE_CREATE,
    /** 단계 검증 1회: 단계와 규칙별 결과(통과·오버라이드 가능·대상 해시·메시지). 전이 명령·드라이런 모두. */
    DISCLOSURE_VALIDATE,
    /** 명령이 적용되어 상태가 정해졌다(from → to, 명령별 부수 효과: 스냅샷·사유 폐기 등). */
    DISCLOSURE_TRANSITION,
    /** 업무 거부: 명령이 적용되지 않았다(검증 차단·엔진 응답 거부·산출 중 항목 변경). 상태 불변, 트랜잭션은 커밋. */
    DISCLOSURE_REJECT,
    /** 엔진 등급·순위 호출 1회의 결과(수락·거부·노후, 스냅샷 ID·위반 목록·요청 지문). 오류 응답은 COMMAND_FAILED. */
    GRADE_FETCH,
    /** 관리자 예외 승인 기록(규칙·대상 해시·승인 ID — 사유 텍스트는 review 행에만). */
    EXCEPTION_APPROVE,
    /** 명령 오류: 업무 트랜잭션이 롤백된 뒤 같은 테넌트의 별도 트랜잭션으로 남기는 실패 사실(명령·예외 종류·오류 코드, 메시지 없음). */
    COMMAND_FAILED,

    // ---------------------------------------------------------------- Phase 3B: 봉인·정정·무효·산출물
    /** 봉인: 번호·canonical·PDF·체인 해시·체인 순번·보존기한, 판정 룰의 정체, SEAL 단계 검증 결과 요약. */
    DISCLOSURE_SEAL,
    /** 봉인 거부(업무 거부, 상태·번호·저장소 불변): 거부 코드 목록 전부(단락 없음)와 실패 규칙, 판정 룰의 정체 — 거부 1건에 1행. */
    DISCLOSURE_SEAL_REJECTED,
    /** 무효(사유 텍스트는 행에만, 감사에는 길이만). */
    DISCLOSURE_VOID,
    /** 정정: 원본 → SUPERSEDED, 새 버전 ID. 새 버전에는 DISCLOSURE_CREATE(supersedesId)가 따로 남는다. */
    DISCLOSURE_SUPERSEDE,
    /** 재기준: 이전·새 고정 버전과 새 룰의 COMPARE 검증 결과, 결과 상태(COMPARED|DRAFT). */
    DISCLOSURE_REBASE,
    /** 준법 플래그 해소(해소 사유·해소자). 6B 수동 해소는 detail에 유형·해소 코드·근거(닫힌 모양)·룰 버전. */
    FLAG_RESOLVE,
    /** (6B) 준법 플래그 담당자 배정(대상 FLAG): 유형·담당자 주체. */
    FLAG_ASSIGN,
    /** (6B) 준법 큐 명령 거부(대상 FLAG, 업무 트랜잭션 커밋): 거부 코드, CHAIN_BROKEN 근거 판정의 세부(문제 코드·작업 ID). */
    FLAG_COMMAND_REJECTED,
    /** (6B) SLA 경과 표시(대상 FLAG, 배치): 유형·기한. 새 플래그를 만들지 않는다. */
    FLAG_SLA_BREACHED,
    /**
     * (6B) 확인서의 계약 연결이 생기거나 바뀌었다(대상 DISCLOSURE — 첫 연결도 이 이름 하나, 계획 §A-6): 결과(LINKED·CORRECTED)·링크 ID·이전 링크 ID·출처
     * 참조, 증권번호의 SHA-256(원문 없음), 이전·이후 계약일, 이전·이후 보존기한과 연장 여부, 고정 룰 버전.
     */
    CONTRACT_LINK_CHANGED,
    /** (6B) 계약 연결 배치 요약(대상 TENANT, 배치 끝): 출처·배치 ID·입력 SHA-256·항목 수·결과별 수(번호 없음). */
    CONTRACT_LINK_IMPORT,
    /** (6B) 미매칭 보고 행 정리(대상 TENANT): 기준 시각·지운 수·룰 일수·룰 버전. */
    CONTRACT_LINK_UNMATCHED_PURGE,
    /** 6B 중간 회신 ①: 같은 출처·배치 ID에 다른 내용 — 배치 전체 거부(대상 TENANT, 두 내용 해시만). */
    CONTRACT_LINK_BATCH_REJECTED,
    /** 6B 중간 회신 ③: 연결이 다른 확인서로 옮겨졌다(정정 새 버전 봉인 때 이월, 무효·정정·만료된 확인서의 연결 인수 — 대상은 받은 확인서). */
    CONTRACT_LINK_CARRIED,
    /** 6B: 초안 폐기(대상 DISCLOSURE) — 지운 값의 해시({@code erased}, 파기와 같은 규약)·계기(설계사 사유 코드 또는 방치 일수). */
    DRAFT_ABANDONED,
    /** 6B: 방치 초안 폐기 배치 요약(대상 TENANT). */
    DRAFT_ABANDON_BATCH,
    /** 6B: 징구율 스냅샷(대상 TENANT) — 기준월·룰 버전·산식·정의 표기·작업 ID·행 수·테넌트 전체 수치와 입력 해시(내부 지표 — 규제 정의 없음). */
    COLLECTION_RATE_SNAPSHOT,
    /** 6B: 같은 (달, 룰 버전)의 징구율 재계산 거부 {@code SNAPSHOT_EXISTS}(대상 TENANT, 새 행 없음). */
    COLLECTION_RATE_SNAPSHOT_REJECTED,
    /** 커밋 후 Object Lock 적용(객체 키·보존 기한). 봉인 직후 또는 재적용(reconcile). */
    ARTIFACT_RETAIN,
    /** 커밋 후 Object Lock 적용 실패 — 재적용 대상으로 남았다(오류 코드만). */
    ARTIFACT_RETAIN_DEFERRED,
    /** 산출물 열람: 복호화 후 평문 해시 대조 통과. */
    ARTIFACT_VIEW,
    /** 산출물 열람 거부: 문서 키 파기·해시 불일치·객체 없음. */
    ARTIFACT_VIEW_DENIED,
    /** 참조 없는 잠금 없는 객체(커밋 실패 잔여물) 삭제. */
    ARTIFACT_GC,
    // ---------------------------------------------------------------- Phase 4: 서명(대상 DISCLOSURE, detail에 세션·서명 ID — 토큰·입력값 없음)
    /** 고객 서명 세션 발급(채널·만료 시각, 재발급이면 닫은 이전 세션). */
    SIGN_SESSION_ISSUE,
    /** 원격 서명 링크 발송(발송 시각 기록, 번호 없음). */
    SIGN_SESSION_SEND,
    /** 세션 열람 증거 기록(스크롤 완료·소요 초). */
    SIGN_SESSION_VIEW,
    /** 토큰으로 세션을 열 수 없었다(사유 코드만 — 모르는 토큰·닫힌 세션·TTL 경과). */
    SIGN_SESSION_DENIED,
    /** 본인확인 시도 결과(수단·통과 여부·실패 횟수·취소 여부 — 입력값 없음). */
    SIGN_IDENTITY_CHECK,
    /** 세션 취소(재발급·본인확인 실패 한도·문서 무효·정정·만료). */
    SIGN_SESSION_REVOKE,
    /** TTL이 지난 세션을 EXPIRED로 기록(재발급 직전·만료 배치). */
    SIGN_SESSION_EXPIRE,
    /** 서명 1건 수집(역할·채널·방법·두 해시·증거 객체 해시·본인확인 결과). */
    SIGNATURE_CAPTURED,
    /** 완료: 서명본·증거 패키지 해시, 연장된 보존기한, 증거 패키지가 담은 감사 범위. */
    DISCLOSURE_COMPLETED,
    /** 서명 기한 경과로 만료(이전 상태·기한·판정 기준 시각, 닫은 세션). */
    DISCLOSURE_EXPIRE,

    // ---------------------------------------------------------------- Phase 5: 앵커·검증·파기(대상 ANCHOR·DISCLOSURE·CUSTOMER_REF)
    /** 일일 앵커(대상 ANCHOR, 대상 ID = KST 날짜): 앵커 순번, 두 체인 머리와 seq, 잎 해시. 이 행의 seq = 앵커 audit_seq + 1. */
    ANCHOR_CREATED,
    /** 영수증 저장(대상 ANCHOR): 배치 ID·루트·깊이·잎 위치·TSA 시각·일련번호. */
    ANCHOR_RECEIPT_STORED,
    /** 영수증 내보내기(대상 DISCLOSURE): 덮는·직전 앵커 순번, 봉인 체인 구간, 내보낸 바이트의 SHA-256. */
    ANCHOR_RECEIPT_EXPORTED,
    /** {@code verify tenant} 1회(대상 TENANT): 보고서 JCS의 SHA-256, 결과, 발견 코드별 수. verify의 유일한 쓰기(+ 불일치 시 CHAIN_BROKEN 플래그). */
    VERIFY_RUN,
    /** 파기 ①(대상 DISCLOSURE): 문서 데이터 키 파기 — 키 ID와 감싼 키 바이트의 SHA-256(지운 값의 해시), 판정 날짜·룰 버전. */
    DOCUMENT_KEY_SHREDDED,
    /** 파기 ③(대상 DISCLOSURE): 지정 컬럼 NULL·묘비 — 지운 값마다 표현(해시·존재), 지운 객체 키 수, 대기 면제 앵커, 판정 룰 버전. */
    DISCLOSURE_DESTROYED,
    /** 고객 파기(대상 CUSTOMER_REF): 지운 값마다 암호문 바이트·외부 식별자의 해시, 판정 룰 버전. */
    CUSTOMER_REF_DESTROYED,
    /** 파기 배치 요약(대상 TENANT, 테넌트당 1행): 후보·파기·건너뜀 사유별·실패·고객 파기 수. dry-run은 쓰지 않는다. */
    DESTRUCTION_BATCH_RUN,
    /** 법적 보류 설정(대상 DISCLOSURE|CUSTOMER_REF): 보류 ID·사유 코드·텍스트 길이(텍스트 자체는 행에만)·룰 버전. 저장소 보류 결과는 보고서에. */
    LEGAL_HOLD_PLACED,
    /** 법적 보류 해제: 보류 ID·해제 사유 코드. 저장소 보류 해제 결과는 보고서에. */
    LEGAL_HOLD_RELEASED,

    // ---------------------------------------------------------------- Phase 6A: 인가·작업·통지·피드
    /** 인가 거부(대상 = 요청한 대상 종류·ID, 별도 트랜잭션): 행위·채널·사유(NO_LINK·CHANNEL·ROLE·SCOPE·NOT_FOUND). 응답은 404 하나다. */
    AUTHZ_DENIED,
    /** 작업 제출(대상 JOB, 잠금을 잡은 뒤 같은 트랜잭션): 종류·채널·매개변수. */
    JOB_QUEUED,
    /** 잠금을 잡은 제출이 남아 있던 활성 행을 닫았다(대상 = 닫힌 작업): 이전 상태·닫은 작업 ID. */
    JOB_INTERRUPTED,
    /** 작업 종단(대상 JOB): 상태, 성공이면 보고서 평문 SHA-256, 실패면 오류 코드와 예외 클래스 이름(메시지 없음). */
    JOB_FINISHED,
    /** 작업 보고서 열람(대상 JOB): 보고서 평문 SHA-256(행의 값과 대조한 뒤). */
    JOB_REPORT_VIEW,
    /** 통지 발송 실패 1회(대상 NOTIFICATION): 세션 ID, 닫힌 오류 코드, 시도 수, 다음 시도 시각, 적용 룰 버전. */
    NOTIFY_RETRY,
    /** 통지 소진(대상 NOTIFICATION): 세션 ID, 오류 코드(소진 코드 또는 NO_PHONE), 시도 수, 룰 버전, 올린 플래그 ID. */
    NOTIFY_DEAD,
    /** 통지 취소(대상 NOTIFICATION): 세션이 닫혔거나 만료됐다(SESSION_CLOSED·SESSION_EXPIRED). */
    NOTIFY_CANCELLED,
    /**
     * 준법의 확인서 조회(6A, 설계서 §9 "준법은 테넌트 전체 — 전 건 VIEW 감사"): 요청마다 1행. 상세는 대상 DISCLOSURE·{@code view: DETAIL}, 목록은 대상
     * 없음·{@code view: LIST}·행 수(행 ID는 싣지 않는다).
     */
    DISCLOSURE_VIEW,
    /** 이벤트 피드 ack(대상 없음, 같은 트랜잭션): 요청한 {@code upToSeq}, 이번에 발행 기록한 행 수, 이제 ack한 지점. 읽기는 감사하지 않는다(상태 불변·개인정보 없음). */
    EVENT_FEED_ACK
}

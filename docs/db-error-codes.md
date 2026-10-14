# DB 오류 코드 (SQLSTATE `GDxxx`)

DB 트리거가 불변식 위반을 거부할 때 올리는 사용자 정의 SQLSTATE 목록. 애플리케이션·테스트는 메시지가 아니라 이 코드로 사유를 구분한다. 번호는 한 번 배정하면 재사용하지 않는다.

| 코드 | 마이그레이션 | 대상 | 거부 조건 |
|---|---|---|---|
| GD001 | V3 | `disclosure` UPDATE | 봉인 이후(가변 상태 밖) 본문 컬럼 변경 — 메타(`status`, `superseded_by_id`, `completed_at`, `voided_at`, `void_reason`, `policy_no`, `contract_date`, `retention_until`) 외 전부 |
| GD002 | V3 | `disclosure` DELETE | 항상 |
| GD003 | V3 | `disclosure` UPDATE | 봉인 이후 상태 → 가변 상태(`DRAFT`~`REASONED`) 회귀 |
| GD004 | V3 | `disclosure` UPDATE | `superseded_by_id` 재기록 |
| GD010 | V3 | `disclosure_item`·`recommendation` | 봉인된 부모의 자식 INSERT·UPDATE·DELETE |
| GD011 | V3 | `disclosure_item`·`recommendation` | 부모 확인서 없음 |
| GD020 | V3 | `signature` INSERT | 서명 대상 확인서 없음 |
| GD021 | V3 | `signature` INSERT | 부모 상태가 `SEALED`·`PARTIALLY_SIGNED`가 아님 |
| GD022 | V3 | `signature` INSERT | `signed_doc_hash ≠ disclosure.canonical_hash` |
| GD030 | V3·V5·V6 | append-only 테이블(`signature`, `audit_log`, `document_artifact`, `audit_anchor`, V5 `catalog_import`, V6 `review`)·`disclosure`·자식 테이블 | UPDATE·DELETE(append-only 테이블), TRUNCATE |
| GD040 | V4 | `rule_version` INSERT | GLOBAL인데 `status ≠ APPROVED`, 또는 TENANT인데 `status ≠ DRAFT` |
| GD041 | V4 | `rule_version` UPDATE | 식별자·`scope` 변경, 또는 TENANT·DRAFT가 아닌 행에서 메타(`status`, `apply_to`, `approved_by`, `approved_at`) 외 컬럼 변경 |
| GD042 | V4 | `rule_version` UPDATE | `apply_to` 재기록(값→다른 값, 값→NULL) |
| GD043 | V4 | `rule_version` UPDATE | `status`가 `DRAFT→APPROVED→ACTIVE→RETIRED` 한 단계 전진이 아님 |
| GD044 | V4 | `rule_version` UPDATE | `approved_by`·`approved_at`을 DRAFT→APPROVED 전이 밖에서 변경 |
| GD045 | V4 | `rule_version`·`form_template` | DELETE·TRUNCATE |
| GD050 | V4 | `form_template` UPDATE | 식별자(`tenant_id`, `template_id`, `version`)·출처(`source_bundle_id`, `bundle_hash`) 변경, 또는 번들 출처 행의 `apply_to` 외 컬럼 변경 |
| GD051 | V4 | `form_template` UPDATE | 테넌트 작성본을 `apply_from ≤ 오늘(Asia/Seoul)` 이후 변경(`apply_to` 외) |
| GD052 | V4 | `form_template` UPDATE | `apply_to` 재기록(값→다른 값, 값→NULL) |
| GD060 | V5·V21 | `customer_data_key` UPDATE | 식별자·`kek_id`·`created_at` 변경, `status`가 `ACTIVE→RETIRED→DESTROYED` 한 단계 전진이 아님, `wrapped_key` 변경(RETIRED→DESTROYED에서 NULL로 지우는 것 외). (V21) 재래핑 분기(정의자 롤 + 표식 `ga.rewrap`)에서 살아 있는 키의 `kek_id`·`wrapped_key`를 함께 바꾸는 것 외의 변경 |
| GD061 | V5 | `customer_data_key` UPDATE | 이 키로 암호화된 `customer_ref` 행이 남아 있는데 DESTROYED로 전이 |
| GD062 | V5 | `customer_data_key` | DELETE·TRUNCATE |
| GD063 | V5 | `customer_ref` UPDATE | 식별자(`tenant_id`, `customer_ref`)·`created_at` 변경 |
| GD064 | V5 | `customer_ref` | DELETE·TRUNCATE(파기는 Phase 5 보존기간 배치) |
| GD070 | V5 | `product_group`·`product_catalog`·`insurer_panel` | DELETE·TRUNCATE(파일에서 사라진 행은 유효기간을 닫는다) |
| GD071 | V5 | `product_catalog` UPDATE | 키 정체성(`tenant_id`, `product_key`, `insurer_code`) 변경 |
| GD065 | V6 | `customer_ref` UPDATE | `registration_key` 변경(등록 멱등 키는 INSERT 때만 정한다) |
| GD080 | V6 | `review` INSERT | 부모 확인서 없음, 또는 부모가 가변 상태(`DRAFT`~`REASONED`)가 아님 — 예외 승인은 봉인 전에만 기록한다 |
| GD081 | V7 | `review` INSERT | 승인의 룰 버전(`rule_version_id`, `tenant_rule_version_id`) ≠ 부모 확인서의 현재 고정 버전 — 승인은 지금 고정된 룰 아래의 실패에만 귀속된다 |
| GD090 | V7 | `disclosure_counter` | 첫 값이 1이 아닌 INSERT, +1이 아닌 갱신(건너뛰기·감소·제자리)·키 변경, DELETE·TRUNCATE — 번호는 무결번이다 |
| GD091 | V7 | `disclosure_chain_head` | 첫 값이 1이 아닌 INSERT, +1이 아닌 갱신·키 변경, 실재하는 봉인(같은 테넌트·순번·체인 해시)을 가리키지 않는 머리, DELETE·TRUNCATE |
| GD092 | V7·V9·V21 | `document_key` | 봉인 전 확인서의 키 INSERT, 키 재료 없는 INSERT, 파기(감싼 키 → NULL + 시각·주체, 정의자 롤 + 표식) 또는 (V21) 재래핑(정의자 롤 + 표식 `ga.rewrap`, 살아 있는 키의 `kek_key_id`·`wrapped_dek`만 함께) 외 UPDATE, DELETE·TRUNCATE |
| GD093 | V7·V8 | `document_artifact` | 봉인 전 확인서의 산출물 INSERT, 다른 확인서의 키·파기된 키 참조, 잠금 적용 기록이 있는 INSERT, 보존 기록 외 UPDATE — V7 `retention_applied_at` NULL→값 1회, V8부터 `retention_applied_until`과 함께 쓰고 기한은 증가만(DELETE·TRUNCATE는 GD030) |
| GD094 | V7 | `disclosure` UPDATE | `retention_until` 단축 또는 값 → NULL — 보존기한은 연장만(Object Lock COMPLIANCE와 같은 의미) |
| GD095 | V7 | `disclosure` INSERT·UPDATE | 봉인 정합: 번호 ≠ 그 테넌트·연도 카운터의 현재 값, 번호 연도 ≠ 봉인 시각의 Asia/Seoul 연도, `chain_seq` ≠ 체인 머리 + 1, `chain_hash` ≠ SHA-256(prev ‖ canonical ‖ pdf) |
| GD100 | V8 | `disclosure` UPDATE | 무효 사유(`void_reason_code`·`void_reason_text`)·정정 사유(`supersede_reason_code`·`supersede_reason_text`)·`voided_at`·`completed_at` 재기록 — 한 번 쓰면 끝(끝 상태 VOID에서 나가는 전이도 여기서 막힌다) |
| GD101 | V8 | `sign_session` | 서명 가능 상태(`SEALED`·`PARTIALLY_SIGNED`)가 아닌 확인서에 발급, 발급 시 부모 두 해시를 고정하지 않음, OPEN·초기값(실패 0·통과 없음·열람 증거·발송 시각 없음)이 아닌 발급, 고정 컬럼 변경, OPEN이 아닌 세션의 변경, 실패 +1·통과 수단 추가·열람 증거 1회·발송 시각 1회 외 변경, DELETE·TRUNCATE |
| GD102 | V8 | `signature` INSERT | `signed_pdf_hash ≠ disclosure.pdf_hash` — 서명자가 본 PDF 바이트에도 귀속 |
| GD103 | V8 | `signature` INSERT | 세션이 없거나, 같은 확인서·역할의 OPEN 세션이 아니거나, 세션이 고정한 두 해시 ≠ 서명의 두 해시 |
| GD104 | V8 | `signature` INSERT | 역할이 확인서 고정 GLOBAL 룰의 `signerSet`에 없음(`managerConfirmMode=OPTIONAL`의 MANAGER는 허용), 또는 고정 룰 행이 없음 |
| GD105 | V8 | `signature_evidence` | 다른 확인서의 서명, 그 확인서의 살아 있는 문서 키가 아님, 잠금 적용 기록이 있는 INSERT, 보존 기록(첫 적용 시각 1회·적용 기한 증가만) 외 UPDATE, DELETE·TRUNCATE |
| GD106 | V8 | `outbox_event`·`outbox_head` | 머리 + 1이 아닌 seq, 발행 시각이 있는 INSERT, 발행 시각 1회 기록 외 UPDATE, 1이 아닌 첫 머리·+1이 아닌 머리 이동·실재 이벤트를 가리키지 않는 머리, DELETE·TRUNCATE |
| GD110 | V9 | `anchor` INSERT | 테넌트별 `anchor_seq`가 직전 + 1이 아님, `anchor_date`·`seal_chain_seq`가 뒤로 감, `seal_chain_head` ≠ 그 `chain_seq` 확인서의 `chain_hash`(0이면 64개 0), `audit_head` ≠ 그 `seq` 감사 행의 `entry_hash`, `leaf_hash` ≠ SHA-256(0x00 ‖ JCS(레코드)) — DB가 다시 계산한다(UPDATE·DELETE·TRUNCATE는 GD030) |
| GD111 | V9 | `anchor_receipt` INSERT | `leaf_index ≥ 2^tree_depth`, 경로가 길이 `tree_depth`의 SHA-256 hex 배열이 아님, 잎 + 경로(노드 = SHA-256(0x01 ‖ 왼쪽 ‖ 오른쪽), 방향 = `leaf_index`의 비트)로 다시 계산한 루트 ≠ `root_hash`(UPDATE·DELETE·TRUNCATE는 GD030) |
| GD112 | V9 | `legal_hold` | 해제된 채로 INSERT, `PLACED → RELEASED` 1회(해제 세 컬럼 NULL → 값) 외 UPDATE, DELETE·TRUNCATE |
| GD113 | V9·V11 | `disclosure`·`customer_ref`·`recommendation`·`sign_session` UPDATE, V11부터 `legal_hold` UPDATE(해제된 보류의 `reason_text`만) | 파기 분기(정의자 롤 `disclosure_destroy_definer` + 파기 함수 표식 `ga.destroy`) 밖에서 `destroyed_at`·`destroyed_by` 변경, 파기된 행의 변경, 파기 분기 안에서 지정 컬럼 외 변경·지정 컬럼을 NULL이 아닌 값으로·`destroyed_at` 미설정 |
| GD114 | V9 | 파기 함수 `ga_document_key_shred`·`ga_disclosure_destroy`·`ga_customer_ref_destroy` | 바인딩된 테넌트 ≠ 인자, 대상 없음, 봉인된 종료 상태(COMPLETED·EXPIRED·VOID·SUPERSEDED)가 아님, 이미 파기됨, `retention_until ≥` 판정 KST 날짜(보존기한 당일이 끝나지 않음), 활성 법적 보류(확인서 또는 그 고객), 확인서 파기 전 문서 키가 살아 있음, 키 파기 때 살아 있는 키 없음, 고객 파기 때 파기되지 않은 확인서가 남음 |
| GD120 | V12·V13 | `idempotency_key` | 진행 중이 아닌 INSERT(`claim_seq ≠ 1`·응답 있음·청구 시각 ≠ 생성 시각), 완료 행의 변경, 식별·요청 해시·만료 변경, 진행 중 행의 인수(`claim_seq + 1`·청구 시각 전진) 외 변경, 완료 기록 때 인수 컬럼 변경, 완료 행의 만료 전 DELETE(V13부터 — 진행 중 행은 해제로 지울 수 있다), TRUNCATE |
| GD121 | V12·V21 | `async_job` | QUEUED가 아닌 INSERT, 식별·요청·파라미터 변경, 상태표(`QUEUED→RUNNING`, `RUNNING→SUCCEEDED`, `RUNNING→FAILED`, `QUEUED→FAILED`) 밖의 전이·종단 행의 변경·전이가 정한 컬럼 밖의 변경, DELETE·TRUNCATE. (V21) 끝난 작업의 보고서 키 재래핑(정의자 롤 + 표식, `report_kek_id`·`report_key_wrapped`만 함께)은 허용 |
| GD122 | V12 | `notification_outbox` | PENDING·시도 0·오류 없음이 아닌 INSERT, 끝 상태(SENT·DEAD·CANCELLED) 행의 변경, 식별·수신자·세션 변경, PENDING 안에서 실패 1회 기록(+1·다음 시각 전진·오류 코드) 외 변경, SENT에서 발송 시각 외 변경, DEAD·CANCELLED에서 마지막 실패 1회 외 변경, DELETE·TRUNCATE |
| GD123 | V12 | `sign_session` UPDATE | `token_hash` NULL → 값이 OPEN 원격 링크의 발송(같은 문장에서 `sent_at` NULL → 값)이 아님 — 원격 링크 토큰은 발송 때 한 번 생긴다(값이 있는 해시의 변경은 GD101) |
| GD124 | V12 | `disclosure` | `org_path` 없는 INSERT, `org_path` 변경 — 작성 시점 조직 스냅샷 |
| GD130 | V14·V15 | `contract_link` | 활성이 아니거나 증권번호 없는 INSERT, (V15) 봉인 전·폐기·파기된 확인서에 대한 INSERT, 같은 확인서의 새 행을 가리키는 `superseded_by` 1회(시각 함께) 외의 UPDATE, 대체된 행의 변경, DELETE·TRUNCATE(파기 분기의 증권·청약 번호 NULL은 GD113 규칙) |
| GD131 | V14 | `contract_link_unmatched` | UPDATE·TRUNCATE(삭제는 정리 작업이 룰 기간 뒤에) |
| GD132 | V14 | `disclosure` | `application_no` 변경(작성 때만 — 폐기·파기 분기의 NULL만 예외) |
| GD133 | V14 | `disclosure` | `ABANDONED`로 만들거나 `abandoned_at`을 쓰는 것이 폐기 함수 밖, ABANDONED로 INSERT, 폐기된 행의 변경, 폐기 분기에서 지정 컬럼 밖의 변경·봉인 이후 행, `ga_draft_abandon`의 전제(테넌트 바인딩·봉인 전·미파기) 위반 |
| GD134 | V14 | `compliance_flag` | 열린 상태가 아닌 INSERT(해소·SLA 경과·담당자), 식별·유형·대상·열린 시각·룰 복사 값(담당 역할·설계사 가시성·기한)·증권번호 변경, 해소된 플래그의 변경, SLA 경과 표시 재기록, DELETE·TRUNCATE |
| GD135 | V14 | `collection_rate_snapshot` | UPDATE·DELETE·TRUNCATE(append-only — 정정은 새 룰 버전) |
| GD136 | V14·V15 | `disclosure`·`contract_link` | `policy_no`·`contract_date` 변경이 그 확인서의 활성 `contract_link` 값과 다름(연결 유스케이스 밖의 변경, 파기 분기 제외). (V15) 연결을 넣거나 대체한 트랜잭션이 커밋 때 그 확인서에 활성 연결 정확히 1건과 같은 현재값을 남기지 않음(지연 제약 트리거), 증권 현재값을 가진 확인서 INSERT |
| GD137 | V17 | `disclosure` | 법적 보류(확인서 또는 그 고객)가 걸린 초안의 폐기(`ga_draft_abandon`) — 파기 전제와 같은 조건 |
| GD138 | V18 | `contract_link_batch` | DELETE·TRUNCATE, 요약을 가진 INSERT, 요약 두 번째 쓰기·요약·완료 시각 밖의 변경(배치 원장 — 같은 참조는 한 내용) |
| GD139 | V18 | `legal_hold` | 이미 지워진 대상(파기된 확인서·폐기된 초안·문서 키가 파기된 확인서·파기된 고객)에 대한 보류 INSERT — 대상 행을 FOR UPDATE로 잠근 뒤 판정 |
| GD140 | V21 | `tenant_kek` | CURRENT가 아닌 INSERT, `CURRENT → RETIRED` 한 번(`status`·`retired_at`) 외의 UPDATE, DELETE·TRUNCATE. (같은 테넌트의 두 번째 CURRENT는 부분 유일 인덱스 `23505`, 형식이 `{tenant}-KEK-{n}`이 아닌 ID는 CHECK `23514`) |
| GD141 | V21 | `ga_kek_rewrap` | 인자 누락, 바인딩된 테넌트가 아님, 이전 KEK = 대상 KEK, 대상 KEK가 그 테넌트의 CURRENT가 아님, 감싼 키가 형식보다 짧음, 모르는 대상 표 |

제약 위반(트리거가 아닌 DB 제약)은 PostgreSQL 표준 코드를 그대로 쓴다: 배타 제약 `23P01`(`ex_rule_version_in_force_overlap`, `ex_form_template_overlap`, V5 `ex_insurer_panel_overlap`), CHECK `23514`(V5: `customer_ref` 암호문 머리·ID 형식, 카탈로그 구간·`line`; V6: `disclosure` 상태 열거·스냅샷 헤더 전부-또는-없음·산출 전 스냅샷 금지, `disclosure_item` 임시등록 정체성·등급 복사본 세 형태, `review` 해시·사유, `registration_key` 형식), PK·유일 `23505`(V5: `ux_customer_data_key_active`, `uq_catalog_import_file`, `ux_compliance_flag_open_target`; V6: `ux_disclosure_item_product`, `ux_customer_ref_registration`; V7: `ux_disclosure_chain_seq`, `uq_document_key_disclosure`), FK `23503`. V7 CHECK(`23514`): `disclosure` 봉인 컬럼 7개 전부-또는-없음·상태 결속(가변 상태 없음, VOID 둘 다, 나머지 봉인 이후 전부)·번호 형식과 테넌트 접두·해시 형식·`chain_seq ≥ 1`·VOID ⇔ 무효 시각 ⇔ 사유·SUPERSEDED ⇔ 후속 ID·고정 룰 필수, 카운터 연도·범위, 체인 머리 형식, 문서 키 ID 형식·파기 일관성, 산출물 종류·해시 형식·길이(암호문 = 평문 + 29)·객체 키 형식. V8 CHECK(`23514`): `disclosure` VOID ⇔ 무효 시각 ⇔ 무효 사유 코드·SUPERSEDED ⇔ 후속 ID ⇔ 정정 사유 코드·텍스트는 코드가 있을 때만·코드 형식 `^[A-Z][A-Z0-9_]{0,39}$`·텍스트 공백 금지·옛 `void_reason`은 NULL(V9 제거)·COMPLETED ⇒ 완료 시각, 완료 시각 ⇒ COMPLETED·VOID·SUPERSEDED, `sign_session` 역할 CUSTOMER·채널 TOUCH_PAD·REMOTE_LINK·PAPER_SCAN·상태 열거·해시 형식·USED ⇔ 사용 시각·REVOKED ⇔ 취소 시각 ⇔ 닫힌 취소 사유·발송 시각은 REMOTE_LINK만·통과 수단 닫힌 어휘, `signature` 역할·채널(SSO 포함)·행위 열거·두 해시 형식·고객 ⇔ 세션 ⇔ subject 없음 ⇔ SSO 아님·UPLOADED_SCAN ⇔ PAPER_SCAN ⇔ 대조 기록·SSO_APPROVAL은 SSO만·본인확인은 배열·사유 확인 목록은 MANAGER만, `signature_evidence` 종류·해시·길이·객체 키 `{tenant}/{disclosure}/SIG/{signature}/{kind}/{cipher_sha256}`·보존 기록 둘 다-또는-없음, `document_artifact` 보존 기록 둘 다-또는-없음, 아웃박스 종류 8개(계약 §4.5)·seq ≥ 1·payload 객체. V8 유일(`23505`): `ux_sign_session_open`(확인서·역할당 OPEN 1개), `ux_sign_session_token`, `uq_outbox_event_id`. V9 CHECK(`23514`): `disclosure`·`customer_ref` 파기 시각 ⇔ 주체, `customer_ref` 파기 ⇔ 성명 암호문 없음(파기 뒤 전화·생년월일·CRM ID도 없음), `review` 사유 공백 금지(NULL은 파기 뒤만 — 삽입 때 NULL은 `23502`), `anchor` 테넌트 ID 형식·번호 범위·해시 형식, `anchor_receipt` 루트 형식·깊이 1~24·정책 OID·일련번호 형식, `legal_hold` 대상 정확히 하나·해제 세 컬럼 함께·공백 금지. V9 유일(`23505`): `ux_anchor_date`(테넌트·날짜당 앵커 1개), `ux_legal_hold_active_disclosure`·`ux_legal_hold_active_customer`(대상당 활성 보류 1건). V9는 옛 `void_reason` 컬럼과 V1 `audit_anchor`(행 0을 단언한 뒤) 를 제거했다. V12 CHECK(`23514`): `anchor` 앵커 날짜 = 생성 시각의 KST 날짜(`ck_anchor_date_is_creation_day`, 5 수용심사 R1), `legal_hold` 해제자 ≠ 설정자(`ck_legal_hold_four_eyes`), `identity_link` 역할 닫힌 집합·AGENT ⇒ `agent_id`·AGENT·MANAGER ⇒ 조직 경로 형식·서비스 주체 단독, `disclosure.org_path` 형식, `sign_session` 토큰 없음은 미발송 원격 링크만·발송 ⇒ 토큰, `idempotency_key` 키·해시 형식·완료 세 컬럼 함께·만료 > 생성, `async_job` 종류·상태·채널·상태별 시각·결과 결속·결과 위치 `{tenant}/reports/{job}`·오류 코드 형식, `notification_outbox` 종류·상태·SENT ⇔ 발송 시각·PENDING ⇔ 닫힌 시각 없음·DEAD·CANCELLED ⇒ 오류 코드. V12 유일(`23505`): `ux_async_job_active`(테넌트·종류당 활성 작업 1건, DESTROY_DRY_RUN은 DESTROY와 같은 키), `ux_notification_session`(세션당 통지 1건).

# Phase 5 계획 — 앵커·TSA·검증·파기 (승인 2026-10-03)

기준: `docs/phase-05-지시문.md` v1.0, `docs/phase-04-수용심사.md` §3(결정 1~8, 승인 ①②), 설계서 v1.10 §5·§6·§9·§12·§14 #4. 브랜치 `work/phase-5`(main `c4ea3c3` = Phase 4 PR #7 병합에서 분기).

**승인 반영(`docs/phase-05-계획승인.md`, 2026-10-03)** — Q2·Q13은 대안, 나머지 권장안, 보강 B1~B4. 이 커밋에서 본문을 다음과 같이 고쳤다.
- **Q1**: 순수 계산(머클·TSA 검증·체인 재계산·보고서 모델)과 **`verify package` 전체**를 `disclosure-audit`에 둔다. `verify package`는 seal·workflow·DB·키에 의존하지 않고, 증거 패키지를 자체 리더로 읽는다(생산자 코드와 독립). `workflow`에는 조정(`AnchorJob`·`DestructionJob`·`verify tenant` 러너)만 둔다(§7).
- **Q2(대안)**: 파기는 **한 트랜잭션**에서 한다. 앱 롤이 일반 경로로 감사·아웃박스를 적재한 뒤 `SET LOCAL ROLE disclosure_destroyer` → 파기 함수 → `RESET ROLE`. 함수의 definer는 테이블 소유자가 아닌 전용 롤 `disclosure_destroy_definer`이다. 트리거는 이 롤 + 함수 표식을 본다(§1.5·§5.4). 두 커넥션 설계는 폐기.
- **Q3**: 암호문 컬럼은 저장 바이트 해시, `signature.ip`·`signature.device`는 존재·유형만, 평문 자유 텍스트는 평문 해시(§5.5). 표에 "남기는 것" 열(§5.6).
- **Q4**: 인벤토리의 추가 컬럼을 전부 표에 넣는다. 표가 권위이다(§5.6).
- **Q5**: `retentionDays`(GLOBAL 전용). 산식 `앵커 + retentionYears + retentionDays`, 룰 스키마가 합계 ≥ 1일을 강제한다(§8.7).
- **Q6**: (a) 시계 오프셋 키는 데모 프로파일에만 존재하고, 운영 프로파일에 넣으면 기동 실패한다. (b) 이미 끝난 보존은 잠금 호출 없이 적용 기록 + 감사 사유 `RETENTION_ALREADY_ELAPSED`. Q5의 ≥ 1일 때문에 봉인 직후에는 생길 수 없음을 테스트로 보인다(§8.9).
- **Q7·Q8**: `anchor` 키 제거 → GLOBAL `anchoring.treeDepth`. `audit_anchor`는 V9가 **행 0을 단언한 뒤에만** DROP하고, 행이 있으면 마이그레이션이 실패한다(보존·보고).
- **Q9**: 계획의 권장(`retention_until < date_KST(asOf)`)이 승인의 원칙(`asOf(KST 날짜) > retention_until`)과 같다.
- **Q10**: 파기 감사에 적용한 룰 버전(판정 시점 ACTIVE 룰)을 적는다. 기존 문서의 보존기한 재계산(연장만)은 §14 미결정에 추가한다.
- **Q11**: 데모 스텁 키는 저장소 밖 `~/.ga-disclosure/`(로컬 KEK와 같은 위치)에 처음 시작할 때 만든다. 저장소 안에 두지 않으므로 커밋될 수 없다.
- **Q12**: 승인 문구대로 **REPEATABLE READ** + 테넌트 단위 재시도, 재시도 횟수 보고(§8.1).
- **Q13(대안)**: "이후" 결론을 약한 문장으로 쓴다(§8.3).
- **Q14**: 내보내기 워터마크는 Phase 6.
- **B1**: 계약 항목에 "버전 ID 없는 삭제를 절대 보내지 않음(요청 캡처)", "없는 키의 버전별 삭제 뒤 마커 0", "보류 중 객체는 보존 만료 뒤에도 삭제 거부"(§6).
- **B2·B3**: G14 주입과 감사 스캔 추가(§9).
- **B4**: 설계서 v1.11(각 코드 커밋과 같은 커밋).

- 지시문의 "시작 전 반영"은 첫 커밋 `0d8c172`로 끝냈다. 내용: 수용심사 원문 보관, 지시문 원문 보관, CLAUDE.md 규칙 2 문구 추가, 병합 게이트 규약 추가, 설계서 v1.10(파기 롤 `disclosure_destroyer`, 3B의 마이그레이터 파기 경로 예약 폐기, 파기 = 묘비).
- 지시문이 요구한 ①~⑦은 §1~§7에 둔다.
- 실측이 필요한 두 가지는 계획 단계에서 직접 돌렸다.
  - ⑥ SeaweedFS legal hold·전체 버전 삭제: 스파이크 테스트(커밋하지 않음)로 확인했다(§6).
  - BouncyCastle 최신판·취약점: Maven Central과 OSV를 조회했다(§3).
- 문언끼리 충돌하거나 기존 코드·DB와 맞지 않아 결정이 필요한 지점은 §11 질문에 모았다. 질문마다 권장안을 먼저 둔다.

**계획 단계에서 찾은 사실 중 설계에 영향이 큰 것**

1. **파기 함수와 감사·아웃박스의 원자성.** 파기 롤 연결과 앱 롤 연결은 서로 다른 DB 세션이라 한 트랜잭션이 아니다. 지시문은 ③에서 "지정 컬럼 NULL + 감사 + 아웃박스"를 함께 요구한다. 해법은 §5.4와 Q2에 있다.
2. **SeaweedFS·AWS 모두 과거 시각의 retain-until을 거부한다**(스파이크 `400 InvalidRequest`).
   - 객체 잠금 기한은 `retention_until` 다음 날 00:00 KST이다(3B `retainUntilInstant`). 그래서 보존기간이 짧아도 잠금은 실제 시각으로 그날 자정까지 간다.
   - 지시문 데모("짧은 보존 테넌트의 객체 잠금이 실제로 만료")를 한 번의 실행에서 만들려면 결정이 둘 필요하다. 그 테넌트의 시드 시계를 과거로 두는 것, 그리고 "이미 끝난 보존기간에는 잠금을 걸지 않고 적용 완료로 기록"하는 규칙이다(Q6).
3. **기존 `ArtifactStore.delete(key)`는 버전만 지우고 삭제 마커는 남긴다.**
   - 스파이크에서 버전 ID 없는 삭제는 없는 키에도 삭제 마커를 만들었다.
   - 파기 경로는 언제나 버전·마커를 나열해 ID로 지운다. 나열이 비어 있으면 아무 호출도 하지 않는다(§6).
4. **기존 룰 키 `anchor`가 테넌트 오버라이드 가능하다**(`{externalTimestamp, tsaProfile}`, DISC-2026-07 `tenantOverridable`). 수용심사 결정 1의 "전 테넌트 루트 1개"와 맞지 않는다(Q7).
5. **V1의 `audit_anchor` 테이블은 쓰는 코드가 없다**(SeedData·AppendOnlyTriggerIT만 사용). 지시문의 `anchor` 테이블이 대체한다(Q8).

---

## 1. V9 DDL (①) — `V9__anchor_retention.sql`

오류 코드는 GD110부터 이어서 배정한다(`db-error-codes.md`). 모든 새 테이블은 RLS(FORCE)·테넌트 정책·TRUNCATE 거부(GD030)를 기존 규약 그대로 갖는다.

### 1.1 `anchor` — 일일 테넌트 앵커 (append-only)

```sql
CREATE TABLE anchor (
  tenant_id       TEXT NOT NULL REFERENCES tenant,
  anchor_seq      BIGINT NOT NULL CHECK (anchor_seq >= 1),
  anchor_date     DATE NOT NULL,                       -- KST 날짜
  seal_chain_seq  BIGINT NOT NULL CHECK (seal_chain_seq >= 0),   -- 봉인이 없으면 0
  seal_chain_head TEXT NOT NULL CHECK (seal_chain_head ~ '^[0-9a-f]{64}$'),  -- 없으면 '0'×64(ZERO_CHAIN)
  audit_seq       BIGINT NOT NULL CHECK (audit_seq >= 0),
  audit_head      TEXT NOT NULL CHECK (audit_head ~ '^[0-9a-f]{64}$'),
  leaf_hash       TEXT NOT NULL CHECK (leaf_hash ~ '^[0-9a-f]{64}$'),
  created_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, anchor_seq),
  UNIQUE (tenant_id, anchor_date)
);
```

INSERT 트리거 GD110이 다음을 강제한다. 모두 DB가 다시 계산할 수 있는 값이다.
- `anchor_seq = 직전 + 1`(무간격), `anchor_date > 직전 anchor_date`.
- `seal_chain_seq ≥ 직전`.
- `seal_chain_head` = 그 `chain_seq`의 확인서 `chain_hash`(0이면 ZERO_CHAIN).
- `audit_head` = `audit_log`의 그 `seq` 행의 `entry_hash`(0이면 64개 0).
- **`leaf_hash`를 DB가 다시 계산해 대조한다.** 잎 입력은 키가 고정된 평탄 객체이다. 값은 테넌트 ID(패턴 제한 문자), 정수, ISO 날짜, 소문자 hex뿐이라 JSON 이스케이프가 생기지 않는다. 그래서 SQL에서 JCS 바이트를 그대로 만들 수 있다(§2.1). 앱의 `AnchorRecord`와 DB가 같은 식을 갖고, 테스트가 둘의 일치를 본다.

UPDATE·DELETE는 GD030(`ga_append_only`)이 막는다.

### 1.2 `anchor_receipt` — 영수증 (앵커당 최대 1행, append-only)

```sql
CREATE TABLE anchor_receipt (
  tenant_id      TEXT NOT NULL,
  anchor_seq     BIGINT NOT NULL,
  batch_id       UUID NOT NULL,                        -- 같은 루트를 공유하는 영수증끼리 같다(테넌트 밖 배치 테이블은 없다)
  root_hash      TEXT NOT NULL CHECK (root_hash ~ '^[0-9a-f]{64}$'),
  tree_depth     SMALLINT NOT NULL CHECK (tree_depth BETWEEN 1 AND 24),
  leaf_index     INTEGER NOT NULL CHECK (leaf_index >= 0),
  merkle_path    JSONB NOT NULL,                       -- 잎 → 루트 순서의 형제 해시 hex 배열, 길이 = tree_depth
  tsa_token      BYTEA NOT NULL,                       -- RFC 3161 TimeStampToken(DER, CMS SignedData)
  tsa_gen_time   TIMESTAMPTZ NOT NULL,
  tsa_policy_oid TEXT NOT NULL CHECK (tsa_policy_oid ~ '^[0-9]+(\.[0-9]+)+$'),
  tsa_serial     TEXT NOT NULL CHECK (tsa_serial ~ '^[0-9a-f]+$'),
  created_at     TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, anchor_seq),
  FOREIGN KEY (tenant_id, anchor_seq) REFERENCES anchor
);
```

INSERT 트리거 GD111은 다음을 강제한다.
- `leaf_index < 2^tree_depth`, `jsonb_array_length(merkle_path) = tree_depth`.
- **잎 → 경로 → 루트를 DB가 다시 계산해 `root_hash`와 대조한다**(plpgsql 루프, `sha256`, §2의 노드 접두).

토큰 서명은 DB가 검증하지 않는다. 앱이 저장 전에 검증하고, `verify`가 다시 검증한다. 영수증이 없으면 "미고정"이다(행 부재). UPDATE·DELETE는 GD030이 막는다.

### 1.3 `legal_hold`

```sql
CREATE TABLE legal_hold (
  tenant_id           TEXT NOT NULL REFERENCES tenant,
  hold_id             UUID NOT NULL,
  disclosure_id       UUID,
  customer_ref        TEXT,                            -- customer_ref PK가 (tenant_id, customer_ref) TEXT라 지시문의 customer_ref_id 자리
  reason_code         TEXT NOT NULL,                   -- 룰 legalHoldReasons 닫힌 목록(앱 검사, 설정 시점의 룰)
  reason_text         TEXT,                            -- 길이 상한 = 룰 legalHoldReasonTextMaxLength
  placed_by           TEXT NOT NULL, placed_at TIMESTAMPTZ NOT NULL,
  released_by         TEXT, released_at TIMESTAMPTZ, release_reason_code TEXT,
  PRIMARY KEY (tenant_id, hold_id),
  CHECK ((disclosure_id IS NULL) <> (customer_ref IS NULL)),
  CHECK ((released_at IS NULL) = (released_by IS NULL) AND (released_at IS NULL) = (release_reason_code IS NULL)),
  FOREIGN KEY (tenant_id, disclosure_id) REFERENCES disclosure,
  FOREIGN KEY (tenant_id, customer_ref) REFERENCES customer_ref
);
CREATE UNIQUE INDEX ux_legal_hold_active_disclosure ON legal_hold (tenant_id, disclosure_id) WHERE released_at IS NULL AND disclosure_id IS NOT NULL;
CREATE UNIQUE INDEX ux_legal_hold_active_customer   ON legal_hold (tenant_id, customer_ref)  WHERE released_at IS NULL AND customer_ref IS NOT NULL;
```

GD112가 다음을 강제한다.
- UPDATE는 `PLACED → RELEASED` 1회뿐이다: 해제 세 컬럼만 NULL → 값으로 바뀌고 나머지는 불변이다.
- DELETE는 거부한다.
- `reason_text`는 파기 대상이 아니다(보류 기록 자체가 분쟁 증거). 보류 사유는 개인정보를 쓰지 않도록 룰 설명과 CLI 도움말에 적는다.

### 1.4 파기 컬럼

- `disclosure.destroyed_at TIMESTAMPTZ`, `destroyed_by TEXT`: 둘 다 NULL이거나 둘 다 값(CHECK). 한 번 쓰기이고 파기 함수 경유만 가능하다.
- `customer_ref.destroyed_at TIMESTAMPTZ`, `destroyed_by TEXT`: 같은 CHECK.
  - `name_enc`의 NOT NULL을 풀고 `CHECK ((destroyed_at IS NULL) = (name_enc IS NOT NULL))`로 바꾼다.
  - 파기된 행은 그 뒤 어떤 변경도 거부한다(GD063 확장 → GD113). 지금은 앱이 성명 등을 UPDATE할 수 있으므로, 파기 뒤 다시 채우는 것을 막아야 한다.
- `document_key`: 3B의 `wrapped_dek` NULL·`shredded_at`·`shredded_by` 그대로다.

### 1.5 파기 롤·함수·트리거 예외 (승인 Q2 — 한 트랜잭션, `SET LOCAL ROLE`)

**롤**(클러스터 수준이라 `docker/postgres/init-roles.sql`과 테스트 하네스가 만든다. 마이그레이터는 NOCREATEROLE이다. V9는 둘의 존재와 멤버십을 단언하고, 없으면 실패한다.)

| 롤 | 속성 | 권한 |
|---|---|---|
| `disclosure_destroyer` | NOLOGIN, NOBYPASSRLS | **세 파기 함수의 EXECUTE만.** 테이블·컬럼·시퀀스 GRANT 0 |
| `disclosure_destroy_definer` | NOLOGIN, NOBYPASSRLS, 테이블 소유자 아님 | 세 함수의 소유자(SECURITY DEFINER). 지정 컬럼 + `destroyed_at`·`destroyed_by`의 컬럼 UPDATE와, 판정에 필요한 컬럼 SELECT만. RLS 정책(`tenant_isolation`, 대상 PUBLIC)을 그대로 따른다 |

- 멤버십: `GRANT disclosure_destroyer TO disclosure_app WITH INHERIT FALSE, SET TRUE`(PostgreSQL 16+ 문법).
  - 앱 롤은 평소 파기자 권한이 없다.
  - 트랜잭션 안에서 `SET LOCAL ROLE`로만 함수 EXECUTE를 얻는다.
  - 파기자 롤 자체는 함수 EXECUTE뿐이므로, 그것으로 얻는 것은 함수 호출 권한뿐이다.
- 함수 소유권 이전: V9가 함수를 만든 뒤 `ALTER FUNCTION … OWNER TO disclosure_destroy_definer`로 넘긴다. 이를 위해 init-roles가 마이그레이터에게 definer 멤버십(`SET TRUE, INHERIT FALSE`)을 준다. V9는 definer에게 스키마 CREATE를 잠시 주고, 이전한 뒤 거둔다.
- 테스트가 카탈로그(`role_table_grants`·`role_column_grants`·`role_routine_grants`·`pg_auth_members`)와 `has_*_privilege`로 위 표를 그대로 확인한다(G7).

**함수**: 셋 다 `SECURITY DEFINER`, `search_path` 고정, 소유자 = definer. 이름은 저장소 규약의 `ga_` 접두를 붙였다.

| 함수 | 하는 일 |
|---|---|
| `ga_document_key_shred(p_tenant, p_disclosure, p_as_of, p_by)` | ①. 3B의 `ga_shred_document_key`(EXECUTE 부여가 없어 쓰인 적 없음)는 DROP한다 |
| `ga_disclosure_destroy(p_tenant, p_disclosure, p_as_of, p_by)` | ③. 지운 컬럼 이름 목록을 돌려준다 |
| `ga_customer_ref_destroy(p_tenant, p_customer_ref, p_as_of, p_by)` | 고객 파기 |

함수 안 검사(실패는 GD114):
- `p_tenant = current_setting('app.tenant_id')`를 단언한다. 함수는 테넌트를 바꾸지 않는다. 앱 트랜잭션이 이미 바인딩해 두었다.
- 대상 행 `FOR UPDATE`.
- 봉인 이후 종료 상태(`COMPLETED|EXPIRED|VOID|SUPERSEDED` ∧ `disclosure_no IS NOT NULL`).
- `retention_until < p_as_of`(KST 날짜, §5.1).
- 활성 보류 없음(확인서·그 고객 양쪽).
- 미파기.
- ③은 추가로 문서 키가 이미 파기됐을 것.

감사·아웃박스는 함수가 쓰지 않는다. 같은 트랜잭션에서 앱 롤이 기존 경로(`AuditPort`·`OutboxPort`)로 먼저 적재한다(§5.4).

**트리거 예외 — 롤 + 함수 표식**
1. 함수는 시작할 때 `set_config('ga.destroy', 'disclosure:' || p_disclosure, true)`(또는 `customer_ref:…`)를 트랜잭션 로컬로 설정하고, 끝에서 `''`로 되돌린다.
2. 불변 트리거(`ga_disclosure_guard_update`, `ga_child_guard`, `signature`·`review`·`sign_session`의 append-only/GD101, `ga_customer_ref_guard`, `ga_document_key_guard`)는 맨 앞에서 다음을 **모두** 만족할 때만 파기 분기로 간다.
   - `current_user = 'disclosure_destroy_definer'`.
   - `current_setting('ga.destroy', true)`가 그 행의 확인서(또는 고객)를 가리킨다.
3. 파기 분기는 "지정 컬럼만 값 → NULL, 나머지는 `to_jsonb(NEW) - 지정 컬럼 = to_jsonb(OLD) - 지정 컬럼`"을 확인한다.
4. `disclosure` 행은 같은 문장에서 `destroyed_at`이 NULL → 값이어야 한다.
5. 자식 테이블은 함수가 `disclosure`보다 먼저 소거한다.
6. 이 분기 밖의 모든 경로는 기존 규칙 그대로다. 막히는 경로:
   - 앱 롤: 표식을 세워도 `current_user`가 다르다.
   - 파기자 롤의 직접 UPDATE: 테이블 권한이 없다.
   - definer로 표식 없이 UPDATE: 표식 검사.
- **위협 모델 한계(설계서에 적는다).** 마이그레이터는 definer 멤버십과 `DISABLE TRIGGER` 권한을 가진 DDL 권한자로서 신뢰 경계 안에 있다.

### 1.6 그 밖

- **`disclosure.void_reason` 구 컬럼 DROP**(Phase 4가 미룬 2단계). `ck_disclosure_legacy_void_reason`도 함께 DROP하고, `ga_disclosure_guard_update`의 메타 목록에서 뺀다.
- **`audit_anchor` DROP**(V1, 미사용 — Q8). V9는 먼저 `SELECT count(*)`가 0임을 단언하고(RLS를 잠시 해제한 창에서 — V8 백필과 같은 방식), 행이 있으면 `RAISE`로 실패한다(제거 대신 보존·보고). 설계서 §5 블록과 §6.7 문구를 `anchor`·`anchor_receipt`로 바꾼다.
- **`compliance_flag`**: `disclosure_id`는 이미 NULL을 허용한다(V1). 감사 체인 단절처럼 확인서를 정할 수 없는 `CHAIN_BROKEN`은 `disclosure_id NULL` + `target_kind='AUDIT_LOG'`·`target_id=seq`로 둔다. DDL 변경은 없다.
- `DisclosureDestroyed` 이벤트: 계약 `contracts/events/v1`에 추가한다(추가형). payload는 `{disclosureId, disclosureNo, destroyedAt}`이고 CHECKSUMS를 갱신한다.

---

## 2. 머클 트리 규격 (②)

### 2.1 잎 — 앵커 레코드

```json
{"anchorDate":"2026-10-03","anchorSeq":12,"auditHead":"<64>","auditSeq":4087,"sealChainHead":"<64>","sealChainSeq":311,"tenantId":"DEMO1","v":1}
```

- 키 정렬·공백 없는 JCS 바이트이다. `leaf = SHA-256(0x00 ‖ JCS)`.
- 테넌트 ID가 잎 안에 있으므로 서로 다른 테넌트의 잎은 같을 수 없다.

### 2.2 트리

- 잎을 `leaf_hash` 바이트의 부호 없는 사전순으로 정렬해 왼쪽부터 채운다. 빈 자리는 패딩 잎이다.
- 깊이 `d = anchoring.treeDepth`(GLOBAL, 비오버라이드, 기본 16)이고 슬롯은 `2^d`이다.
- 노드는 `SHA-256(0x01 ‖ 왼쪽 ‖ 오른쪽)`이다.
- 패딩 잎은 `SHA-256(0x02 ‖ ASCII("ga-disclosure/anchor/pad/v1"))` 상수이다. 레벨 k의 전부-패딩 부분트리 해시도 상수이므로 미리 계산해 두면 2^16 슬롯이어도 계산은 잎 수에 비례한다.
- 접두 0x00·0x01·0x02가 서로 다르므로, 잎을 노드로 내밀거나 노드를 잎으로 내미는 두 번째 원상 공격은 성립하지 않는다(RFC 6962 방식에 패딩 도메인을 더한 것).
- 경로는 잎 → 루트 순서의 형제 해시 `d`개이다. 레벨 i에서 형제가 오른쪽인지는 `leaf_index`의 i번째 비트(0이면 형제가 오른쪽)로 정한다. 그래서 경로에 방향을 따로 두지 않는다.
- 잎 수 > `2^d`이면 배치를 거부한다(fail-fast, 보고서 `TREE_FULL`, 영수증 0).
- 테넌트마다 해석한 `treeDepth`가 다르면(GLOBAL 복제 불일치) 배치를 거부한다(`TREE_DEPTH_DISAGREES`).
- TSA에 보내는 값: `messageImprint = {SHA-256, root}`. 루트(32바이트)가 이미 SHA-256 출력이라 다시 해시하지 않는다.
- **규모 노출의 한계(설계서에 적는다).** 패딩이 상수라서, 경로의 형제 중 "전부-패딩 부분트리 상수"인 것의 위치로 점유 슬롯 수의 상한을 대략 추정할 수 있다. 정확한 수와 다른 테넌트의 잎·머리 값은 드러나지 않는다.

### 2.3 기계 판독 규격 블록 (설계서 §6에 두고 `MerkleSpecTableTest`가 코드 상수와 양방향 대조)

```merkle-spec
key,value
hash,SHA-256
leafPrefix,00
nodePrefix,01
padPrefix,02
padLabel,ga-disclosure/anchor/pad/v1
leafInput,JCS(anchorDate|anchorSeq|auditHead|auditSeq|sealChainHead|sealChainSeq|tenantId|v)
recordVersion,1
leafOrder,leafHashUnsignedAscending
depthKey,anchoring.treeDepth
defaultDepth,16
maxLeaves,2^depth
pathOrder,leafToRoot
pathSide,bit(leafIndex,level)=0 -> sibling right
imprint,root
```

---

## 3. TSA 포트·스텁 (③)

**의존성(착수 시 재확인).** 2026-10-03 Maven Central 조회 결과:
- `bcpkix-jdk18on`·`bcprov-jdk18on`·`bcutil-jdk18on` 최신 = **1.86**(`maven-metadata.xml` release 1.86, POM last-modified 2026-09-11).
- OSV `api.osv.dev/v1/query`(Maven, 1.86)는 세 좌표 모두 **0건**이었다. 대조로 1.77을 조회하면 9건이 나와 질의가 동작함을 확인했다.
- 라이선스는 POM 기준 "Bouncy Castle Licence"(MIT 계열)이다. 서드파티 목록 `docs/third-party.md`(신설)에 기록한다.
- 버전 카탈로그 + 락 파일로 고정한다. 의존 모듈은 `disclosure-audit`(main은 `bcpkix`·`bcprov`·`bcutil`, 검증·스텁), 그리고 testFixtures이다.

**모듈·패키지**
- `com.ga.disclosure.audit.tsa`
  - `TimestampAuthorityPort`: BC 타입이 없는 포트. `StampResponse stamp(byte[32] digest)` → DER 토큰 + nonce.
  - `TimestampVerifier`: BC 사용.
  - `TimestampToken`(파싱 결과 record: `genTime`·`policyOid`·`serialHex`·`imprint`·`nonce`·`signerCertSha256`).
- `com.ga.disclosure.audit.tsa.stub.LocalStubTsa`: BC `TimeStampResponseGenerator`. main에 둔다 — 데모 프로파일이 런타임에 써야 하기 때문이다. 앱은 BC가 아니라 이 클래스만 참조한다.
- `com.ga.disclosure.audit.tsa.http.HttpTimestampAuthority`: 실 TSA 어댑터, RFC 3161 over HTTP. URL과 신뢰 앵커는 배포 설정이다. 기관 선택·체인 보관은 §14 #4 운영 결정 그대로이다.
- ArchUnit: `org.bouncycastle..` 참조는 `com.ga.disclosure.audit.tsa..`와 testFixtures만 허용한다(FQN 패키지 열거, 주입으로 확인).

**요청·검증**
- 요청: SHA-256 imprint, 64비트 난수 nonce(`SecureRandom` — `TokenSource`처럼 포트로 받아 테스트가 고정), `certReq=true`, 정책 OID는 지정하지 않는다(TSA 기본).
- 응답 수락: status granted, imprint·nonce 일치, 서명 유효, 그리고 신뢰 앵커로 체인. 하나라도 어긋나면 영수증을 쓰지 않는다(TSA 실패와 같다).
- `TimestampVerifier.verify(token, root, trustAnchors)`는 다음을 확인한다.
  - CMS 서명 유효.
  - 서명자 인증서의 EKU가 `id-kp-timeStamping`이고 critical.
  - 설정된 `TrustAnchor` 집합에서 PKIX 경로 검증. 폐기 확인은 끈다 — 스텁에는 CRL·OCSP가 없고, 실 TSA의 폐기 정책은 §14 #4이다. `genTime` 시점 유효성을 본다.
  - imprint = root.
  - 결과는 `VALID | INVALID(이유) | UNTRUSTED`이다. 신뢰 앵커가 비어 있으면 서명이 맞아도 `UNTRUSTED`이고, 통과가 아니다.
- **nonce**는 응답 수락 때만 비교한다. 저장된 토큰 안의 nonce는 `verify`에서 보고만 한다(요청 nonce를 따로 저장하지 않는다).

**스텁 키 수명**
- 테스트: 실행(JVM)마다 ECDSA P-256 키와 자체 서명 인증서를 생성한다(유효 1일, EKU timeStamping critical). 신뢰 앵커는 그 인증서 하나이다.
- 데모(Q11 권장): 첫 시작에 `~/.ga-disclosure/tsa-stub.p12`(저장소 밖, 권한 600, 로컬 KEK 파일과 같은 위치·규약)를 만든다. 신뢰 앵커 인증서는 `~/.ga-disclosure/tsa-trust.pem`으로 내보낸다. 이후 실행은 재사용한다. 키는 커밋하지 않는다.
  - 지시문의 "시작 시 생성"을 문자 그대로 매 실행 생성으로 하면 문제가 생긴다. CLI는 명령마다 새 JVM이라 앵커 날짜마다 키가 달라지고, 신뢰 번들이 누적돼야 한다.

**TSA 실패**: 불가·거부·검증 실패 모두 봉인·서명·완료를 막지 않는다. 앵커는 남고 영수증이 비어 다음 실행의 둘째 배치가 된다(§8.1).

---

## 4. `verify` 보고서 (④)

**스키마** `contracts/verify/v1/verify-report.schema.json`(닫힌 객체):

```json
{
  "reportVersion": 1, "verifierVersion": "<git describe>", "kind": "PACKAGE|TENANT",
  "inputs": { "package": {"sha256": "<64>", "bytes": 131138}, "receipt": {"sha256": "<64>"} | null, "trust": {"sha256": "<64>"} | null,
              "tenantId": "DEMO1", "from": null, "to": null, "asOf": "…" },
  "result": "MATCH|MISMATCH",
  "checks": [ {"check": "MANIFEST_SCHEMA", "status": "PASS|FAIL|SKIPPED", "count": 1} ],
  "findings": [ {"code": "SEAL_CHAIN_BROKEN", "where": {"chainSeq": 2, "disclosureId": "…"}, "detail": {"expected": "<64>", "actual": "<64>"}} ],
  "statements": [ "내부 정합성만 확인. 존재 시각·체인 연속은 영수증 또는 verify tenant가 필요하다." ],
  "conclusion": { "existedBefore": "2026-10-04T00:00:07Z" | null, "existedAfter": "2026-10-03T00:00:05Z" | null, "tsaTrusted": true | false | null },
  "counts": { "disclosures": 0, "objects": 0, "auditRows": 0, "anchors": 0, "receipts": 0 }
}
```

- 보고서 직렬화는 JCS다. `VERIFY_RUN` 감사에는 그 SHA-256을 남긴다.
- 고정 문장은 지시문 원문 그대로 상수로 두고 테스트가 대조한다.

**종료 코드**: `0` 일치(`MATCH`, `ANCHOR_UNSTAMPED`만 있어도 2 — 아래 표), `2` 불일치, `3` 입력 오류(파일 없음·손상 ZIP·스키마 위반 영수증·알 수 없는 테넌트). 3은 보고서 대신 `{code, message}`만 내보낸다.

**발견 코드** — 지시문의 13개 + 계획 추가 4개:

| 코드 | package | tenant | 의미 |
|---|---|---|---|
| `PACKAGE_ENTRY_MISMATCH` | ✓ | | 엔트리 해시·크기·매니페스트 `files` 불일치, canonical JCS 재계산 ≠ `canonicalHash` |
| `SIGNED_PDF_NOT_PREFIXED` | ✓ | | `disclosure.pdf`가 서명본의 바이트 접두가 아님 |
| `SIGNATURE_BINDING_MISMATCH`(추가) | ✓ | | 서명 레코드의 두 해시 ≠ 매니페스트 해시 |
| `AUDIT_ENTRY_MISMATCH`(추가) | ✓ | | 감사 발췌 행의 `entry_hash` 재계산 불일치(연속은 보지 않는다) |
| `SEAL_CHAIN_BROKEN` | ✓(영수증 구간) | ✓ | 봉인 체인 재계산 불일치 |
| `AUDIT_CHAIN_BROKEN` | | ✓ | 감사 체인 seq·prev·entry 불일치 |
| `NUMBERING_GAP` | | ✓ | 연도별 채번 갭·중복 |
| `OBJECT_HASH_MISMATCH` | | ✓ | 복호화 평문 해시 ≠ 기록 |
| `OBJECT_MISSING` | | ✓ | 파기 안 된 확인서의 객체 부재 |
| `OBJECT_NOT_DELETED` | | ✓ | 파기된 확인서의 객체(어느 버전이든) 존재 |
| `DESTRUCTION_UNAUDITED`(추가) | | ✓ | `destroyed_at`이 있는데 `DISCLOSURE_DESTROYED` 감사가 없음(또는 그 반대) |
| `ANCHOR_MISMATCH` | | ✓ | 앵커 잎·머리 ≠ 그 seq 시점 재계산 |
| `RECEIPT_PATH_INVALID` | ✓ | ✓ | 잎 + 경로 ≠ 루트, 또는 영수증이 이 문서·테넌트의 것이 아님 |
| `TSA_INVALID` | ✓ | ✓ | 토큰 서명·imprint 불일치 |
| `TSA_UNTRUSTED` | ✓ | ✓ | 신뢰 앵커 없음·체인 실패 |
| `ANCHOR_UNSTAMPED` | | ✓ | 영수증 없는 앵커가 `verify.unstampedAnchorAlertDays`를 넘김 |
| `RECEIPT_NOT_COVERING`(추가) | ✓ | | 영수증 앵커의 `sealChainSeq` < 문서 `chainSeq`(덮지 않음) |

- `verify package`에서 신뢰 앵커를 주지 않으면 TSA 검사 결과는 `TSA_UNTRUSTED`(불일치, 종료 2)이다.
- 데모와 예시는 `--tsa-trust`를 준다. 신뢰 없는 결론은 "존재 증명"이 아니기 때문이다.

---

## 5. 파기 (⑤)

### 5.1 판정 산식 (`RetentionDecision`, 순수 — `disclosure-sign.retention`에 기존 `RetentionAnchors`와 함께)

입력: 확인서 요약(상태, 봉인 여부, `retention_until`, `completed_at`, 앵커 날짜들), 활성 보류 여부(확인서·고객), 룰(고정 `retentionAnchors` + 판정 시점 룰의 `contractLinkWaitDays`, Q10), `asOf`(Instant).

```
today = date_KST(asOf)
terminal  = status ∈ {COMPLETED, EXPIRED, VOID, SUPERSEDED} ∧ disclosure_no ≠ null     -- 봉인 전 VOID는 범위 밖
reached   = retention_until < today                                                     -- 보존기한 당일이 끝난 뒤(잠금 기한 = 다음 날 00:00 KST와 같은 경계)
pending   = ∃ a ∈ retentionAnchors : date(a) = null ∧ possible(a, status) ∧ ¬waived(a)
possible(COMPLETION, s)    = s = COMPLETED
possible(CONTRACT_DATE, s) = s = COMPLETED
possible(SEAL, s)          = true                                                        -- 봉인됐으면 언제나 날짜가 있다
waived(CONTRACT_DATE)      = date_KST(completed_at) + contractLinkWaitDays < today
결과 = DESTROY(anchorsWaived)        if terminal ∧ reached ∧ ¬hold ∧ ¬pending ∧ ¬destroyed
       SKIP(RETENTION_NOT_REACHED | HOLD | PENDING_ANCHOR | ALREADY_DESTROYED | NOT_TERMINAL)
```

- **`≤`의 의미.** 지시문은 "`retention_until ≤ asOf`"라고 썼다. `retention_until`은 날짜이고 그 날 끝(다음 날 00:00 KST)까지 잠긴다. 그래서 날짜 비교 `retention_until < date_KST(asOf)`로 구현한다. 순간 비교로는 `retainUntilInstant(retention_until) ≤ asOf`와 같다(Q9).
- 우선순위: 건너뜀 사유가 여럿이면 `HOLD > LOCK_NOT_EXPIRED > PENDING_ANCHOR > RETENTION_NOT_REACHED` 순으로 하나만 보고한다. 보고서에는 사유 목록 전체도 둔다.

### 5.2 순서와 재시도 지점 (단계별 멱등)

| 단계 | 내용 | 멱등 근거 | 중단 후 재실행 |
|---|---|---|---|
| ⓪ | 그 확인서의 모든 `document_artifact`·`signature_evidence` 행이 `retention_applied_until < today`(NULL이면 거짓) — 하나라도 아니면 `LOCK_NOT_EXPIRED`, **아무것도 바꾸지 않는다** | 읽기만 | 그대로 다시 판정 |
| ① | `ga_document_key_shred` — `wrapped_dek` NULL, 감사 `DOCUMENT_KEY_SHREDDED`(`{wrappedDek: SHA-256(감싼 키 바이트)}`) | 함수가 이미 파기된 키에 NULL을 돌려주면 감사도 쓰지 않는다 | ①은 건너뛰고 ②부터 |
| ② | 객체 키 전부: 버전·삭제 마커를 나열해 ID로 지운다. 나열이 비면 호출 0. 잠금 거부(시계 차)면 `LOCK_NOT_EXPIRED`로 보고하고 ③ 진입 금지 | 없는 버전 삭제 = 성공(스파이크), 나열 기반이라 마커를 만들지 않는다 | ②부터 |
| ③ | 모든 키의 `ListObjectVersions`(버전 + 마커) = 0 확인 → `ga_disclosure_destroy` — 지정 컬럼 NULL, `destroyed_at`, 감사 `DISCLOSURE_DESTROYED`, 아웃박스 `DisclosureDestroyed` | 함수가 기파기면 GD114 대신 `ALREADY_DESTROYED`를 돌려준다(오류 아님) | 끝 |

- 보류는 ①·③ 함수 안에서 다시 확인한다. ① 뒤에 보류가 걸리면 ②·③이 멈춘다. 키 파기는 되돌릴 수 없고, 그 사실은 보고서에 `HOLD_AFTER_SHRED`로 센다.
- 아웃박스 `DisclosureDestroyed`는 ③에서만 낸다(①에는 이벤트가 없다).

### 5.3 고객 파기

- **live 확인서 = 그 고객의 확인서 중 `destroyed_at IS NULL`인 것(봉인 전 초안 포함).** 초안 파기는 범위 밖이므로 초안이 하나라도 있으면 그 고객은 파기되지 않는다. 이 사실을 설계서 §14 미결정 항목(초안 보존)에 함께 적는다.
- 조건(판정 시점 룰): `live = 0` ∧ 고객 보류 없음 ∧ `asOf_KST ≥ 기준일 + 유예`.
  - 기준일·유예 = (확인서가 있었으면) 마지막 `destroyed_at`(KST) + `customerRef.graceDaysAfterLastDestruction`.
  - (없었으면) `created_at`(KST) + `customerRef.abandonedDays`.
- `ga_customer_ref_destroy`: `name_enc`·`phone_enc`·`birth_date_enc`·`crm_customer_id`(Q4) NULL, `destroyed_at`, 감사 `CUSTOMER_REF_DESTROYED`. 이벤트는 없다(계약에 고객 이벤트가 없다).
- GD064(DELETE 거부)는 그대로다.

### 5.4 파기 트랜잭션 (승인 Q2 — 한 트랜잭션)

①·③과 고객 파기는 각각 앱 데이터소스의 **트랜잭션 하나**로 한다.

1. 테넌트 바인딩(기존 `WorkflowTransactions`).
2. 앱 롤로 지울 값을 읽어 §5.5의 표현으로 해시를 만든다.
3. 기존 경로로 감사 `DOCUMENT_KEY_SHREDDED|DISCLOSURE_DESTROYED|CUSTOMER_REF_DESTROYED`를 적재한다. ③은 아웃박스 `DisclosureDestroyed`도 적재한다. 체인·계약 검증은 기존 코드 하나이다.
4. `SET LOCAL ROLE disclosure_destroyer` → `SELECT ga_…(…)` → `RESET ROLE`.
5. 커밋.

함수가 거부하면(GD114) 트랜잭션 전체가 롤백되므로, 감사·아웃박스도 남지 않는다.

- 연결 풀: `SET LOCAL`은 트랜잭션 끝에 풀린다. 호출부는 `DestroyerGateway` 한 클래스이고, `finally`에서 `RESET ROLE`도 부른다. 예외 뒤 같은 연결이 파기자 롤로 남지 않는지는 테스트가 본다(실패 주입 뒤 `current_user` 확인).
- Spring 트랜잭션·Hikari와 충돌하는 구체적 사실이 나오면 보고하고 두 커넥션 설계로 돌아간다(승인 Q2 단서).

### 5.5 지운 값의 해시 표현 (승인 Q3)

| 종류 | 감사에 남기는 것 | 이유 |
|---|---|---|
| 평문 자유 텍스트(추천·무효·정정 사유, `review.reason`) | `SHA-256(UTF-8)` | 사본을 가진 쪽이 나중에 대조하는 것이 목적 |
| 평문 외부 식별자(`policy_no`, `crm_customer_id`) | `SHA-256(UTF-8)` | 같은 목적. 정의역이 작지 않다 |
| JSONB 행동 기록(`view_evidence`) | `SHA-256(JCS(값))` | 정규화 바이트 |
| 암호문 BYTEA(`*_enc`, `wrapped_dek`) | `SHA-256(저장된 바이트)` | 평문 해시는 쓰지 않는다. 생년월일·전화번호는 평문 해시가 사실상 평문이다 |
| `signature.ip`(INET), `signature.device`(JSON) | 해시 없이 `{"present": true, "family": 4\|6}` / `{"present": true}` | 작은 정의역. 해시가 값 자체이다 |

### 5.6 개인정보 컬럼 전수 (설계서 §9 `pii-columns` 블록 후보, `PiiColumnTableTest`가 세 함수의 컬럼 집합·DB 카탈로그와 양방향 대조)

```pii-columns
table,column,kind,erasedBy,auditRepr,keeps
customer_ref,name_enc,ENCRYPTED,ga_customer_ref_destroy,sha256-stored,customer_ref·enc_key_id·created_at·destroyed_at
customer_ref,phone_enc,ENCRYPTED,ga_customer_ref_destroy,sha256-stored,same row
customer_ref,birth_date_enc,ENCRYPTED,ga_customer_ref_destroy,sha256-stored,same row
customer_ref,crm_customer_id,EXTERNAL_ID,ga_customer_ref_destroy,sha256-utf8,same row
document_key,wrapped_dek,KEY,ga_document_key_shred,sha256-stored,key_id·shredded_at·shredded_by
disclosure,void_reason_text,FREE_TEXT,ga_disclosure_destroy,sha256-utf8,void_reason_code
disclosure,supersede_reason_text,FREE_TEXT,ga_disclosure_destroy,sha256-utf8,supersede_reason_code
disclosure,policy_no,EXTERNAL_ID,ga_disclosure_destroy,sha256-utf8,disclosure_no·status·hashes·chain·times
recommendation,reason_text,FREE_TEXT,ga_disclosure_destroy,sha256-utf8,reason_code·item link
review,reason,FREE_TEXT,ga_disclosure_destroy,sha256-utf8,rule·target hash·approver·time
signature,device,DEVICE,ga_disclosure_destroy,presence,role·channel·method·signed_at·both hashes
signature,ip,NETWORK,ga_disclosure_destroy,presence-family,same row
signature,view_evidence,BEHAVIOR,ga_disclosure_destroy,sha256-jcs,same row
sign_session,view_evidence,BEHAVIOR,ga_disclosure_destroy,sha256-jcs,session status·times·pinned hashes
compliance_flag,policy_no,EXTERNAL_ID,ga_disclosure_destroy,sha256-utf8,type·status·resolution·times
customer_data_key,wrapped_key,KEY,RETAINED,-,tenant key lifecycle GD060-062 (not per customer)
disclosure,customer_ref,PSEUDONYM,RETAINED,-,tombstone link; meaningless once customer_ref is destroyed
audit_log,detail.customerRef,PSEUDONYM,RETAINED,-,hash chain; pseudonym only
outbox_event,payload.customerRef,PSEUDONYM,RETAINED,-,published contract history; pseudonym only
```

- **객체**(`document_artifact`의 CANONICAL_JSON·PDF·SIGNED_PDF·EVIDENCE_ZIP, `signature_evidence`의 STROKES·IMAGE·SCAN)는 컬럼이 아니라 ①·②로 지운다. 표 아래 문장으로 적고, 테스트는 `kind` 열거(`ArtifactKind`·`SignatureEvidenceKind`) 전부가 ② 경로에 있는지 본다.
- 지시문의 최소 목록 밖에서 더한 것(승인 Q4 — 경계 사례는 지우는 쪽, 식별자·해시·번호·시각만 예외): `crm_customer_id`, `policy_no`(두 곳), `review.reason`, `sign_session.view_evidence`. 구현 중 새로 찾는 컬럼도 표에 먼저 넣고 함수가 따라간다.
- 남기는 것: 직원 주체(`agent_id`, `signer_subject`, `actor_subject`, `placed_by` 등)는 고객 개인정보가 아니라 직원 업무 기록이다. 보존·파기는 Phase 6 인가·직원 수명주기와 함께 다룬다(Q4).
- **테스트의 "표에 없는 암호화 컬럼" 검출**: 카탈로그에서 BYTEA 컬럼 전부, `*_enc`·`wrapped_*` 이름, 그리고 V5의 암호문 형식 CHECK가 걸린 컬럼을 모은다. 표 또는 비개인정보 열거(`anchor_receipt.tsa_token` 등 FQN식 `table.column` 목록)에 없으면 실패한다.

---

## 6. `ArtifactStoreContract` 신규 항목 — SeaweedFS 실측 (⑥)

`chrislusf/seaweedfs@sha256:4e61d15f…`(4.48, 하네스와 같은 digest). AWS SDK v2로 실행한 스파이크 원문 요약(2026-10-03):

| 항목 | 결과 |
|---|---|
| 두 버전 + 버전 없는 삭제 → 나열 | `versions=2 markers=1` |
| 버전 2개·마커 1개를 ID로 각각 삭제 | 전부 OK → `versions=0 markers=0`, `HeadObject` 404 |
| 없는 버전 ID 삭제 | OK(오류 없음) |
| **없는 키를 버전 없이 삭제** | OK지만 **삭제 마커가 생긴다**(`versions=0 markers=1`) → 파기는 나열 기반 ID 삭제만 쓴다 |
| COMPLIANCE 5초 잠금 중 버전 삭제 | `403 AccessDenied` |
| 같은 삭제 + `x-amz-bypass-governance-retention` | `403 AccessDenied`(COMPLIANCE는 우회되지 않음 — 어댑터는 이 헤더를 보내지 않는다) |
| 잠금 만료 뒤 삭제 | OK → 0 |
| **과거 시각 retain-until 설정** | `400 InvalidRequest`(AWS와 같다 — §0 사실 2, Q6) |
| legal hold ON → 조회 | `ON` |
| hold 중 버전 삭제 | `403 AccessDenied` |
| hold OFF → 조회 → 삭제 | `OFF` → OK → 0 |
| 보존 만료 뒤에도 hold ON이면 삭제 | `403 AccessDenied`(보류가 보존과 독립) |
| 버전 ID 없이 hold ON/조회 | OK/`ON`(최신 버전에 걸림 — 어댑터는 언제나 모든 버전에 건다) |

- **legal hold는 지원된다.** `capabilities().legalHold = SUPPORTED`이고, 계약 항목 "설정·조회·해제·보류 중 삭제 거부"가 그대로 돈다.
- 어댑터 변경:
  - `delete(key)`가 삭제 마커도 지운다. 지금은 버전만 지운다.
  - `setLegalHold(key, on)`는 그 키의 **모든 버전**에 건다.
  - `capabilities()`를 추가한다.
- 미지원 저장소를 위한 `UNSUPPORTED`는 포트 계약에 남긴다. 미지원이면 예외로 명시하고, 조용한 no-op은 두지 않는다. 계약 테스트의 대역 구현(`FailingPorts`)으로 그 분기를 시험한다.
- 설계서 §9에는 "DB 보류가 통제(배치 건너뜀), S3 보류는 벨트"를 지원 여부와 무관하게 적는다.

계약 신규 항목: `deleteRemovesEveryVersionAndMarker`, `deletingAMissingKeyCreatesNothing`(버전별 삭제 뒤 마커 0), `adapterNeverSendsAVersionlessDelete`(승인 B1 — SDK 실행 인터셉터로 요청을 캡처해 모든 `DeleteObject`에 `versionId`가 있음을 단언), `lockedVersionDeleteIsRefused`(3B 재사용), `legalHoldSetGetRelease`, `heldVersionCannotBeDeletedEvenAfterRetention`(B1), `capabilitiesAreExplicit`.

---

## 7. 모듈 위치 (⑦)

**사실.** 파기·검증이 쓰는 포트(`ArtifactStore`·`DocumentCryptoPort`·`DocumentRecordStore`·`DisclosureStore`·`AuditPort`·`OutboxPort`·`TenantTransactions` 성격의 `WorkflowTransactions`)는 전부 `disclosure-workflow`에 있다. `disclosure-seal`이 아니다. 레이어 규칙은 `compliance → workflow`를 허용하지 않는다("Workflow may be used only by Api, Infra, App").

지시문 권장(compliance가 조정, seal 포트 호출)을 따르려면 포트를 옮기거나 규칙을 넓혀야 한다. 권장은 **레이어 규칙을 넓히지 않고** 다음처럼 두는 것이다(Q1).

| 코드 | 모듈·패키지 | 이유 |
|---|---|---|
| 머클 트리, `AnchorRecord`(잎), 경로 검증, 감사 체인 걷기(`AuditChain.breaks` 확장, 스트리밍), 봉인 체인 식, TSA 포트·검증·스텁, 영수증 형식 | `disclosure-audit`(Spring 무의존) | 지시문 위치 그대로. 순수 |
| `RetentionDecision`(판정 산식) | `disclosure-sign.retention` | 기존 `RetentionAnchors` 옆. 순수 |
| `AnchorJob`(A·B 단계), `DestructionJob`, `LegalHoldService`, `TenantVerifier`(`verify tenant` 러너 — 계산은 audit 함수), `ReceiptExporter` | `disclosure-workflow`(`workflow.anchor`·`workflow.retention`·`workflow.verify`) | 조정만. 필요한 포트가 전부 여기에 있다. Phase 4의 `ExpireService`·3B의 `ArtifactService.gc/reconcile`과 같은 배치 위치 |
| `PackageVerifier`(`verify package` 전체), 보고서 모델·직렬화 | `disclosure-audit`(`audit.verify`) | 승인 Q1. 증거 패키지를 **자체 리더**로 읽는다(ZIP·매니페스트 스키마·JCS·SHA-256·감사 행 해시) — seal의 생산자 코드에 의존하지 않으므로 검증이 생산자와 독립이다. ArchUnit 새 규칙: `audit.verify`는 seal·workflow·infra·`java.net`·`java.sql`·`javax.sql`·`javax.crypto`를 참조하지 않는다 |
| 파기 함수 호출기 `com.ga.disclosure.infra.retention.DestroyerGateway` | `disclosure-infra` | **직접 접근 허용 목록 세 번째 FQN**(롤 `disclosure_destroyer`, 별도 DataSource). 설계서 §9 표의 자리표시 행을 이 FQN으로 확정한다 |
| CLI | `disclosure-app` | 기존 `OperatorCli` 위임 패턴(`SignCommands`처럼 `AnchorCommands`·`VerifyCommands`·`RetentionCommands`) |

- `disclosure-compliance`는 Phase 6(준법 큐·징구율)까지 손대지 않는다.
- 테넌트 순회는 CLI가 `TenantDirectoryReader`(허용 목록 첫 항목)로 하고, 테넌트마다 workflow를 부른다.
- 앵커 B단계의 "전 테넌트 잎 모으기"는 테넌트 트랜잭션별 읽기의 합이다. 테넌트 없는 테이블·쿼리는 없다(G3).

---

## 8. 유스케이스와 트랜잭션

### 8.1 `AnchorJob.run(date, actor)` (기본 date = 오늘 KST, 시스템 행위자 `system:anchor`)

**A단계** — 테넌트마다 트랜잭션 1개(**REPEATABLE READ**, 승인 Q12)
1. 그 날짜 앵커가 있으면 NOOP.
2. 한 스냅샷에서 봉인 체인 머리(`disclosure_chain_head`)와 감사 머리(마지막 `audit_log` 행)를 읽는다.
3. `anchor` INSERT(GD110이 다시 계산).
4. 감사 `ANCHOR_CREATED`(seq = `audit_seq + 1`, 다음 앵커가 덮는다).
5. 스냅샷 뒤에 다른 트랜잭션이 감사 행을 커밋했다면 둘 중 하나로 끝난다.
   - 감사 적재(advisory lock 뒤 스냅샷의 머리를 읽는다)가 seq 중복(23505)으로 실패한다.
   - 직렬화 실패(40001)가 난다.
   둘 다 그 테넌트만 처음부터 재시도한다(상한 5회). 재시도 횟수는 보고서 `retries`에 테넌트별로 센다. 두 머리가 같은 스냅샷이고, `audit_seq`가 자기 감사 행 직전이라는 것은 성공한 시도에서만 성립한다.

**B단계** — 그 날짜의 영수증 없는 앵커 전부(테넌트별 읽기)
1. 트리 → 루트 → TSA → 응답 수락 검증.
2. 테넌트마다 트랜잭션에서 `anchor_receipt` INSERT(GD111이 경로를 다시 계산).
3. 감사 `ANCHOR_RECEIPT_STORED`(`batchId`·`root`·`genTime`).

- TSA 실패면 영수증 0이다. 다음 실행이 같은 날짜의 남은 앵커로 둘째 배치를 만든다(새 `batch_id`·루트·토큰). 보고서에 `batches`·`secondBatches`를 센다.
- 날짜가 지난 미고정 앵커도 B단계 대상이다. 날짜별로 따로 배치한다.

**매니페스트 `anchor` 필드**: 완료 트랜잭션에서 "완료 시점에 이미 있던 최신 앵커" `{anchorSeq, anchorDate, leafHash, sealChainSeq, auditSeq}`(없으면 null). 스키마에 객체 형태를 추가한다(추가형, CHECKSUMS). 기존 패키지는 다시 만들지 않는다.

### 8.2 `anchor receipt export --tenant T --disclosure X --out receipt.json`

내용은 다음과 같다. 감사 행은 넣지 않는다.
- `covering`: 문서의 `chainSeq` 이상이면서 `auditSeq ≥ audit.toSeq`인 첫 앵커 + 그 영수증.
- `previous`: 매니페스트 `anchor`가 가리키는 앵커의 영수증(있으면).
- `sealChain`: `previous.sealChainSeq + 1`(없으면 1) … `covering.sealChainSeq` 구간의 `{chainSeq, disclosureNo, canonicalHash, pdfHash, chainHash}`. 파기된 확인서도 묘비 해시로 들어간다.
- 스키마는 `contracts/verify/v1/anchor-receipt-export.schema.json`이다.

### 8.3 `verify package <zip> [--receipt] [--tsa-trust pem]`

지시문 목록 그대로이다. 추가로 영수증이 있으면 다음을 본다.
- 영수증 구간에서 문서 행의 `canonicalHash`·`pdfHash`가 매니페스트와 같은지(`RECEIPT_PATH_INVALID`).
- 구간 재계산이 `covering.sealChainHead`에 닿는지.
- 결론 상한: "이 문서는 {T1} 이전에 이 내용으로 존재했다"(T1 = `covering` 토큰의 genTime).
- 결론 하한(승인 Q13 대안, `previous`가 있을 때만): *"이 문서는 {anchorDate} 앵커의 봉인 체인 머리(seq N, 기록 시각 {created_at}) 뒤에 봉인되었다. 하한의 시각은 자체 기록이며 외부로 증명되는 것은 상한({T1} 이전)뿐이다."* 앵커 생성 시각 H ≤ 그 TSA 시각 T0이므로 "T0 이후"는 따라 나오지 않는다. 테스트가 두 문장을 각각 단언한다.

### 8.4 `verify tenant`

테넌트 트랜잭션(읽기 전용)에서 커서로 흘려 읽는다. 순서는 다음과 같다.
1. 감사 체인 → 봉인 체인 → 채번(연도별) → 객체(키별 복호화·해시, 파기 건은 버전·마커 0).
2. 앵커(각 seq 시점 머리 재계산은 1의 걷기 중에 기록해 둔 값과 대조).
3. 영수증(경로·루트·토큰).
4. 미고정 기간.

쓰기는 끝에 별도 트랜잭션 하나로 한다: `VERIFY_RUN` 감사 1행 + 불일치 시 `CHAIN_BROKEN` 플래그. 확인서를 정할 수 있으면 그 확인서, 아니면 `disclosure_id NULL` + `target_kind`이다.

### 8.5 `DestructionJob.run(tenant, asOf, dryRun, actor)`

- 후보 쿼리(테넌트 트랜잭션): 봉인된 종료 상태 ∧ `retention_until < today` ∧ 미파기. 상한 `--limit`.
- 확인서마다 §5.2. dry-run은 ⓪과 판정만 하고 쓰기 0이다(감사도 0, 보고서만).
- 고객 파기는 확인서 처리 뒤 같은 실행에서 한다.
- 테넌트당 요약 감사 1행 `DESTRUCTION_BATCH_RUN`. 감사 체인이 테넌트별이라 "요약 1행"을 테넌트별로 해석했다.
- 보고서 JSON(`contracts/verify/v1/destruction-report.schema.json`): 후보, 파기, 건너뜀 사유별, 실패, `anchorsWaived`, `holdAfterShred`.

### 8.6 보류

- `LegalHoldService.place(tenant, actor, target, reasonCode, reasonText)` / `release(…, holdId, releaseReasonCode)`. 감사는 `LEGAL_HOLD_PLACED|RELEASED`이고 `retention_until`은 바꾸지 않는다.
- 저장소 지원 시 대상 객체 전부(고객 보류면 그 고객의 봉인된 확인서 객체 전부)의 모든 버전에 hold를 켜고, 해제 때 끈다.
- 객체 hold 실패는 보고서에 남기고 DB 보류는 유지한다. DB 보류가 통제이다.

### 8.7 룰 데이터 변경 (번들 제자리 재해시 — `released-bundles.txt` 비어 있음)

| 키 | 위치 | 비고 |
|---|---|---|
| `anchoring: {treeDepth: 16}` | GLOBAL, 비오버라이드(스키마 `not: enum`에 추가) | 기존 `anchor`(테넌트 오버라이드 가능)를 대체한다(Q7) |
| `retentionDays`(정수 ≥ 0, 기본 0) | GLOBAL, 비오버라이드 | **산식은 하나**: `앵커 + retentionYears + retentionDays`. `retentionYears` 최소 1 → 0으로 풀되 **룰 스키마가 합계 ≥ 1일을 강제**한다(승인 Q5, `retentionYears ≥ 1 ∨ retentionDays ≥ 1`). 데모 짧은 보존 번들은 `0년 1일` |
| `retention: {contractLinkWaitDays}` | 오버라이드 가능 | 판정 시점 룰(Q10) |
| `legalHoldReasons`(닫힌 코드 목록), `legalHoldReasonTextMaxLength` | GLOBAL | `voidReasons`와 같은 형태 |
| `customerRef: {graceDaysAfterLastDestruction, abandonedDays}` | 오버라이드 가능 | |
| `verify: {unstampedAnchorAlertDays}` | GLOBAL | |

- `retentionYears`·`retentionDays`는 지금처럼 GLOBAL로 둔다. 짧은 보존 데모 테넌트는 **데모 전용 GLOBAL 번들**(`DISC-DEMO-SHORT`, `retentionYears 0`·`retentionDays 1`)을 그 테넌트에만 배포해 만든다(`rules distribute --tenants DEMO3`). 테넌트 오버라이드로 법정 보존을 줄이는 길은 열지 않는다(Q5).

### 8.8 데모 (지시문 §6)

- `seed.sh` Phase 5 절. 1회째는 다음 순서이다.
  1. `anchor run --date <어제>`: 첫 날. 그 전에 완료된 A-2는 매니페스트 `anchor=null`.
  2. 그 뒤 완료되는 건 1건(예: A-4-SCAN)은 매니페스트 `anchor` = 첫 날 앵커이고, 둘째 날 앵커가 덮는다.
  3. `anchor run`(오늘): 둘째 날.
  4. `anchor receipt export` → `verify package`(영수증 없음 1회, 영수증 + `--tsa-trust` 1회, 둘 다 종료 0) → `verify tenant` 종료 0.
  5. DEMO3(짧은 보존, Q5·Q6) 확인서 1건 `retention destroy --as-of` → `verify tenant` 종료 0(묘비).
  6. DEMO3 둘째 건에 보류를 걸고 파기 시도 → `HOLD`.
- 2회째: 새 앵커·영수증·파기 0, 전부 NOOP.
- **날짜 문제**: "어제" 앵커는 `--date`로 만들 수 있다. 다만 A단계는 그 날짜의 머리를 지금 읽으므로, 실제로는 "지금 머리를 어제 날짜로 기록"하게 된다. 그래서 `anchor run --date`는 오늘 이후·미래를 거부하고, 과거 날짜는 **그 날짜 앵커가 없고 이후 날짜 앵커도 없을 때만** 허용한다(`anchor_date` 단조 — GD110). 데모는 이 경로로 두 날을 만든다. 운영 의미는 "누락된 날을 늦게 채움"이고, `created_at`이 실제 시각을 남긴다.

### 8.9 데모 시계 오프셋과 이미 끝난 보존 (승인 Q6)

- (a) `ga.demo.clock-offset`(ISO 기간, 예 `-P3D`)은 **데모 프로파일 설정 클래스에만** 있다(`@Profile("demo")` 구성의 `@ConfigurationProperties`).
  - 운영 프로파일에는 그 키를 읽는 코드 자체가 없다.
  - 운영 프로파일에서 그 키가 설정돼 있으면 기동 시 실패한다(알 수 없는 `ga.demo.*` 키 거부 검사).
  - 테스트 두 가지: 운영 프로파일 + 키 → 기동 실패, 데모 프로파일 + 키 → 오프셋 시계.
- (b) `RetentionLocks`: `retainUntilInstant(retention_until) ≤ clock.instant()`이면 S3 호출 없이 `retention_applied_until`을 기록한다. 감사 사유는 `RETENTION_ALREADY_ELAPSED`이다.
  - 봉인·완료 직후에는 Q5의 합계 ≥ 1일 때문에 생길 수 없다. 테스트: 최소 보존(0년 1일)으로 봉인한 직후 이 사유 0, 같은 확인서를 보존 만료 뒤 reconcile하면 1.
  - 데모 흐름: DEMO3을 오프셋 시계로 봉인한다. 실제 시각으로는 과거인 retain-until이라 S3가 400을 내고, 잠금은 보류되며 봉인은 유효하다(3B 규약). 이어서 실제 시계의 `artifacts reconcile --tenants DEMO3`가 `RETENTION_ALREADY_ELAPSED`로 기록하고, 그 뒤 파기한다.

---

## 9. 완료 기준 ↔ 테스트

| # | 테스트 | 핵심 |
|---|---|---|
| G1 | `AnchorJobIT` | 테넌트·날짜당 1행(재실행 NOOP, 새 토큰 0), 잎 = JCS 해시(앱·DB 일치), 두 머리 한 시점(봉인 병행 주입 시에도 앵커 머리 = 그 seq의 실제 값), `audit_seq` = 자기 `ANCHOR_CREATED` 직전, UTC 자정을 넘는 하나의 KST 날, GD110 위반 INSERT 거부 |
| G2 | `MerkleTreeTest`·`MerkleSpecTableTest` | 잎 1·2·17개 경로 길이 = d, 임의 잎 재계산 = 루트, 패딩 상수값 고정, 잎을 노드로·노드를 잎으로 제시 → 실패, `2^d + 1` 거부, 규격 블록 ↔ 상수 양방향, DB GD111 경로 재계산 = 앱 |
| G3 | `AnchorIsolationIT`·ArchUnit | A의 영수증·경로·앵커 JSON에 B의 ID·머리 문자열 0, B의 테넌트로 A 영수증 INSERT → RLS 거부, 직접 접근 허용 목록 FQN 열거에 `DestroyerGateway` 추가·그 외 없음 |
| G4 | `TsaStubTest`·`AnchorJobIT`·ArchUnit | 스텁 토큰 VALID, 루트·nonce·인증서(다른 키) 변조 각각 실패, 신뢰 앵커 없음 → UNTRUSTED, TSA 불가 주입 → 앵커 있음·영수증 없음 → 다음 실행 둘째 배치, `org.bouncycastle..` 참조 범위, 락 파일에 1.86 |
| G5 | `VerifyPackageTest`(DB 없이, Phase 4 골든 패키지 + 테스트용 영수증) | 정상 0, 변조 7종 각각 2 + 코드, 손상 ZIP 3, 영수증 유·무 문장, 다른 문서 영수증 2(`RECEIPT_NOT_COVERING`/`RECEIPT_PATH_INVALID`) |
| G6 | `VerifyTenantIT` | 정상 0, 슈퍼유저 변조 5종(`chain_hash`, 감사 `entry_hash`, 객체 바이트, 앵커 잎, 영수증 경로) 각각 2 + `CHAIN_BROKEN`, 파기 건 부재 정상·존재 시 `OBJECT_NOT_DELETED`, 미고정 기간 초과 `ANCHOR_UNSTAMPED`. 앵커 잎·영수증 변조는 GD110·111이 INSERT를 막으므로, 트리거를 끈 슈퍼유저 세션으로 주입 |
| G7 | `DestroyerRoleIT` | 카탈로그: 롤 권한 = 세 함수 EXECUTE뿐, 앱 롤·파기 롤의 직접 UPDATE 거부, 함수가 보존 미도래·활성 보류·기파기 거부, 앱 롤이 `ga.destroy`를 세우고 UPDATE → 거부, 지정 외 컬럼 변경 거부 |
| G8 | `DestructionOrderIT` | ⓪ 미만료 → 변경 0(키 포함), ① 뒤 중단 주입 → 재실행 ②③만(①감사 1행), ② 뒤 중단 → 이어서 ③, 저장소 잠금 거부 주입 → ③ 미진입, 끝나면 버전·마커 0 |
| G9 | `TombstoneIT` | 파기 전후 행 비교(지정 컬럼만 NULL), 감사 해시 = 사전 계산값, 봉인 체인 재계산·채번·`verify tenant` 0 |
| G10 | `RetentionDecisionTest`·`DestructionRulesAsDataIT` | 산식 전수(상태 × 앵커 날짜 유무 × 대기 경과 × 보류), 룰 데이터만 바꿔 대기·유예 변경 |
| G11 | `LegalHoldIT`·`ArtifactStoreContract` | 설정 → HOLD, 해제 → 다음 실행 파기, 감사 2행, 대상당 활성 1건(유일 인덱스), `retention_until` 불변, SeaweedFS hold 켬·끔 |
| G12 | `CustomerRefDestructionIT` | live 있으면 건너뜀(초안 포함), 0건 + 유예 경과 → NULL, 파기 뒤 UPDATE 거부(GD113), DELETE 여전히 GD064, 감사 해시 |
| G13 | `PiiColumnTableTest` | 블록 ↔ 세 함수 컬럼 집합(함수 소스를 `pg_proc`에서 읽어 UPDATE 대상 컬럼 추출) 양방향, 한 줄 제거 시 실패, 카탈로그의 암호문·키 컬럼이 표 또는 비개인정보 열거에 없으면 실패 |
| G14 | 빌드 로그·보고서 | 아래 주입 |

**G14 주입 목록**(지시문 최소 8종 + 계획 추가):
1. 도메인 접두 제거(잎 = SHA-256(JCS)).
2. 패딩 깊이 가변(잎 수에 맞춘 최소 깊이).
3. 트리거의 롤·표식 검사 제거.
4. ⓪ 잠금 확인 제거.
5. 파기 감사 해시 생략.
6. `verify tenant`의 감사 연속(prev) 검사 생략.
7. 다른 테넌트 영수증 수용.
8. TSA 신뢰 앵커 검사 생략.

계획 추가:
- GD110 잎 재계산 제거.
- GD111 경로 재계산 제거.
- 삭제 마커 남기기(`delete`가 버전만).
- 파기 함수가 감사 seq 대조 생략.
- `PackageVerifier`가 `java.net` 참조(ArchUnit).
- 지정 외 컬럼 하나 소거.
- `pii-columns` 블록 한 줄 제거.

승인 B2:
- `SET LOCAL ROLE` 없이 앱 롤로 파기 함수 호출 → 거부.
- 함수 표식 없이 파기자 롤의 직접 UPDATE → 거부.
- 데모 시계 오프셋 키를 운영 프로파일에 넣기 → 기동 실패.

승인 B3: `RETENTION_ALREADY_ELAPSED` 감사 스캔.
- integrationTest 세션이 끝날 때 공유 PostgreSQL 컨테이너의 모든 테넌트 감사에서 이 사유를 센다(평문 스캔과 같이 테스트 실행 전체를 대상으로).
- 이 사유를 의도적으로 만드는 테스트 클래스가 만든 테넌트(FQN 열거 허용 목록)가 아닌 곳에 1건이라도 있으면 실패한다.
- 주입: 봉인 경로에서 이 분기를 강제 → 스캔 실패.

---

## 10. 순서 (승인 후)

1. 계획 갱신(승인 반영) 1커밋.
2. **V9** + `init-roles.sql`·하네스의 파기 롤 + `db-error-codes.md` + 트리거 IT(GD110~114) + `DestroyerRoleIT` + 주입.
3. **룰 데이터**: `anchoring`·`retentionDays`·`retention`·`legalHold*`·`customerRef`·`verify`, `anchor` 대체, 데모 짧은 보존 번들, 번들 재해시·CHECKSUMS.
4. **audit 순수**: 머클·잎·규격 블록, 체인 걷기, TSA 포트·검증·스텁(BC 1.86 고정, 서드파티 목록), ArchUnit.
5. **저장소**: `delete` 마커 처리, `setLegalHold`, `capabilities`, 계약 신규 항목.
6. **앵커**: `AnchorJob`, 매니페스트 `anchor` 필드(스키마 추가형).
7. **검증**: `PackageVerifier`, `ReceiptExporter`, `TenantVerifier`, 보고서 스키마.
8. **파기·보류**: `RetentionDecision`, `DestroyerGateway`(별도 DataSource), `DestructionJob`, `LegalHoldService`, 고객 파기, `DisclosureDestroyed` 이벤트.
9. **CLI·데모**(Q5·Q6·Q11), 데모 2회 기록.
10. **보고서**(지시문 추가 6항목), PR, CI 1차 증거, 태그 `phase-5`. **병합은 수용 심사 회신 뒤.**

---

## 11. 질문 (권장안 먼저) — 2026-10-03 승인: Q2·Q13 대안, 나머지 권장안(Q12는 승인 문구대로 REPEATABLE READ)

**Q1. 배치 코드 위치(⑦).** 권장: 순수 코드는 `disclosure-audit`(+ 판정 산식은 `disclosure-sign.retention`)에 둔다. 앵커·파기·검증 배치와 `PackageVerifier`는 `disclosure-workflow`에 두고, 레이어 규칙은 넓히지 않는다. 필요한 포트가 전부 workflow에 있어서다. `PackageVerifier`의 오프라인성은 ArchUnit 새 규칙으로 강제한다.
- 대안: 지시문 권장대로 compliance가 조정. `ArtifactStore`·`DocumentCryptoPort` 등을 seal로 옮기거나, `compliance → workflow`를 허용해야 한다.

**Q2. 파기 함수의 원자성(§5.4).** 권장: 파기 롤 연결이 advisory lock을 먼저 잡는다. 앱이 감사 행·아웃박스 envelope을 계산하고, 함수가 seq·prev를 대조해 소거와 같은 트랜잭션에서 INSERT한다.
- 대안: 함수는 소거 + 내부 기록만 하고 감사·아웃박스는 앱이 뒤따라 쓴다. 상태 변경과 이벤트가 갈라진다.

**Q3. 지운 값의 해시 표현(§5.5).** 권장: 암호문 컬럼은 저장 바이트의 해시(평문 해시 금지), `signature.ip`는 해시 없이 존재·주소 체계만. 지시문 문언("지운 평문의 해시")과 다르다.
- 대안: 문언대로 평문 해시. 생년월일·IP는 해시로부터 사실상 복원된다.

**Q4. 개인정보 컬럼 범위(§5.6).** 권장: 지시문 최소 목록에 `crm_customer_id`, `disclosure.policy_no`, `compliance_flag.policy_no`, `review.reason`, `sign_session.view_evidence`를 더한다. `subject_policy.policy_no`(Phase 6 테이블)와 직원 주체는 Phase 6에서 다룬다.

**Q5. 짧은 보존 테넌트.** 권장: `retentionDays`를 추가해 산식 하나(`P{y}Y{d}D`)로 둔다. 보존 키는 계속 GLOBAL 전용이다. 데모·테스트의 짧은 보존은 그 테넌트에만 배포하는 데모 전용 GLOBAL 번들로 만든다.
- 대안: 보존 키를 테넌트 오버라이드 가능하게 연다. 사규로 법정 보존을 줄일 수 있게 된다.

**Q6. 데모에서 "잠금이 실제로 만료"(§0 사실 2).** 권장은 두 가지를 함께 하는 것이다.
- (a) 데모·CLI 프로파일 전용 시계 오프셋 `ga.clock.offset`(예 `-P3D`, 운영 프로파일에서는 기동 거부). DEMO3 시드만 이 오프셋으로 실행한다.
- (b) `RetentionLocks`: `retainUntilInstant ≤ now`면 S3 호출 없이 `retention_applied_until`을 기록한다. 이미 끝난 보존기간에는 걸 잠금이 없고, 저장소는 과거 시각을 거부한다.
- 대안: 데모의 파기 단계를 "다음 날 다시 실행"으로 둔다. 한 번의 실행으로 끝나지 않고, 2회째 NOOP 기준과도 어긋난다.

**Q7. 기존 룰 키 `anchor`(테넌트 오버라이드 가능 `{externalTimestamp, tsaProfile}`).** 권장: GLOBAL 비오버라이드 `anchoring {treeDepth}`로 대체한다. TSA 엔드포인트·신뢰 앵커는 배포 설정이다. 루트가 전 테넌트 하나이므로 테넌트별 TSA 선택·해제는 성립하지 않는다. 설계서 §6.7의 "TSA 사업자 선택·비용은 테넌트 몫" 문장도 고친다.

**Q8. V1 `audit_anchor` 테이블.** 권장: V9에서 DROP한다. 쓰는 코드가 없고 `anchor`가 대체한다. 시드·테스트 참조도 옮긴다.

**Q9. `retention_until ≤ asOf`의 의미.** 권장: 날짜 비교 `retention_until < date_KST(asOf)`. 기한 당일 끝까지 보존하고, 잠금 기한(다음 날 00:00 KST)과 같은 경계이다.

**Q10. 파기 정책 키의 룰 버전.** 권장: `retentionAnchors`는 확인서에 고정된 룰에서 읽는다. 보존기한을 만든 정의이기 때문이다. `contractLinkWaitDays`·`customerRef.*`·`legalHoldReasons`·`verify.*`는 **판정 시점에 해석한 룰**에서 읽는다. 운영 정책이고, 기존 확인서의 고정 룰에는 이 키가 없다.

**Q11. 데모 TSA 스텁 키.** 권장: 저장소 밖 `~/.ga-disclosure/tsa-stub.p12`에 처음 한 번 만들고 재사용한다(로컬 KEK 파일과 같은 규약). 신뢰 앵커 인증서는 `tsa-trust.pem`이다.
- 대안: 실행마다 생성하고 신뢰 번들을 누적한다.

**Q12. 앵커 A단계의 격리 수준.** 권장: REPEATABLE READ 대신 READ COMMITTED + 잠금(체인 머리 `FOR SHARE` → 감사 advisory lock)이다. RR 스냅샷은 첫 문장에서 잡혀, 잠금 대기 뒤에 직렬화 실패를 낸다. 잠금 후 읽기가 더 강한 "한 시점"을 보장한다.

**Q13. "{T0} 이후" 결론의 문장.** 직전 앵커는 그 시점의 체인 머리를 고정할 뿐이다. 그래서 문서가 그 이후에 **봉인됐다**(체인상 뒤에 있다)는 것은 말할 수 있지만, 내용이 그 이후에 처음 존재했다는 것은 아니다. 권장 문장: "이 문서는 {T0}(직전 앵커) 이후에 봉인 체인에 들어갔다."

**Q14. 설계서 §12 Phase 5의 "내보내기 워터마크".** 지시문에 없다. 권장: 내보내기 API(Phase 6)로 옮긴다. 지금은 CLI 내보내기(`artifacts get`)에 워터마크가 없다는 사실만 설계서에 적는다.

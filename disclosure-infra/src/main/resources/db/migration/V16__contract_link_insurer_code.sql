-- =============================================================================================
-- V16: V14 계약 연결 테이블의 보험사 코드 CHECK 오기 수정(6B 5단계에서 발견).
--   V14는 contract_link·contract_link_unmatched.insurer_code를 '^[A-Z0-9_]{1,32}$'로 검사했다 — 이 시스템의 보험사 코드는 엔진 계약 1.2.0
--   InsurerCode(도메인 Patterns.INSURER_CODE, 카탈로그 계약)와 같은 '^[A-Z0-9][A-Z0-9-]{0,7}$'(예: INS-A)다. 하이픈 코드가 거부되고 밑줄 코드가
--   통과했다. 계약 contracts/contract-link/v1도 같은 패턴이다. 다른 값(출처·출처 참조·연결자)의 검사는 그대로 둔다.
-- 기존 V* 파일은 고치지 않는다 — 제약은 DROP/ADD.
-- =============================================================================================
ALTER TABLE contract_link DROP CONSTRAINT ck_contract_link_codes;
ALTER TABLE contract_link ADD CONSTRAINT ck_contract_link_codes CHECK (insurer_code ~ '^[A-Z0-9][A-Z0-9-]{0,7}$' AND source ~ '^[A-Z][A-Z0-9_]{0,31}$'
                                                                       AND btrim(source_ref) <> '' AND btrim(linked_by) <> '');
ALTER TABLE contract_link_unmatched DROP CONSTRAINT ck_contract_link_unmatched_values;
ALTER TABLE contract_link_unmatched ADD CONSTRAINT ck_contract_link_unmatched_values CHECK (policy_no ~ '^\S{1,64}$'
                                                                                            AND insurer_code ~ '^[A-Z0-9][A-Z0-9-]{0,7}$'
                                                                                            AND source ~ '^[A-Z][A-Z0-9_]{0,31}$'
                                                                                            AND btrim(source_ref) <> '');

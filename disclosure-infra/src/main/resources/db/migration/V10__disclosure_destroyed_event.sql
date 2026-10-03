-- =============================================================================================
-- V10: 계약 이벤트 DisclosureDestroyed(Phase 5 파기 ③, 5 계획 §1.6 — 추가형). V8 ck_outbox_event_type의 허용 목록에 더한다.
-- V9가 함께 넓혔어야 했다(5 계획 §1.6에 이벤트 추가가 있었으나 DB 목록을 빠뜨렸다) — 기존 마이그레이션은 고치지 않으므로 V10으로 더한다.
-- payload는 식별자·번호·시각뿐(contracts/events/v1/payloads/DisclosureDestroyed.schema.json).
-- =============================================================================================
ALTER TABLE outbox_event DROP CONSTRAINT ck_outbox_event_type;
ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_event_type
    CHECK (type IN ('DisclosureCreated', 'DisclosureSealed', 'SignatureCaptured', 'DisclosureCompleted', 'DisclosureVoided', 'DisclosureSuperseded',
                    'PolicyLinked', 'ComplianceFlagRaised', 'DisclosureDestroyed'));

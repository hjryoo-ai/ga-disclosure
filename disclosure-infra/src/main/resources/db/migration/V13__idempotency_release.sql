-- =============================================================================================
-- V13: 저장하지 않는 응답 뒤의 멱등 키 해제(6A 수용심사 §2 ②).
--   규칙: 유스케이스에 닿은 요청만 키를 묶는다. 2xx·409·422만 완료로 저장하고, 400·401·404·428·5xx 뒤에는 그 청구 순번의
--   진행 중 행을 지운다 — 같은 키로 고친 본문이 오면 새로 청구해 정상 처리한다(V12까지는 만료까지 다른 본문이 422 REUSED였다).
--   · ga_idempotency_key_guard의 DELETE 분기만 바뀐다: 완료 행은 여전히 만료 뒤에만 지운다(GD120). 진행 중 행은 언제든.
--   · 앱은 청구 순번이 같을 때만 지운다(임차를 넘겨 다른 요청이 인수한 행은 그쪽 것이다).
--   · 함수 교체는 소유자(마이그레이터)로 한다. 권한은 CREATE OR REPLACE가 유지한다.
-- =============================================================================================

CREATE OR REPLACE FUNCTION ga_idempotency_key_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'idempotency_key is purged row by row after expiry' USING ERRCODE = 'GD120';
    END IF;
    IF TG_OP = 'DELETE' THEN
        -- 진행 중 행은 만료 전에도 지울 수 있다(해제 — 유스케이스에 닿지 않았거나 저장하지 않는 응답). 완료 행은 만료 뒤에만.
        IF OLD.expires_at >= now() AND OLD.response_status IS NOT NULL THEN
            RAISE EXCEPTION 'completed idempotency key of % is live until %', OLD.actor_subject, OLD.expires_at USING ERRCODE = 'GD120';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.claim_seq <> 1 OR NEW.response_status IS NOT NULL OR NEW.claimed_at <> NEW.created_at THEN
            RAISE EXCEPTION 'idempotency key is first claimed in progress' USING ERRCODE = 'GD120';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE
    IF OLD.response_status IS NOT NULL THEN
        RAISE EXCEPTION 'a completed idempotency key never changes' USING ERRCODE = 'GD120';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['claim_seq', 'claimed_at', 'response_status', 'response_ref', 'response_hash'])
       IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['claim_seq', 'claimed_at', 'response_status', 'response_ref', 'response_hash']) THEN
        RAISE EXCEPTION 'idempotency key identity, request hash and expiry are fixed' USING ERRCODE = 'GD120';
    END IF;
    IF NEW.response_status IS NULL THEN
        -- 임차 인수: 순번 + 1, 청구 시각 전진
        IF NEW.claim_seq <> OLD.claim_seq + 1 OR NEW.claimed_at <= OLD.claimed_at THEN
            RAISE EXCEPTION 'an in-progress idempotency key is only taken over (claim + 1, later claim time)' USING ERRCODE = 'GD120';
        END IF;
    ELSIF NEW.claim_seq <> OLD.claim_seq OR NEW.claimed_at <> OLD.claimed_at THEN
        RAISE EXCEPTION 'completion records the response only' USING ERRCODE = 'GD120';
    END IF;
    RETURN NEW;
END
$$;

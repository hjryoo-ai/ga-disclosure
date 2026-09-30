package com.ga.disclosure.infra.engine;

import com.ga.platform.core.tenant.TenantId;

/**
 * 엔진 HTTP 전송(경로와 본문 바이트만 다룬다). 운영은 {@link HttpEngineTransport}, 데모는 프로세스 안 스텁. 응답을 받으면 상태 코드와 무관하게
 * 돌려주고, 연결 실패·타임아웃은 {@link com.ga.disclosure.workflow.disclosure.EngineUnavailableException}.
 */
public interface EngineTransport {

    String GRADES_PATH = "/internal/v1/disclosure/commission-grades";

    Response post(TenantId tenant, String path, byte[] body);

    Response get(TenantId tenant, String path);

    /** 받은 응답(본문은 원문 바이트 — 해석은 클라이언트가 계약 스키마로). */
    record Response(int status, byte[] body) {
        public Response {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}

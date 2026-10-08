package com.ga.disclosure.api.rest;

import com.ga.disclosure.api.dto.AgentSignatureRequest;
import com.ga.disclosure.api.dto.ClientContext;
import com.ga.disclosure.api.dto.ManagerConfirmationRequest;
import com.ga.disclosure.api.dto.SessionIssueReceipt;
import com.ga.disclosure.api.dto.SessionIssueRequest;
import com.ga.disclosure.api.dto.SignReceipt;
import com.ga.disclosure.api.mapper.DisclosureMapper;
import com.ga.disclosure.api.mapper.SignMapper;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.disclosure.SignSessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 확인서별 서명 경로(6A 계획 §4.1): 고객 서명 세션 발급, 설계사 서명, 관리자 확인, 종이 스캔 검토, 완료. 세션 발급 응답은 {@code no-store}(현장 기기 토큰 —
 * 멱등 재생하지 않는다).
 */
@RestController
@RequestMapping("/api/v1/disclosures")
public class SignaturesController {

    private final SignSessionService sessions;
    private final SignService signing;

    public SignaturesController(SignSessionService sessions, SignService signing) {
        this.sessions = sessions;
        this.signing = signing;
    }

    @PostMapping("/{id}/sign-sessions")
    public ResponseEntity<SessionIssueReceipt> issue(Caller caller, @PathVariable("id") String id, @RequestBody SessionIssueRequest request) {
        SessionIssueReceipt receipt = SignMapper.issued(sessions.issue(caller, DisclosureMapper.id(id),
                SignMapper.channel(request == null ? null : request.channel())), SignMapper.channel(request.channel()));
        // 일회용 자격(현장 기기 토큰)을 담은 응답만 no-store — 멱등 완료가 409 IDEMPOTENCY_NOT_REPLAYABLE이 된다. 원격 링크 영수증은 토큰이 없어 재생된다
        return receipt.deviceToken() == null ? ResponseEntity.status(HttpStatus.CREATED).body(receipt)
                : ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(receipt);
    }

    @PostMapping("/{id}/agent-signature")
    public SignReceipt agentSignature(Caller caller, ClientContext client, @PathVariable("id") String id,
                                      @RequestBody(required = false) AgentSignatureRequest request) {
        return SignMapper.signed(signing.agentSign(caller, DisclosureMapper.id(id), SignMapper.capture(request, client)));
    }

    @PostMapping("/{id}/manager-confirmation")
    public SignReceipt managerConfirmation(Caller caller, @PathVariable("id") String id, @RequestBody ManagerConfirmationRequest request) {
        return SignMapper.signed(signing.managerConfirm(caller, DisclosureMapper.id(id), SignMapper.flags(request)));
    }

    @PostMapping("/{id}/paper-scan-review")
    public SignReceipt paperScanReview(Caller caller, @PathVariable("id") String id) {
        return SignMapper.signed(signing.reviewPaperScan(caller, DisclosureMapper.id(id)));
    }

    @PostMapping("/{id}/complete")
    public SignReceipt complete(Caller caller, @PathVariable("id") String id) {
        return SignMapper.signed(signing.complete(caller, DisclosureMapper.id(id)));
    }
}

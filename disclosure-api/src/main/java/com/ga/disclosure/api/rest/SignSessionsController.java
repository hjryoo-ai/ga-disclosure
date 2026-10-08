package com.ga.disclosure.api.rest;

import com.ga.disclosure.api.dto.IdentityReceipt;
import com.ga.disclosure.api.dto.PaperScanRequest;
import com.ga.disclosure.api.dto.SignReceipt;
import com.ga.disclosure.api.dto.TokenRequest;
import com.ga.disclosure.api.mapper.SignMapper;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.disclosure.SignSessionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 현장 기기 세션의 설계사 측 동작(6A 계획 §4.1): 대면 본인확인, 종이 스캔 업로드. 세션 토큰은 본문으로. */
@RestController
@RequestMapping("/api/v1/sign-sessions")
public class SignSessionsController {

    private final SignSessionService sessions;
    private final SignService signing;

    public SignSessionsController(SignSessionService sessions, SignService signing) {
        this.sessions = sessions;
        this.signing = signing;
    }

    @PostMapping("/face-to-face")
    public IdentityReceipt faceToFace(Caller caller, @RequestBody TokenRequest request) {
        return SignMapper.identity(sessions.confirmFaceToFace(caller, SignMapper.token(request == null ? null : request.token())));
    }

    @PostMapping("/paper-scan")
    public SignReceipt paperScan(Caller caller, @RequestBody PaperScanRequest request) {
        return SignMapper.signed(signing.uploadPaperScan(caller, SignMapper.token(request == null ? null : request.token()), SignMapper.scan(request)));
    }
}

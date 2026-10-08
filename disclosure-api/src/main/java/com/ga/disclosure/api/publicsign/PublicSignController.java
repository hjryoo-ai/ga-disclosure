package com.ga.disclosure.api.publicsign;

import com.ga.disclosure.api.dto.ClientContext;
import com.ga.disclosure.api.dto.PublicCaptureReceipt;
import com.ga.disclosure.api.dto.PublicCaptureRequest;
import com.ga.disclosure.api.dto.PublicIdentityResult;
import com.ga.disclosure.api.dto.PublicSessionStatus;
import com.ga.disclosure.api.dto.PublicStatusView;
import com.ga.disclosure.api.dto.PublicToken;
import com.ga.disclosure.api.dto.PublicVerifyRequest;
import com.ga.disclosure.api.dto.PublicViewRequest;
import com.ga.disclosure.api.mapper.PublicSignMapper;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.disclosure.SignSessionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 공개 서명(6A 계획 §5.1, 전부 POST). 토큰은 게이트가 헤더·본문에서 꺼내 넘긴다({@link PublicToken}) — 경로·질의에는 없다. 토큰 거부·형식 오류·예외는
 * 게이트가 같은 404 바이트로 바꾸고, 업무 거부(유효 토큰 보유자)만 422.
 */
@RestController
@RequestMapping("/public/v1/sign")
public class PublicSignController {

    private final SignSessionService sessions;
    private final SignService signing;

    public PublicSignController(SignSessionService sessions, SignService signing) {
        this.sessions = sessions;
        this.signing = signing;
    }

    @PostMapping("/open")
    public ResponseEntity<byte[]> open(PublicToken token) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(sessions.open(token.raw()));
    }

    @PostMapping("/view")
    public PublicSessionStatus view(PublicToken token, @RequestBody(required = false) PublicViewRequest request) {
        sessions.recordView(token.raw(), PublicSignMapper.scrollComplete(request), PublicSignMapper.viewSeconds(request));
        return new PublicSessionStatus("OPEN");
    }

    @PostMapping("/verify-identity")
    public PublicIdentityResult verify(PublicToken token, @RequestBody(required = false) PublicVerifyRequest request) {
        return PublicSignMapper.identity(sessions.verify(token.raw(), PublicSignMapper.inputs(request)));
    }

    @PostMapping("/capture")
    public PublicCaptureReceipt capture(PublicToken token, ClientContext client, @RequestBody PublicCaptureRequest request) {
        return PublicSignMapper.captured(signing.capture(token.raw(), PublicSignMapper.capture(request, client)));
    }

    @PostMapping("/status")
    public PublicStatusView status(PublicToken token) {
        return PublicSignMapper.status(sessions.status(token.raw()));
    }
}

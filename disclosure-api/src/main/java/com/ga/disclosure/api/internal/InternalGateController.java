package com.ga.disclosure.api.internal;

import com.ga.disclosure.api.dto.GateDecisionView;
import com.ga.disclosure.api.dto.GateRequest;
import com.ga.disclosure.api.mapper.GateMapper;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.gate.GateService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 청약 게이트(6B 계획 §6·승인 §4): {@code POST /internal/v1/gate} — 식별자가 액세스 로그·질의 문자열에 남지 않게 본문으로. 게이트 서비스 주체만(그 밖은
 * 404), 분당 한도 초과는 429. 질의라 멱등 키를 받지 않고 응답을 저장·재생하지 않는다(저장된 옛 판정이 재생되면 안 된다 — 멱등 예외 경로).
 */
@RestController
@RequestMapping("/internal/v1")
public class InternalGateController {

    private final GateService gate;

    public InternalGateController(GateService gate) {
        this.gate = gate;
    }

    @PostMapping("/gate")
    public GateDecisionView check(Caller caller, @RequestBody(required = false) GateRequest request) {
        return GateMapper.view(gate.check(caller, () -> GateMapper.query(request)));
    }
}

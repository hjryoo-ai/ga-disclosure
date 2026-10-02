package com.ga.disclosure.app.notify;

import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.sign.token.SignToken;
import com.ga.disclosure.workflow.sign.NotifyPort;

import java.io.PrintStream;
import java.util.Objects;

/**
 * 원격 서명 링크 콘솔 통지(4 계획 §7.6 — 실연동은 Phase 6). 표준 출력에만 쓰고 로거는 쓰지 않는다(토큰 원문이 로그 수집에 들어가지 않게). 번호는
 * 출력하지 않는다 — 발송 사실과 링크만. 데모 스크립트가 이 줄({@code SIGN LINK})에서 토큰을 받아 고객 경로를 이어 실행한다.
 */
public final class ConsoleSignLinkNotifier implements NotifyPort {

    private final String baseUrl;
    private final PrintStream out;

    public ConsoleSignLinkNotifier(String baseUrl, PrintStream out) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public void sendSignLink(Sensitive<PhoneNumber> to, SignToken token) {
        Objects.requireNonNull(to, "to");
        out.println("SIGN LINK " + baseUrl + token.reveal());
    }
}

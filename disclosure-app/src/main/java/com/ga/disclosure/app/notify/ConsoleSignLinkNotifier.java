package com.ga.disclosure.app.notify;

import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.workflow.sign.NotifyPort;
import com.ga.disclosure.workflow.sign.SignLink;

import java.io.PrintStream;
import java.util.Objects;

/**
 * 원격 서명 링크 콘솔 통지(데모·개발 — 실 사업자 어댑터는 운영 결정, 6A 계획 §7.2). 표준 출력에만 쓰고 로거는 쓰지 않는다(링크의 토큰이 로그 수집에 들어가지
 * 않게). 번호는 출력하지 않는다 — 링크만. 데모 스크립트가 이 줄({@code SIGN LINK})의 프래그먼트({@code #} 뒤)에서 토큰을 받아 고객 경로를 이어 실행한다.
 */
public final class ConsoleSignLinkNotifier implements NotifyPort {

    private final PrintStream out;

    public ConsoleSignLinkNotifier(PrintStream out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public void sendSignLink(Sensitive<PhoneNumber> to, SignLink link) {
        Objects.requireNonNull(to, "to");
        out.println("SIGN LINK " + link.reveal());
    }
}

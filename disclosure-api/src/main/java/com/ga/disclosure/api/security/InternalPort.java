package com.ga.disclosure.api.security;

/**
 * 내부 경로 전용 포트(Phase 8, 8 계획 승인 Q5 — 3중 분리의 앱 쪽 한 겹). {@code /internal/**}은 이 포트로만, 그 밖의 경로는 이 포트가 아닌 곳으로만 —
 * 어긋나면 없는 경로와 같은 404다(인그레스 경로 설정 하나가 틀려도 내부 경로가 공개 포트로 열리지 않는다). 포트는 서버가 뜬 뒤 정해진다(임의 포트 시험).
 */
public final class InternalPort {

    public static final String PREFIX = "/internal";

    private volatile int port = -1;

    public void set(int port) {
        this.port = port;
    }

    public int get() {
        return port;
    }

    /** 이 요청이 내부 포트로 들어왔는가(포트를 아직 모르면 아니다). */
    public boolean arrivedOn(int localPort) {
        return port > 0 && localPort == port;
    }

    /** 경로가 내부 접두인가(문맥 경로 뒤의 원 URI 기준 — 매칭 전 판정이라 원 URI를 본다. 인코딩된 접두는 라우트에 닿지 않는다). */
    public static boolean internalPath(String pathWithinApplication) {
        return pathWithinApplication.equals(PREFIX) || pathWithinApplication.startsWith(PREFIX + "/");
    }
}

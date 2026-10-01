package com.ga.disclosure.workflow.artifact;

/** 저장된 암호문이 기록된 문서 키·맥락(테넌트·확인서·종류)으로 풀리지 않는다 — 변조·다른 객체·옮긴 바이트. 메시지에 내용은 없다. */
public final class ArtifactUnreadableException extends RuntimeException {

    public ArtifactUnreadableException(Throwable cause) {
        super("stored artifact does not open with its document key and context", cause);
    }
}

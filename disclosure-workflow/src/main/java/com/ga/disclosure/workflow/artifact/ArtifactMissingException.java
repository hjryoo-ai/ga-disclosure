package com.ga.disclosure.workflow.artifact;

/** 저장소에 객체가 없다(키만 메시지에 싣는다 — 키는 테넌트·확인서 ID·종류·암호문 해시로 개인정보가 없다). */
public final class ArtifactMissingException extends RuntimeException {

    public ArtifactMissingException(String key) {
        super("no stored object " + key);
    }
}

package com.ga.disclosure.workflow.artifact;

/** 저장소가 그 기능을 지원하지 않는다({@link ArtifactStore#capabilities()} = UNSUPPORTED). 조용한 no-op 대신 이 예외로 알린다(5 계획 §6). */
public final class UnsupportedCapabilityException extends RuntimeException {

    public UnsupportedCapabilityException(String capability) {
        super("artifact store does not support " + capability);
    }
}

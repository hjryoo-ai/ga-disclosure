package com.ga.disclosure.workflow.artifact;

/** 저장소가 Object Lock으로 거부했다(잠긴 버전 삭제, 보존기한 단축). */
public final class ObjectLockedException extends RuntimeException {

    public ObjectLockedException(String key, Throwable cause) {
        super("object lock rejected the operation on " + key, cause);
    }
}

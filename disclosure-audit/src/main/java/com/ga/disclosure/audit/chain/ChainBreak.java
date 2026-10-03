package com.ga.disclosure.audit.chain;

import java.util.Objects;

/** 체인 걷기에서 찾은 어긋남 하나: 어느 seq에서 무엇이. 메시지는 seq와 종류뿐이다(행 내용·개인정보 없음). */
public record ChainBreak(long seq, Kind kind, long expectedSeq) {

    public enum Kind { SEQ_GAP, PREV_MISMATCH, HASH_MISMATCH }

    public ChainBreak {
        Objects.requireNonNull(kind, "kind");
    }

    public String message() {
        return switch (kind) {
            case SEQ_GAP -> "seq " + seq + " where " + expectedSeq + " was expected";
            case PREV_MISMATCH -> "seq " + seq + " prevHash does not link to the previous entry";
            case HASH_MISMATCH -> "seq " + seq + " entryHash does not match its content";
        };
    }
}

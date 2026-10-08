package com.ga.disclosure.api.error;

/** 요청 형식 오류(400 {@code MALFORMED_REQUEST}) — 필드 이름만 싣는다(값 없음). 매퍼가 던진다. */
public final class MalformedRequestException extends RuntimeException {

    private final String field;

    public MalformedRequestException(String field) {
        super("malformed request field " + field);
        this.field = field;
    }

    public String field() {
        return field;
    }
}

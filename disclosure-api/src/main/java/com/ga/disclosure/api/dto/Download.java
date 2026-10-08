package com.ga.disclosure.api.dto;

/** 바이트 그대로 내보내는 응답(산출물·앵커 영수증): 본문과 미디어 타입. */
public record Download(byte[] bytes, String mediaType) {

    public Download {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Download d && java.util.Arrays.equals(bytes, d.bytes) && mediaType.equals(d.mediaType);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(bytes) * 31 + mediaType.hashCode();
    }

    @Override
    public String toString() {
        return "Download[" + mediaType + ", " + bytes.length + " bytes]";
    }
}

package com.ga.disclosure.audit.tsa;

/**
 * TSA 전송 포트: DER {@code TimeStampReq}를 보내고 DER {@code TimeStampResp}를 받는다. 요청 생성·응답 수락은 {@link
 * TimestampClient}가 하므로 구현(스텁·HTTP)은 전송만 한다. 닿지 못하면 {@link TimestampFailure}({@code UNAVAILABLE}).
 */
@FunctionalInterface
public interface TimestampAuthorityPort {

    byte[] exchange(byte[] timeStampRequestDer);
}

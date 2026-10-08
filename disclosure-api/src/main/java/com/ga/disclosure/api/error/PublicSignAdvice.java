package com.ga.disclosure.api.error;

import com.ga.disclosure.api.publicsign.PublicSignController;
import com.ga.disclosure.api.security.PublicSignGate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 공개 서명 경로의 오류(6A 계획 §5.1·§5.2): 업무 거부만 422 {@code REJECTED}(유효 토큰 보유자 — 토큰을 가진 사람은 이미 그 세션을 안다). 그 밖의 모든 예외
 * (토큰 거부·형식 오류·내부 오류)는 게이트에 "거부"를 알린다 — 게이트가 상수 거부 바이트를 쓴다(내부 경로의 advice보다 먼저 적용된다).
 */
@RestControllerAdvice(assignableTypes = PublicSignController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicSignAdvice {

    private static final System.Logger LOG = System.getLogger(PublicSignAdvice.class.getName());

    @ExceptionHandler(RejectedOutcomeException.class)
    ResponseEntity<byte[]> rejected(RejectedOutcomeException e) {
        return Problem.rejections(HttpStatus.UNPROCESSABLE_CONTENT, e.rejections());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Void> reject(Exception e, HttpServletRequest request) {
        LOG.log(System.Logger.Level.INFO, "PUBLIC_SIGN_REJECTED " + e.getClass().getSimpleName());
        request.setAttribute(PublicSignGate.REJECT_ATTRIBUTE, Boolean.TRUE);
        return ResponseEntity.notFound().build();
    }
}

package com.ga.disclosure.workflow.authz;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 테넌트를 받지만 진입점이 아닌 public 메서드(다른 유스케이스가 자기 트랜잭션 안에서 부르는 내부 단계). 사유가 필수이고, 목록은
 * {@code AuthorizationCoverageTest}의 닫힌 FQN 열거와 같아야 한다.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface NotAnEntry {

    String value();
}

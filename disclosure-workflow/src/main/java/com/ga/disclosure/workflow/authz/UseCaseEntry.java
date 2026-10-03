package com.ga.disclosure.workflow.authz;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 유스케이스 진입점. 본문(같은 메서드의 람다 포함)에서 {@link AuthorizationPort#require}를 직접 부른다 — {@code AuthorizationCoverageTest}가
 * 강제한다. 컨트롤러·CLI는 이 표시가 있는 메서드만 부른다.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface UseCaseEntry {

    Action[] value();
}

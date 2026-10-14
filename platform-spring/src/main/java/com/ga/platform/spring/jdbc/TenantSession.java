package com.ga.platform.spring.jdbc;

/**
 * 테넌트 세션 설정의 이름. {@link TenantSessionBinder}가 트랜잭션마다 이 설정에 테넌트를 넣고, 소비 저장소의 RLS 정책이
 * {@code current_setting('app.tenant_id', true)}로 읽는다. 마이그레이션(SQL)은 이 상수를 참조할 수 없으므로 소비 저장소는
 * 자기 마이그레이션의 문자열이 이 값과 같음을 시험으로 대조한다(ga-disclosure {@code RlsSettingNameTest}).
 */
public final class TenantSession {

    /** PostgreSQL 사용자 정의 설정 이름(트랜잭션 로컬). */
    public static final String SETTING_NAME = "app.tenant_id";

    private TenantSession() {
    }
}

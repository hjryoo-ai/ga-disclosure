/**
 * 보안 체인(6A 계획 §8): {@code /api/**}·{@code /internal/**}는 JWT 하나의 체인 → 테넌트 바인딩 필터. 원 URI·쿼리·헤더를 다루는 코드는 이 패키지뿐이다
 * (ApiLayerRulesTest).
 */
package com.ga.disclosure.api.security;

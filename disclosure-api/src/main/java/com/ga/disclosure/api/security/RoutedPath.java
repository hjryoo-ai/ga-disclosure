package com.ga.disclosure.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * 보안 결정(채널·mTLS 대조 경로)에 쓰는 요청 경로: 원 URI가 아니라 MVC·보안 체인이 라우팅하는 것과 같은 <b>디코딩된</b> 애플리케이션 안 경로(퍼센트 디코딩·
 * 세미콜론 내용 제거)에 연속 슬래시를 하나로·끝 슬래시를 뗀 것. 원 URI 문자열로 판단하면 {@code /%69nternal/…}·{@code /internal/v1/%67ate}처럼 같은 핸들러에
 * 닿는 다른 표기가 판단을 건너뛴다(6B 8단계 보안 검토 반영).
 */
final class RoutedPath {

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    static {
        PATHS.setUrlDecode(true);
        PATHS.setRemoveSemicolonContent(true);
    }

    private RoutedPath() {
    }

    static String of(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request).replaceAll("/{2,}", "/");
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}

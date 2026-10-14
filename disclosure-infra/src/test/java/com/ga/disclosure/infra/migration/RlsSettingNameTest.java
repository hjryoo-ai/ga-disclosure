package com.ga.disclosure.infra.migration;

import com.ga.platform.spring.jdbc.TenantSession;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 플랫폼 0.2.0(H6): RLS 정책이 읽는 세션 설정 이름은 {@code platform-spring}의 {@link TenantSession#SETTING_NAME}이다 — 바인더가 그 이름에
 * 테넌트를 넣는다. SQL은 상수를 참조할 수 없으므로 마이그레이션의 문자열을 상수와 대조한다. 마이그레이션이 읽거나 쓰는 설정 이름은 그 상수와
 * 아래 닫힌 표식 목록뿐이다(다른 이름은 철자가 틀린 테넌트 설정일 수 있다 — 그러면 정책이 늘 NULL을 읽어 0행이 된다).
 */
class RlsSettingNameTest {

    /** 테넌트 설정이 아닌 표식(파기·재래핑 정의자 함수의 호출 표식). 폐기된 항목이 남으면 실패한다. */
    private static final Set<String> MARKERS = Set.of("ga.destroy", "ga.rewrap");

    private static final Pattern SETTING = Pattern.compile("(current_setting|set_config)\\(\\s*'{1,2}([^']+)'{1,2}");

    /** 앱이 적용하는 그 목록(클래스패스 — 시험 실행에서는 jar 안에 있다; {@link SchemaMigrator#bundledVersion()}과 같은 조회). */
    private static Map<String, String> migrations() {
        try {
            Map<String, String> files = new TreeMap<>();
            for (Resource r : new PathMatchingResourcePatternResolver(RlsSettingNameTest.class.getClassLoader())
                    .getResources("classpath*:db/migration/*.sql")) {
                try (InputStream in = r.getInputStream()) {
                    files.put(r.getFilename(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            assertThat(files).as("migrations on the classpath").containsKey("V1__init.sql");
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> settingNames(String sql) {
        Set<String> names = new TreeSet<>();
        Matcher m = SETTING.matcher(sql);
        while (m.find()) {
            names.add(m.group(2));
        }
        return names;
    }

    @Test
    void rlsPoliciesReadThePlatformSettingName() {
        String rls = migrations().get("V2__rls.sql");
        assertThat(rls).as("V2__rls.sql on the classpath").isNotNull();
        assertThat(settingNames(rls)).containsExactly(TenantSession.SETTING_NAME);
        assertThat(rls).contains("current_setting(''" + TenantSession.SETTING_NAME + "'', true)");
    }

    @Test
    void everySessionSettingInTheMigrationsIsTheTenantSettingOrAKnownMarker() {
        Map<String, Set<String>> unknown = new TreeMap<>();
        Set<String> markersSeen = new TreeSet<>();
        migrations().forEach((file, sql) -> settingNames(sql).forEach(name -> {
            if (MARKERS.contains(name)) {
                markersSeen.add(name);
            } else if (!name.equals(TenantSession.SETTING_NAME)) {
                unknown.computeIfAbsent(file, f -> new TreeSet<>()).add(name);
            }
        }));
        assertThat(unknown).as("setting names other than %s and the markers", TenantSession.SETTING_NAME).isEmpty();
        assertThat(markersSeen).as("stale marker entries").isEqualTo(MARKERS);
    }
}

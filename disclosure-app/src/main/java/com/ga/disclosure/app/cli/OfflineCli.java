package com.ga.disclosure.app.cli;

import com.ga.disclosure.infra.migration.SchemaMigrator;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.io.PrintStream;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;

/**
 * 앱 컨텍스트 없이 도는 운영자 명령(Phase 8). 앱 컨텍스트는 기동 때 스키마 버전 가드를 지나야 하는데({@code SchemaVersionGuard}), 이 명령들은 그
 * 앞에 와야 한다 — 마이그레이션 자체, 그리고 DB가 필요 없는 로컬 비밀 준비.
 * <pre>
 * db migrate                                          (마이그레이터 롤 ga.migrator.* — 운영은 마이그레이션 Job에만 그 자격 증명)
 * secrets init --secrets-dir &lt;dir&gt; [--demo yes]       ({@link SecretCommands})
 * crypto kek init --tenant T --kek-id T-KEK-1 --secrets-dir &lt;dir&gt; [--if-absent yes]   ({@link KekCommands#init})
 * backup seal|open|upload|download, backup objects export|import                      ({@link BackupCommands})
 * </pre>
 * 설정 해석(환경변수·{@code application.yaml}·{@code --name=value})만을 위해 자동 구성 없는 빈 컨텍스트를 잠깐 띄운다 — 이 클래스는 컴포넌트가 아니라
 * 앱의 컴포넌트 스캔에 걸리지 않는다. 실패는 {@link CliFailure}로 던지고 진입점이 종료 코드 1로 바꾼다.
 */
public final class OfflineCli {

    static final Set<String> COMMANDS = Set.of("db migrate", "secrets init", "crypto kek init",
            "backup seal", "backup open", "backup upload", "backup download", "backup objects export", "backup objects import");

    private OfflineCli() {
    }

    public static boolean handles(String[] args) {
        try {
            return COMMANDS.contains(CliArguments.parse(args).command());
        } catch (CliFailure e) {
            return false;
        }
    }

    public static void run(String[] args, PrintStream out) {
        Objects.requireNonNull(out, "out");
        CliArguments parsed = CliArguments.parse(args);
        switch (parsed.command()) {
            case "db migrate" -> migrate(args, out);
            case "secrets init" -> new SecretCommands(out, Clock.systemUTC()).run(parsed);
            case "crypto kek init" -> KekCommands.init(parsed, out);
            case "backup seal", "backup open", "backup upload", "backup download", "backup objects export", "backup objects import" -> {
                try (ConfigurableApplicationContext settings = settings(args)) {
                    new BackupCommands(settings.getEnvironment(), out, Clock.systemUTC()).run(parsed);
                }
            }
            default -> throw new CliFailure("not an offline command: '" + parsed.command() + "'");
        }
    }

    private static void migrate(String[] args, PrintStream out) {
        String url;
        String username;
        String password;
        try (ConfigurableApplicationContext settings = settings(args)) {
            Environment env = settings.getEnvironment();
            url = env.getRequiredProperty("ga.migrator.url");
            username = env.getRequiredProperty("ga.migrator.username");
            password = env.getRequiredProperty("ga.migrator.password");
        }
        SchemaMigrator.Applied applied = SchemaMigrator.migrate(url, username, password);
        out.println("DB_MIGRATE applied=" + applied.migrationsExecuted() + " version="
                + (applied.targetVersion() == null ? SchemaMigrator.bundledVersion() : applied.targetVersion()));
    }

    private static ConfigurableApplicationContext settings(String[] args) {
        return new SpringApplicationBuilder(Settings.class).web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false).run(args);
    }

    /** 설정 해석용 빈 컨텍스트의 원천(빈 없음). */
    static final class Settings {
    }
}

package com.ga.disclosure.infra;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ga.disclosure.domain.pii.BirthDate;
import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.infra.json.SensitiveGuardModule;
import com.ga.disclosure.infra.testing.PiiSentinels;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.workflow.customer.Customer;
import com.ga.disclosure.workflow.customer.NewCustomer;
import com.ga.disclosure.workflow.customer.NotificationPurpose;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 P4·P6: 센티널 고객({@link PiiSentinels})을 모든 경로로 다룬 뒤 다음 출력 어디에도 평문(원문·변형·UTF-8 16진)이 0건.
 * <ol>
 *   <li>logback 전 로거 TRACE(스프링 JDBC 바인드 값 로그 포함) — 메모리 어펜더</li>
 *   <li>표준 출력·표준 오류</li>
 *   <li>고의로 일으킨 실패의 예외 메시지와 스택 트레이스(형식 오류, AAD 불일치, 없는 고객, 직렬화 시도)</li>
 *   <li>반환 객체 전부의 {@code toString()}</li>
 *   <li>감사 로그 전 행</li>
 *   <li>컨테이너 안 {@code pg_dump --data-only}(전 테넌트)</li>
 *   <li>PostgreSQL 서버 로그</li>
 * </ol>
 * 추가로 스캐너 자체가 센티널을 찾아낸다는 대조 검사를 둔다(검사기가 항상 0을 내는 거짓 음성 방지).
 * 통합 테스트 전체의 결과 XML(표준 출력·오류·실패 메시지)은 루트 빌드의 {@code scanPlaintextLeaks}가 같은 센티널로 검사한다.
 */
class PlaintextLeakScanIT {

    private static final PostgresHarness DB = PostgresHarness.get();

    private final CatalogCustomerSetup s = new CatalogCustomerSetup("2026-09-01T00:00:00Z");
    private final List<String> outputs = new ArrayList<>();

    private void capture(Supplier<?> action) {
        try {
            Object result = action.get();
            outputs.add(String.valueOf(result));
        } catch (RuntimeException e) {
            StringWriter trace = new StringWriter();
            e.printStackTrace(new PrintWriter(trace));
            outputs.add(e.getMessage());
            outputs.add(trace.toString());
        }
    }

    @Test
    void noPlaintextInLogsExceptionsToStringAuditOrDatabase() {
        LoggerContext logging = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = logging.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.TRACE);
        logging.getLogger("org.springframework.jdbc").setLevel(Level.TRACE);
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream streams = new ByteArrayOutputStream();
        PrintStream tee = new PrintStream(streams, true, StandardCharsets.UTF_8);
        System.setOut(tee);
        System.setErr(tee);
        TenantId t = s.freshTenant("LEAK");
        TenantId other = s.freshTenant("LEAK_OTHER");
        try {
            exerciseEveryPath(t, other);
        } finally {
            System.setOut(out);
            System.setErr(err);
            root.detachAppender(appender);
            root.setLevel(previous);
            logging.getLogger("org.springframework.jdbc").setLevel(null);
        }

        List<String> logLines = appender.list.stream().map(e -> e.getFormattedMessage() + " " + (e.getThrowableProxy() == null ? ""
                : e.getThrowableProxy().getMessage())).toList();
        assertThat(logLines).as("TRACE logging actually captured JDBC activity").anyMatch(l -> l.contains("customer_ref"));
        assertThat(String.join("\n", logLines)).satisfies(PlaintextLeakScanIT::clean);
        assertThat(streams.toString(StandardCharsets.UTF_8)).satisfies(PlaintextLeakScanIT::clean);
        assertThat(outputs).hasSizeGreaterThan(15).allSatisfy(PlaintextLeakScanIT::clean);
        assertThat(s.auditOf(t).toString()).satisfies(PlaintextLeakScanIT::clean);
        String dump = DB.dumpAllData();
        assertThat(dump).contains("customer_ref").contains(t.value());
        assertThat(dump).satisfies(PlaintextLeakScanIT::clean);
        assertThat(DB.serverLogs()).satisfies(PlaintextLeakScanIT::clean);
    }

    /** 대조 검사: 스캐너는 원문·변형·16진 표현을 실제로 찾아낸다. */
    @Test
    void theScannerFindsEveryFormOfTheSentinels() {
        assertThat(PiiSentinels.findIn("x " + PiiSentinels.NAME + " y")).isNotEmpty();
        assertThat(PiiSentinels.findIn("tel: 010-4729-1836")).isNotEmpty();
        assertThat(PiiSentinels.findIn("dob 19310719")).isNotEmpty();
        String hex = java.util.HexFormat.of().formatHex(PiiSentinels.NAME.getBytes(StandardCharsets.UTF_8));
        assertThat(PiiSentinels.findIn("\\x" + hex)).as("plaintext stored as bytea shows up as hex in a dump").isNotEmpty();
        assertThat(PiiSentinels.forbidden()).hasSizeGreaterThanOrEqualTo(14);
    }

    private void exerciseEveryPath(TenantId t, TenantId other) {
        NewCustomer sentinel = new NewCustomer(CustomerName.of(PiiSentinels.NAME), PhoneNumber.of(PiiSentinels.PHONE),
                BirthDate.parse(PiiSentinels.BIRTH_DATE));
        outputs.add(sentinel.toString());
        outputs.add(sentinel.name().toString() + sentinel.phone() + sentinel.birthDate());
        CustomerRef ref = s.customers.register(t, CatalogCustomerSetup.OPERATOR, sentinel);
        CustomerRef second = s.customers.register(t, CatalogCustomerSetup.OPERATOR,
                new NewCustomer(CustomerName.of(PiiSentinels.NAME), null, null));
        capture(() -> s.customers.lookup(t, CatalogCustomerSetup.OPERATOR, ref));
        Customer customer = s.customers.lookup(t, CatalogCustomerSetup.OPERATOR, ref);
        outputs.add(customer.toString() + customer.name() + customer.phone() + customer.birthDate());
        capture(() -> s.customers.phoneForNotification(t, CatalogCustomerSetup.OPERATOR, ref, NotificationPurpose.REMOTE_LINK, "SESSION-X"));
        // P6: 본인확인 대조 — 맞는 입력·틀린 입력·형식 오류 입력 모두 결과만
        outputs.add(String.valueOf(BirthDate.matches(customer.birthDate().orElseThrow(), PiiSentinels.BIRTH_DATE)));
        outputs.add(String.valueOf(BirthDate.matches(customer.birthDate().orElseThrow(), "1931-07-20")));
        outputs.add(String.valueOf(BirthDate.matches(customer.birthDate().orElseThrow(), PiiSentinels.NAME)));
        // 형식 오류: 메시지에 입력값이 없어야 한다
        capture(() -> CustomerName.of(PiiSentinels.NAME + "\u0007"));
        capture(() -> PhoneNumber.of(PiiSentinels.PHONE + "9"));
        capture(() -> PhoneNumber.of("x" + PiiSentinels.PHONE));
        capture(() -> BirthDate.parse(PiiSentinels.BIRTH_DATE + "0"));
        // 없는 고객·다른 테넌트
        capture(() -> s.customers.lookup(other, CatalogCustomerSetup.OPERATOR, ref));
        capture(() -> s.customers.phoneForNotification(t, CatalogCustomerSetup.OPERATOR, second, NotificationPurpose.REMOTE_LINK, "S"));
        // 직렬화 시도: 앱 매퍼(가드 모듈)와 기본 매퍼 둘 다 실패하고 메시지에 값이 없다
        JsonMapper guarded = JsonMapper.builder().addModule(new SensitiveGuardModule()).build();
        JsonMapper plain = JsonMapper.builder().build();
        capture(() -> guarded.writeValueAsString(customer.name()));
        capture(() -> guarded.writeValueAsString(customer));
        capture(() -> plain.writeValueAsString(customer.phone().orElseThrow()));
        // 키 순환(전 경로) 후 다시 조회
        capture(() -> s.rekey.rekey(t, CatalogCustomerSetup.OPERATOR, 1));
        capture(() -> s.customers.lookup(t, CatalogCustomerSetup.OPERATOR, ref));
        // AAD 불일치: 두 행의 이름 암호문을 바꿔치기한 뒤 조회
        swapNameCiphertexts(t, ref, second);
        capture(() -> s.customers.lookup(t, CatalogCustomerSetup.OPERATOR, ref));
        capture(() -> s.customers.lookup(t, CatalogCustomerSetup.OPERATOR, second));
    }

    private static void swapNameCiphertexts(TenantId t, CustomerRef a, CustomerRef b) {
        try (Connection c = DB.superuserDataSource().getConnection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE customer_ref x SET name_enc = y.name_enc
                  FROM customer_ref y
                 WHERE x.tenant_id = ? AND y.tenant_id = x.tenant_id
                   AND ((x.customer_ref = ? AND y.customer_ref = ?) OR (x.customer_ref = ? AND y.customer_ref = ?))
                """)) {
            ps.setString(1, t.value());
            ps.setString(2, a.value());
            ps.setString(3, b.value());
            ps.setString(4, b.value());
            ps.setString(5, a.value());
            assertThat(ps.executeUpdate()).isEqualTo(2);
        } catch (SQLException e) {
            throw new PostgresHarness.UncheckedSqlException(e);
        }
    }

    private static void clean(String text) {
        assertThat(PiiSentinels.findIn(text == null ? "" : text)).as("plaintext sentinels found in output").isEmpty();
    }
}

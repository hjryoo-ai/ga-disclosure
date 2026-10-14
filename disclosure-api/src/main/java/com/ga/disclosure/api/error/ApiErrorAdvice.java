package com.ga.disclosure.api.error;

import com.ga.disclosure.domain.disclosure.IllegalTransition;
import com.ga.disclosure.workflow.ConcurrentWriteConflict;
import com.ga.disclosure.workflow.RejectionCategory;
import com.ga.disclosure.workflow.authz.AuthorizationDenied;
import com.ga.disclosure.workflow.contract.InvalidContractLinkBatchException;
import com.ga.disclosure.workflow.disclosure.CommandRejectedException;
import com.ga.disclosure.workflow.disclosure.DisclosureNotFoundException;
import com.ga.disclosure.workflow.flag.FlagRejectedException;
import com.ga.disclosure.workflow.job.JobAlreadyRunningException;
import com.ga.disclosure.workflow.job.JobQueryService;
import com.ga.disclosure.workflow.page.InvalidCursorException;
import com.ga.disclosure.workflow.retention.LegalHoldRejectedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 내부 경로({@code /api}·{@code /internal})의 예외 → {@link Problem}(6A 계획 §4.2). 상태는 HTTP 판단이 아니라 기계 변환이다:
 * <ul>
 *   <li>권한 없음({@link AuthorizationDenied})과 없는 자원·라우트는 같은 404 바이트.</li>
 *   <li>업무 거부({@link CommandRejectedException}·보류 거부·Outcome 거부)는 workflow가 붙인 범주로 — CONFLICT 409, INVALID 422, 본문 {@code REJECTED}.
 *       상태 충돌({@link IllegalTransition}) 409, 동시 쓰기 409, 작업 겹침 409.</li>
 *   <li>형식 오류 400 {@code MALFORMED_REQUEST}({@code details.field}만 — 값 없음).</li>
 *   <li>그 밖은 500 {@code INTERNAL_ERROR} — 메시지·스택을 응답에 싣지 않는다.</li>
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiErrorAdvice {

    private static final System.Logger LOG = System.getLogger(ApiErrorAdvice.class.getName());

    @ExceptionHandler({AuthorizationDenied.class, DisclosureNotFoundException.class, NoResourceFoundException.class, NotFoundException.class})
    ResponseEntity<byte[]> notFound(Exception e) {
        return Problem.of(HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<byte[]> method(HttpRequestMethodNotSupportedException e) {
        return Problem.of(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
    }

    /** 게이트 주체의 분당 한도(6B 계획 §6) — 판정 전이라 감사 없음. */
    @ExceptionHandler(com.ga.disclosure.workflow.gate.GateRateLimitedException.class)
    ResponseEntity<byte[]> rateLimited(com.ga.disclosure.workflow.gate.GateRateLimitedException e) {
        return Problem.of(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
    }

    /** 고객 등록 한도(6B 계획 §9.6, 10단계 회신 ③) — 저장·감사 없음, 429는 완료로 저장되지 않아 멱등 키가 해제된다. */
    @ExceptionHandler(com.ga.disclosure.workflow.customer.RegistrationRateLimitedException.class)
    ResponseEntity<byte[]> registrationLimited(com.ga.disclosure.workflow.customer.RegistrationRateLimitedException e) {
        return Problem.of(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
    }

    @ExceptionHandler(CommandRejectedException.class)
    ResponseEntity<byte[]> rejected(CommandRejectedException e) {
        return Problem.rejections(status(e.category()), List.of(new Problem.Rejection(e.code(), null)));
    }

    @ExceptionHandler(LegalHoldRejectedException.class)
    ResponseEntity<byte[]> holdRejected(LegalHoldRejectedException e) {
        return Problem.rejections(status(e.category()), List.of(new Problem.Rejection(e.code(), null)));
    }

    @ExceptionHandler(FlagRejectedException.class)
    ResponseEntity<byte[]> flagRejected(FlagRejectedException e) {
        return Problem.rejections(status(e.rejection().category()), List.of(new Problem.Rejection(e.rejection().name(), null)));
    }

    @ExceptionHandler(RejectedOutcomeException.class)
    ResponseEntity<byte[]> outcomeRejected(RejectedOutcomeException e) {
        return Problem.rejections(status(e.category()), e.rejections());
    }

    /** 범주 → 상태(기계 변환). */
    static HttpStatus status(RejectionCategory category) {
        return switch (category) {
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
    }

    @ExceptionHandler(IllegalTransition.class)
    ResponseEntity<byte[]> conflict(IllegalTransition e) {
        return Problem.of(HttpStatus.CONFLICT, "CONFLICT");
    }

    @ExceptionHandler(ConcurrentWriteConflict.class)
    ResponseEntity<byte[]> concurrent(ConcurrentWriteConflict e) {
        return Problem.of(HttpStatus.CONFLICT, "CONCURRENT_WRITE");
    }

    /** 룰 없는 테넌트의 룰 읽기(Phase 8 룰 어휘) — 쓰기의 503({@code TenantRulesInterceptor})과 같은 응답. */
    @ExceptionHandler(com.ga.disclosure.workflow.onboarding.RulesNotInForceException.class)
    ResponseEntity<byte[]> rulesNotInForce(com.ga.disclosure.workflow.onboarding.RulesNotInForceException e) {
        return Problem.of(HttpStatus.SERVICE_UNAVAILABLE, "TENANT_RULES_NOT_ACTIVE");
    }

    @ExceptionHandler(JobAlreadyRunningException.class)
    ResponseEntity<byte[]> jobRunning(JobAlreadyRunningException e) {
        return Problem.of(HttpStatus.CONFLICT, "JOB_ALREADY_RUNNING");
    }

    @ExceptionHandler(JobQueryService.ReportNotAvailableException.class)
    ResponseEntity<byte[]> noReport(JobQueryService.ReportNotAvailableException e) {
        return Problem.of(HttpStatus.CONFLICT, "REPORT_NOT_AVAILABLE");
    }

    @ExceptionHandler(InvalidCursorException.class)
    ResponseEntity<byte[]> invalidCursor(InvalidCursorException e) {
        return Problem.of(HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
    }

    /** 계약 연결 배치의 스키마 위반(위치·규칙 이름은 응답에 싣지 않는다 — 값이 번호일 수 있다). */
    @ExceptionHandler(InvalidContractLinkBatchException.class)
    ResponseEntity<byte[]> invalidBatch(InvalidContractLinkBatchException e) {
        return Problem.field(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", null);
    }

    @ExceptionHandler(MalformedRequestException.class)
    ResponseEntity<byte[]> malformed(MalformedRequestException e) {
        return Problem.field(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", e.field());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<byte[]> typeMismatch(MethodArgumentTypeMismatchException e) {
        return Problem.field(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", e.getName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<byte[]> missingParameter(MissingServletRequestParameterException e) {
        return Problem.field(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", e.getParameterName());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<byte[]> missingHeader(MissingRequestHeaderException e) {
        return Problem.field(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", e.getHeaderName());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class, IllegalArgumentException.class})
    ResponseEntity<byte[]> unreadable(Exception e) {
        return Problem.field(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", unknownField(e));
    }

    /** 모르는 필드(6B §9 승인 R1 — 앱 전체 {@code FAIL_ON_UNKNOWN_PROPERTIES})면 그 필드 이름. 값·메시지는 싣지 않는다. */
    private static String unknownField(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof tools.jackson.databind.exc.UnrecognizedPropertyException u) {
                return u.getPropertyName();
            }
        }
        return null;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<byte[]> internal(Exception e) {
        LOG.log(System.Logger.Level.ERROR, "API_INTERNAL_ERROR " + e.getClass().getSimpleName());
        return Problem.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
    }
}

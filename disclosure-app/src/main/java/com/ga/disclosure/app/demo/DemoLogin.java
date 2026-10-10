package com.ga.disclosure.app.demo;

import com.ga.platform.core.tenant.TenantId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 데모 로그인(Phase 7 Q6 권장 — 데모 프로파일만, 계약 {@code contracts/api/v1/demo-oidc.openapi.yaml}): Authorization Code + PKCE(S256).
 * <ol>
 *   <li>{@code GET /demo/oidc/authorize} — JS 없는 주체 선택 폼(설정 {@code ga.demo.login.accounts}의 닫힌 목록). 직원 화면이 새 창으로 연다 — 코드 검증자는
 *       직원 화면의 메모리에만 있으므로(브라우저 저장소 금지) 직원 화면 창은 떠나지 않는다.</li>
 *   <li>{@code POST /demo/oidc/authorize} — 선택을 받아 일회용 코드(60초)를 만들고 {@code 303 → /oidc-callback?code&state}. 콜백 쪽이 코드를
 *       {@code postMessage}로 직원 화면 창에 넘기고 닫힌다.</li>
 *   <li>{@code POST /demo/oidc/token} — 코드 + 검증자 → 데모 JWT({@link DemoOidcIssuer}, 1시간). 코드는 한 번만, 검증자의 SHA-256이 챌린지와 같아야 한다.</li>
 * </ol>
 * 클라이언트 ID·되돌아갈 주소는 하나씩(닫힌 값). 주체를 고르면 그 주체로 토큰이 나온다 — 데모 IdP의 본성이고({@code demo token} CLI와 같다) 운영 IdP 연동은
 * Phase 8. 쿠키·세션 없음. 문구는 영어(데모 IdP 대역 — 서버 코드에 한글 문구를 두지 않는다). API 컨트롤러가 아니다 — 라우트는
 * {@link DemoWebConfiguration}의 함수형 라우터(API 계층 규칙은 {@code /api}·{@code /internal}·{@code /public} 컨트롤러의 것이다).
 */
@Component
@Profile("demo")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(DemoLogin.LoginProperties.class)
public class DemoLogin {

    public static final String CLIENT_ID = "ga-disclosure-web";
    public static final String REDIRECT_URI = "/oidc-callback";
    static final Duration CODE_TTL = Duration.ofSeconds(60);
    static final Duration TOKEN_TTL = Duration.ofHours(1);
    private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern STATE = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");
    private static final Pattern VERIFIER = Pattern.compile("^[A-Za-z0-9._~-]{43,128}$");
    private static final Pattern ACCOUNT = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,31}/[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final MediaType HTML = MediaType.parseMediaType("text/html;charset=UTF-8");

    /** 데모 로그인 계정(테넌트/주체) — 닫힌 목록. 역할은 여기 없다(역할은 {@code identity_link}). */
    @ConfigurationProperties("ga.demo.login")
    public record LoginProperties(List<String> accounts) {
        public LoginProperties {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            for (String a : accounts) {
                if (!ACCOUNT.matcher(a).matches()) {
                    throw new IllegalArgumentException("ga.demo.login.accounts entries are TENANT/subject: " + a);
                }
            }
        }
    }

    record Grant(String account, String challenge, Instant expiresAt) {
    }

    private final LoginProperties properties;
    private final DemoOidcIssuer issuer;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> codes = new ConcurrentHashMap<>();

    public DemoLogin(LoginProperties properties, DemoOidcIssuer issuer, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    ServerResponse form(ServerRequest request) {
        Map<String, String> query = request.params().toSingleValueMap();
        if (!valid(query)) {
            return page(HttpStatus.BAD_REQUEST, "<h1>Demo sign-in</h1><p>Invalid authorization request.</p>");
        }
        StringBuilder body = new StringBuilder(2048);
        body.append("<h1>Demo sign-in</h1><form method=\"post\" action=\"/demo/oidc/authorize\">");
        for (String name : List.of("response_type", "client_id", "redirect_uri", "code_challenge", "code_challenge_method", "state")) {
            body.append("<input type=\"hidden\" name=\"").append(name).append("\" value=\"").append(query.get(name)).append("\">");
        }
        body.append("<fieldset><legend>Choose a demo account</legend>");
        List<String> accounts = properties.accounts();
        for (int i = 0; i < accounts.size(); i++) {
            String a = accounts.get(i);
            body.append("<div><input type=\"radio\" name=\"account\" required id=\"account-").append(i).append("\" value=\"").append(a).append("\"")
                    .append(i == 0 ? " checked" : "").append("><label for=\"account-").append(i).append("\">").append(a.replace("/", " · "))
                    .append("</label></div>");
        }
        body.append("</fieldset><button type=\"submit\">Sign in</button></form>");
        return page(HttpStatus.OK, body.toString());
    }

    ServerResponse authorize(ServerRequest request) {
        Map<String, String> form = request.params().toSingleValueMap();
        String account = form.get("account");
        if (!valid(form) || account == null || !properties.accounts().contains(account)) {
            return page(HttpStatus.BAD_REQUEST, "<h1>Demo sign-in</h1><p>Invalid authorization request.</p>");
        }
        sweep();
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String code = URL.encodeToString(raw);
        codes.put(code, new Grant(account, form.get("code_challenge"), clock.instant().plus(CODE_TTL)));
        return ServerResponse.status(HttpStatus.SEE_OTHER).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.LOCATION, REDIRECT_URI + "?code=" + code + "&state=" + form.get("state")).build();
    }

    ServerResponse token(ServerRequest request) {
        Map<String, String> form = request.params().toSingleValueMap();
        String code = form.getOrDefault("code", "");
        Grant grant = codes.remove(code);                                   // 한 번만 — 실패해도 다시 쓸 수 없다
        String verifier = form.getOrDefault("code_verifier", "");
        boolean ok = grant != null && "authorization_code".equals(form.get("grant_type")) && CLIENT_ID.equals(form.get("client_id"))
                && REDIRECT_URI.equals(form.get("redirect_uri")) && VERIFIER.matcher(verifier).matches()
                && clock.instant().isBefore(grant.expiresAt()) && MessageDigest.isEqual(s256(verifier), grant.challenge().getBytes(StandardCharsets.US_ASCII));
        if (!ok) {
            return ServerResponse.badRequest().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                    .body(JSON.createObjectNode().put("error", "invalid_grant").toString());
        }
        int slash = grant.account().indexOf('/');
        String jwt = issuer.token(TenantId.of(grant.account().substring(0, slash)), grant.account().substring(slash + 1), TOKEN_TTL);
        return ServerResponse.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .body(JSON.createObjectNode().put("access_token", jwt).put("token_type", "Bearer").put("expires_in", TOKEN_TTL.toSeconds()).toString());
    }

    private static boolean valid(Map<String, String> p) {
        return "code".equals(p.get("response_type")) && CLIENT_ID.equals(p.get("client_id")) && REDIRECT_URI.equals(p.get("redirect_uri"))
                && "S256".equals(p.get("code_challenge_method")) && CHALLENGE.matcher(p.getOrDefault("code_challenge", "")).matches()
                && STATE.matcher(p.getOrDefault("state", "")).matches();
    }

    private void sweep() {
        Instant now = clock.instant();
        codes.values().removeIf(g -> !now.isBefore(g.expiresAt()));
    }

    private static byte[] s256(String verifier) {
        try {
            return URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)))
                    .getBytes(StandardCharsets.US_ASCII);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ServerResponse page(HttpStatus status, String main) {
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Demo sign-in</title></head><body><main>" + main + "</main></body></html>";
        return ServerResponse.status(status).contentType(HTML).cacheControl(CacheControl.noStore()).body(html.getBytes(StandardCharsets.UTF_8));
    }
}

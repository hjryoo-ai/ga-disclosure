package com.ga.disclosure.app.config;

import com.ga.disclosure.app.notify.ConsoleSignLinkNotifier;
import com.ga.disclosure.infra.crypto.SecureRandomTokenSource;
import com.ga.disclosure.seal.renderer.SignedPdfAppender;
import com.ga.disclosure.sign.token.TokenSource;
import com.ga.disclosure.workflow.anchor.AnchorStore;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.customer.CustomerRefService;
import com.ga.disclosure.workflow.disclosure.DisclosureServiceDeps;
import com.ga.disclosure.workflow.disclosure.ExpireService;
import com.ga.disclosure.workflow.disclosure.NotificationDispatcher;
import com.ga.disclosure.workflow.disclosure.SealService;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.disclosure.SignSessionService;
import com.ga.disclosure.workflow.sign.NotificationStore;
import com.ga.disclosure.workflow.sign.NotifyPort;
import com.ga.disclosure.workflow.sign.SignSessionStore;
import com.ga.disclosure.workflow.sign.SignatureStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 4 조립: 고객 서명 세션·서명 수집·완료(설계서 §6.5). 서명 트랜잭션은 봉인과 같은 제한 시간을 쓴다(증거 객체를 올리므로 잔여물 정리 유예의 전제가
 * 같다). 통지는 콘솔 구현이고 링크 기준 URL은 배포 설정이다 — 토큰은 프래그먼트({@code …/s#}, 6A 계획 §5.5). 원격 링크는 발급 트랜잭션이 아웃박스에 적재하고
 * 통지 디스패처(작업 NOTIFY)가 보낸다(6A 계획 §7).
 */
@Configuration
public class SignConfiguration {

    @Bean
    public TokenSource tokenSource() {
        return new SecureRandomTokenSource();
    }

    @Bean
    public NotifyPort notifyPort() {
        return new ConsoleSignLinkNotifier(System.out);
    }

    @Bean
    public NotificationDispatcher notificationDispatcher(DisclosureServiceDeps deps, NotificationStore outbox, SignSessionStore sessions,
                                                         CustomerRefService phones, TokenSource tokens, NotifyPort notify,
                                                         @Value("${ga.sign.link-base-url:https://sign.example.invalid/s#}") String linkBase) {
        return new NotificationDispatcher(deps, outbox, sessions, phones, tokens, notify, linkBase);
    }

    @Bean
    public SignedPdfAppender signedPdfAppender() {
        return new SignedPdfAppender();
    }

    @Bean
    public SignSessionService signSessionService(DisclosureServiceDeps deps, SignSessionStore sessions, DocumentRecordStore records,
                                                 DocumentCryptoPort crypto, ArtifactStore storage, TokenSource tokens, NotificationStore outbox) {
        return new SignSessionService(deps, sessions, records, crypto, storage, tokens, outbox);
    }

    @Bean
    public ExpireService expireService(DisclosureServiceDeps deps, SignSessionStore sessions) {
        return new ExpireService(deps, sessions);
    }

    @Bean
    public SignService signService(DisclosureServiceDeps deps, SignSessionStore sessions, SignatureStore signatures, DocumentRecordStore records,
                                   DocumentCryptoPort crypto, ArtifactStore storage, SignedPdfAppender appender, SealService seal,
                                   AnchorStore anchors) {
        return new SignService(deps, sessions, signatures, records, crypto, storage, appender, seal.transactionTimeout(), anchors);
    }
}

/**
 * 이벤트 피드(6A 계획 §4.1, 설계서 §4.5, 승인 Q5): 테넌트 아웃박스({@code outbox_event})를 정수 {@code afterSeq}·{@code nextSeq}·{@code headSeq}로
 * 당겨 읽고(at-least-once), 소비자의 ack가 그 seq까지 발행 시각({@code published_at})을 한 번 기록한다. 푸시는 포트만 있다(구현 없음).
 */
package com.ga.disclosure.workflow.feed;

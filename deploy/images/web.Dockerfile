# syntax=docker/dockerfile:1
# ga-disclosure web 이미지(Phase 8 ③): disclosure-web 산출물(Vite dist)을 비루트 nginx(UID 101, 8080)로. 헤더는 데모 서빙(DemoWebConfiguration)과 같은
# 문자열이다(이미지 시험이 대조). 베이스는 다중 아키텍처 인덱스 digest로 고정(2026-10-10 실측: nginx-unprivileged 1.30.5 alpine — 안정 계열).
ARG BASE=nginxinc/nginx-unprivileged:1.30.5-alpine@sha256:15c994d10d6d78658721c3bcafff14cb281fba2a4bdf9d5ba92c416a472516e3
FROM ${BASE}
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/
EXPOSE 8080

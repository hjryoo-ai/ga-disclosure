# syntax=docker/dockerfile:1
# ga-disclosure app 이미지(Phase 8 ③): 부트 jar를 계층으로 풀어 비루트(UID 10001)로 띄운다. 같은 이미지를 웹 Deployment·마이그레이션 Job(db migrate)·
# CronJob(jobs run …)이 인자만 바꿔 쓴다 — 기동 때 대조하는 마이그레이션 목록과 Job이 적용하는 목록이 같은 jar에서 나온다.
# 베이스는 다중 아키텍처 인덱스 digest로 고정(2026-10-10 실측: Temurin 25.0.4.1+1 JRE, Alpine — amd64·arm64). 비밀·키 파일은 이미지에 없다 —
# 런타임에 비밀 볼륨(ga.secrets.dir)으로 온다(이미지 레이어 스캔이 검사). 루트 FS는 읽기 전용으로 띄운다(쓰기는 /tmp emptyDir만).
# Ubuntu noble 판(…-jre-noble)을 먼저 썼다가 바꿨다: apt가 끌어오는 libgnutls30t64에 GnuTLS 자체 시험용 PEM 개인키 10개가 박혀 있어 레이어 스캔이
# 걸렸다(공개 시험 벡터라 비밀은 아니다). 스캔 규칙을 좁히는 대신 그 라이브러리가 없는 베이스로 옮겼다 — 윗 레이어에서 지워도 아래 레이어에 남는다.
ARG BASE=eclipse-temurin:25.0.4.1_1-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61

FROM ${BASE} AS layers
WORKDIR /x
COPY app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination out

FROM ${BASE}
RUN addgroup -g 10001 -S ga && adduser -u 10001 -S -G ga -H -h /nonexistent -s /sbin/nologin ga
WORKDIR /app
COPY --from=layers /x/out/dependencies/ ./
COPY --from=layers /x/out/spring-boot-loader/ ./
COPY --from=layers /x/out/snapshot-dependencies/ ./
COPY --from=layers /x/out/application/ ./
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.io.tmpdir=/tmp"
# 8080 = /api·/public, 8081 = /internal(Q5), 8082 = 관리(헬스·메트릭)
EXPOSE 8080 8081 8082
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

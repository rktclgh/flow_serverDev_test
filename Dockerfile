# syntax=docker/dockerfile:1
#
# 빌드 컨텍스트는 저장소 루트다 (web/ 과 extension/ 을 모두 참조해야 하므로).
# React 산출물을 Spring 의 static 리소스로 번들해 단일 컨테이너로 배포한다.
#   - CORS 설정이 필요 없다
#   - nginx 는 upstream 하나만 알면 된다
#   - 프론트/백 배포가 원자적으로 함께 일어난다

# ── 1. 프론트엔드 빌드 ────────────────────────────────────────────────
FROM node:22-alpine AS web
WORKDIR /web

COPY web/package.json web/package-lock.json ./
RUN npm ci

COPY web/ ./
RUN npm run build

# ── 2. 백엔드 빌드 ────────────────────────────────────────────────────
# gradle 배포판 이미지 대신 wrapper 를 쓴다.
# wrapper 가 gradle-wrapper.properties 의 버전을 고정하므로 로컬·CI·서버가 같은 빌드를 낸다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# 의존성 레이어를 소스와 분리해 캐시 적중률을 높인다
COPY extension/gradlew ./
COPY extension/gradle gradle
COPY extension/build.gradle.kts extension/settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY extension/src src
COPY --from=web /web/dist src/main/resources/static

RUN ./gradlew bootJar --no-daemon -x test

# ── 3. 런타임 ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# 업로드 파일을 다루는 서비스이므로 root 로 실행하지 않는다
RUN addgroup -S app && adduser -S app -G app

WORKDIR /app

# 저장 키의 날짜 프리픽스가 이 시간대를 따른다(SPEC §10.4 아래, StorageConfig#clock).
# 이 줄이 없으면 컨테이너는 UTC 로 뜬다 — 호스트가 서울이어도 그렇다. 실측으로 확인했고,
# 그대로 두면 한국 시간 오전 0~9시 업로드가 전날 프리픽스로 조용히 들어간다.
# alpine 에 tzdata 가 이미 있어 별도 설치가 필요 없다.
ENV TZ=Asia/Seoul

COPY --from=build /src/build/libs/*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

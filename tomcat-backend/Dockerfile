# syntax=docker/dockerfile:1
# WAS 티어: Tomcat 9 + OpenJDK 21 + petclinic.war
# (pom.xml 도 java.version=21, Spring 5.3.39 등 Java 21 지원 버전으로 맞춤)

# =========================================================
# 1단계: WAR 빌드 (app/ = zip의 was/app 폴더)
# =========================================================
FROM alpine:3.24 AS build

RUN apk add --no-cache openjdk21-jdk maven

WORKDIR /src
# 의존성 먼저 받아서 캐시 (pom.xml이 안 바뀌면 재다운로드 안 함)
# --mount=type=cache : 메이븐 저장소(~/.m2)를 빌드 사이에 재사용 → 코드만 고쳤을 때 다운로드 생략
COPY app/pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -PMySQL dependency:go-offline || true

COPY app/ .
# DB 접속 정보는 WAR에 넣지 않음.
# pom.xml의 리소스 필터링이 ${jdbc.url} 을 빌드 시점 값으로 박아버리므로,
# 대신 SpEL 표현식을 넣어 "실행 시 시스템 프로퍼티(-Djdbc.*)"를 읽게 함.
# 실제 값은 setenv.sh 가 환경변수(DB_HOST 등)로 만들어 넘김.
RUN --mount=type=cache,target=/root/.m2 mvn -B -o clean package -PMySQL -DskipTests \
      "-Djdbc.url=#{systemProperties['jdbc.url']}" \
      "-Djdbc.username=#{systemProperties['jdbc.username']}" \
      "-Djdbc.password=#{systemProperties['jdbc.password']}"

# =========================================================
# 2단계: Tomcat 9 실행 이미지
# =========================================================
FROM alpine:3.24

# Spring 5.3(javax.*) → Tomcat 9 필수 (Tomcat 10+는 jakarta.* 라 안 뜸)
ARG TOMCAT_VERSION=9.0.98
# TZ: "지난 시간 예약 불가" 판단을 한국 시간 기준으로
ENV TZ=Asia/Seoul \
    CATALINA_HOME=/opt/tomcat \
    PATH=/opt/tomcat/bin:$PATH

# Java 설치 + Tomcat 다운로드/설치
#   [빌드 최적화] curl 은 다운로드에만 필요 → 가상 패키지(.fetch)로 설치했다가 같은 RUN 안에서 삭제
#   (다른 RUN 에서 지우면 앞 레이어에 그대로 남아 크기가 안 줄어듦)
#   non-root: tomcat 사용자를 uid/gid 10001 로 고정 (USER 에 숫자로 적기 위해)
RUN apk add --no-cache openjdk21-jre-headless tzdata \
 && apk add --no-cache --virtual .fetch curl \
 && curl -fsSL "https://archive.apache.org/dist/tomcat/tomcat-9/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz" \
      -o /tmp/tomcat.tar.gz \
 && mkdir -p ${CATALINA_HOME} \
 && tar -xzf /tmp/tomcat.tar.gz -C ${CATALINA_HOME} --strip-components=1 \
 && rm -rf /tmp/tomcat.tar.gz ${CATALINA_HOME}/webapps/* \
 && apk del .fetch \
 && addgroup -S -g 10001 tomcat && adduser -S -u 10001 -G tomcat -h ${CATALINA_HOME} tomcat \
 && chown -R tomcat:tomcat ${CATALINA_HOME}

# 환경변수(DB_HOST 등) → JDBC 설정으로 변환 (Tomcat이 시작 시 자동 실행)
COPY --chown=tomcat:tomcat setenv.sh ${CATALINA_HOME}/bin/setenv.sh
# Windows에서 편집해 CRLF가 들어가도 동작하도록 정리
RUN sed -i 's/\r$//' ${CATALINA_HOME}/bin/setenv.sh

# 빌드한 WAR를 webapps/petclinic.war 로 배치 → /petclinic 으로 서비스
COPY --from=build --chown=tomcat:tomcat /src/target/petclinic.war ${CATALINA_HOME}/webapps/petclinic.war

# 기본값 (EKS에서는 ConfigMap/Secret 으로 주입)
ENV DB_HOST=db \
    DB_PORT=3306 \
    DB_NAME=petclinic \
    DB_USER=petclinic
# DB_PASSWORD 는 기본값을 두지 않음 (이미지에 비밀번호를 남기지 않기 위해)
# → 실행 시 꼭 지정: docker run -e DB_PASSWORD=... / K8s 에서는 Secret

# non-root 실행: tomcat 사용자 (uid/gid 10001)
# 숫자로 적어야 쿠버네티스 runAsNonRoot 가 root 가 아님을 확인할 수 있음
USER 10001:10001
EXPOSE 8080

# Tomcat을 포그라운드(PID 1)로 실행
CMD ["catalina.sh", "run"]

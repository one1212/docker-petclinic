# syntax=docker/dockerfile:1
# [비교용] 최적화를 하나도 적용하지 않은 Tomcat 이미지 — 실제 배포에는 쓰지 않음
#   - 단일 스테이지: JDK + Maven + 소스 + 빌드 산출물이 실행 이미지에 그대로 남음
#   - 소스 전체를 먼저 COPY → 코드 한 줄만 바꿔도 의존성 다운로드부터 다시
#   - 메이븐 캐시 마운트 없음
#   - root 로 실행
# 빌드: docker build -f build-compare/tomcat-naive.Dockerfile tomcat-backend

FROM alpine:3.24

ARG TOMCAT_VERSION=9.0.98
ENV TZ=Asia/Seoul \
    CATALINA_HOME=/opt/tomcat \
    PATH=/opt/tomcat/bin:$PATH

RUN apk add --no-cache openjdk21-jdk maven curl tzdata
RUN curl -fsSL "https://archive.apache.org/dist/tomcat/tomcat-9/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz" -o /tmp/tomcat.tar.gz
RUN mkdir -p ${CATALINA_HOME} && tar -xzf /tmp/tomcat.tar.gz -C ${CATALINA_HOME} --strip-components=1
RUN rm -rf /tmp/tomcat.tar.gz ${CATALINA_HOME}/webapps/*

WORKDIR /src
COPY app/ .
RUN mvn -B -q clean package -PMySQL -DskipTests \
      "-Djdbc.url=#{systemProperties['jdbc.url']}" \
      "-Djdbc.username=#{systemProperties['jdbc.username']}" \
      "-Djdbc.password=#{systemProperties['jdbc.password']}"
RUN cp target/petclinic.war ${CATALINA_HOME}/webapps/petclinic.war

COPY setenv.sh ${CATALINA_HOME}/bin/setenv.sh
RUN sed -i 's/\r$//' ${CATALINA_HOME}/bin/setenv.sh

ENV DB_HOST=db DB_PORT=3306 DB_NAME=petclinic DB_USER=petclinic
EXPOSE 8080
CMD ["catalina.sh", "run"]

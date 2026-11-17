#!/bin/sh
# Tomcat 시작 시 catalina.sh 가 자동으로 읽는 파일.
# datasource-config.xml 이 system-properties-mode="OVERRIDE" 라서
# 여기서 넘긴 -Djdbc.* 값이 WAR 안의 값보다 우선함.

# DB 비밀번호가 비어 있으면 Tomcat 은 떠도 앱 배포가 실패해 "컨테이너는 Up 인데 예약이 안 되는" 상태가 됨
# → 시작 단계에서 바로 멈추고 이유를 남김 (docker logs 에 보이고, 컨테이너는 Exited(1))
if [ -z "${DB_PASSWORD:-}" ]; then
  echo "[setenv] ERROR: DB_PASSWORD 가 비어 있습니다. docker run -e DB_PASSWORD=... (K8s 는 Secret) 로 지정하세요." >&2
  exit 1
fi

CATALINA_OPTS="$CATALINA_OPTS \
 -Djdbc.url=jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useUnicode=true \
 -Djdbc.username=${DB_USER} \
 -Djdbc.password=${DB_PASSWORD}"
export CATALINA_OPTS

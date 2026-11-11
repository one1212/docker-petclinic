#!/bin/sh
# Tomcat 시작 시 catalina.sh 가 자동으로 읽는 파일.
# datasource-config.xml 이 system-properties-mode="OVERRIDE" 라서
# 여기서 넘긴 -Djdbc.* 값이 WAR 안의 값보다 우선함.
CATALINA_OPTS="$CATALINA_OPTS \
 -Djdbc.url=jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useUnicode=true \
 -Djdbc.username=${DB_USER} \
 -Djdbc.password=${DB_PASSWORD}"
export CATALINA_OPTS

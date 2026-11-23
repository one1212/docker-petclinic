# syntax=docker/dockerfile:1

FROM alpine:3.24

# Apache + 리버스 프록시 모듈 + htpasswd(apache2-utils, 관리자 비밀번호 파일 생성용) 설치
#   non-root 실행 준비:
#   - 1024 미만 포트(80)는 root 만 열 수 있으므로 8080 으로 Listen
#   - PID 파일(/run/apache2)과 관리자 비밀번호 파일(/etc/apache2/admin)을 apache 사용자가 쓸 수 있게
RUN apk add --no-cache apache2 apache2-proxy apache2-utils \
 && mkdir -p /run/apache2 /etc/apache2/admin \
 # 기본 설정을 포그라운드/로그 stdout 용으로 조정
 && sed -i 's/^#ServerName .*/ServerName localhost/' /etc/apache2/httpd.conf \
 && sed -i 's/^Listen 80$/Listen 8080/' /etc/apache2/httpd.conf \
 && sed -i 's#^ErrorLog .*#ErrorLog /dev/stderr#' /etc/apache2/httpd.conf \
 && sed -i 's#^\s*CustomLog .*#    CustomLog /dev/stdout combined#' /etc/apache2/httpd.conf \
 && chown -R apache:apache /run/apache2 /etc/apache2/admin

# [빌드 최적화] 잘 안 바뀌는 것(설정·시작 스크립트)을 먼저, 자주 바뀌는 화면 파일(html/css)을 맨 뒤에
#   → 화면만 고치면 위 단계는 전부 캐시 재사용, 마지막 COPY 만 다시 실행

# Tomcat 으로 넘기는 리버스 프록시 + 관리자 접근 제어 설정
# (proxy.conf 모듈 로딩 뒤에 읽히도록 zz- 접두사)
COPY zz-petclinic.conf /etc/apache2/conf.d/zz-petclinic.conf

# 시작 스크립트: ADMIN_USER / ADMIN_PASSWORD → 관리자 비밀번호 파일 생성 후 Apache 실행
# --chmod 로 권한을 COPY 와 함께 지정 (별도 RUN chmod 레이어 없음)
COPY --chmod=755 docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
# Windows 에서 편집해 CRLF 가 들어가도 동작하도록 정리
RUN sed -i 's/\r$//' /usr/local/bin/docker-entrypoint.sh

# Tomcat 컨테이너 주소 (docker run -e TOMCAT_HOST=... 로 변경 가능)
# ADMIN_PATH: 관리자 화면 주소. 비밀번호가 아니므로 기본값을 두고, 실행 시 -e ADMIN_PATH=/다른-주소 로 변경 가능
# ADMIN_USER / ADMIN_PASSWORD 는 기본값을 두지 않음 (이미지에 비밀번호를 남기지 않기 위해)
ENV TOMCAT_HOST=tomcat-backend \
    TOMCAT_PORT=8080 \
    ADMIN_PATH=/staff-room

# 관리자 화면 (예약 달력 · 전체 예약). 사용자 문서 폴더(htdocs) 밖에 두고 ADMIN_PATH 로만 연결
COPY admin/ /var/www/admin/

# 사용자 화면: 예약 / 예약 조회 / 게임 2개 / 유튜브 + 공통 메뉴 CSS
COPY index.html lookup.html games.html game.html bath-game.html youtube.html site.css /var/www/localhost/htdocs/

# non-root 실행: apache 사용자(uid 100, gid 101)
# 숫자로 적어야 쿠버네티스 runAsNonRoot 가 root 가 아님을 확인할 수 있음
USER 100:101
EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
# Apache를 포그라운드(PID 1)로 실행
CMD ["httpd", "-D", "FOREGROUND"]

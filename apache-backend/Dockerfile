# syntax=docker/dockerfile:1

FROM alpine:3.24

# Apache + 리버스 프록시 모듈 설치
RUN apk add --no-cache apache2 apache2-proxy \
 && mkdir -p /run/apache2 \
 # 기본 설정을 포그라운드/로그 stdout 용으로 조정
 && sed -i 's/^#ServerName .*/ServerName localhost/' /etc/apache2/httpd.conf \
 && sed -i 's#^ErrorLog .*#ErrorLog /dev/stderr#' /etc/apache2/httpd.conf \
 && sed -i 's#^\s*CustomLog .*#    CustomLog /dev/stdout combined#' /etc/apache2/httpd.conf

# 미용 예약 페이지 + 미용 체험 게임
COPY index.html game.html /var/www/localhost/htdocs/

# Tomcat으로 넘기는 리버스 프록시 설정
# (proxy.conf 모듈 로딩 뒤에 읽히도록 zz- 접두사)
COPY zz-petclinic.conf /etc/apache2/conf.d/zz-petclinic.conf

# Tomcat 컨테이너 주소 (docker run -e TOMCAT_HOST=... 로 변경 가능)
ENV TOMCAT_HOST=tomcat-backend \
    TOMCAT_PORT=8080

EXPOSE 80

# Apache를 포그라운드(PID 1)로 실행
CMD ["httpd", "-D", "FOREGROUND"]

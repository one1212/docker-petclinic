# syntax=docker/dockerfile:1
# DB 티어: MySQL 8.4 LTS + petclinic 스키마/초기 데이터

FROM mysql:8.4

# 최초 기동 시(데이터 디렉터리가 비어 있을 때) 이름 순서대로 자동 실행
COPY schema.sql /docker-entrypoint-initdb.d/01-schema.sql
COPY data.sql   /docker-entrypoint-initdb.d/02-data.sql
# 파일마다 별도 세션으로 실행되므로 각 파일 맨 앞에 SET NAMES 추가 (한글 초기 데이터 깨짐 방지)
RUN sed -i '1i SET NAMES utf8mb4;' /docker-entrypoint-initdb.d/*.sql

# 한글(미용 예약 이름 등) 저장용 문자셋
# --default-time-zone: WAS(Asia/Seoul)와 맞춰야 예약 시간이 9시간 밀려 저장되지 않음
CMD ["mysqld", "--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci", "--default-time-zone=+09:00"]

# MYSQL_ROOT_PASSWORD / MYSQL_DATABASE / MYSQL_USER / MYSQL_PASSWORD 는
# 실행 시 환경변수로 넘김 (EKS에서는 Secret)
EXPOSE 3306

# 두룹 살롱 펫클리닉 (3계층 · Java 21 · 도커)

## 실행 방법 (도커 컴포즈)

이 폴더(`docker-compose.yml` 이 있는 곳)에서 실행합니다.

```bash
docker compose up -d --build
```

- DB → WAS → WEB 순서로 자동으로 뜹니다. WAS는 DB 준비가 끝날 때까지 알아서 기다립니다.
- 처음에는 5~10분 걸립니다(라이브러리 다운로드). 다음부터는 빠릅니다.
- 브라우저: **http://localhost:8088**  (게임: `/game.html`, 펫클리닉: `/petclinic/`)

| 하고 싶은 것 | 명령 |
|---|---|
| 상태 확인 (모두 `healthy`/`running` 이면 정상) | `docker compose ps` |
| WAS 로그 보기 (나가기 `Ctrl+C`) | `docker compose logs -f tomcat-backend` |
| 코드 수정 후 반영 | `docker compose up -d --build` |
| 끄기 (예약 데이터 유지) | `docker compose down` |
| 다시 켜기 | `docker compose up -d` |
| DB까지 완전 초기화 (`schema.sql`·`data.sql` 고쳤을 때) | `docker compose down -v` 후 `docker compose up -d --build` |

> ⚠ 예전에 `docker run` 으로 직접 띄운 `db`, `tomcat-backend`, `web` 컨테이너가 남아 있으면
> 포트(8088)가 겹치니 먼저 지우세요: `docker rm -f db tomcat-backend web`

## 폴더 구성

| 폴더 | 계층 | 주요 파일 |
|---|---|---|
| `apache-backend/` | 웹 서버 (Apache 2.4) | `Dockerfile`, `zz-petclinic.conf`(프록시), `index.html`(예약·달력), `game.html`(미용 체험 게임) |
| `tomcat-backend/` | 애플리케이션 서버 (Tomcat 9 + OpenJDK 21) | `Dockerfile`, `setenv.sh`, `app/`(메이븐 프로젝트 → `petclinic.war`) |
| `db-backend/` | 데이터베이스 (MySQL 8.4) | `Dockerfile`, `schema.sql`, `data.sql` |
| `docker-compose.yml` | 3계층 한 번에 실행 | 서비스 `db`, `tomcat-backend`, `web` |

## 버전

- Java: **OpenJDK 21** (`pom.xml` 의 `java.version=21`)
- Spring Framework 5.3.39 / Spring Data 2021.2.18 / Hibernate 5.6.15 (+ Byte Buddy 1.14.18)
- Tomcat 9.0.98 (Spring 5.3 은 javax.* 라서 Tomcat 10 이상 불가)
- MySQL 8.4 / MySQL 드라이버 `com.mysql:mysql-connector-j` 8.4.0
- Jackson 2.17.2 / AspectJ 1.9.22.1

## 기능

- **미용 예약:** 시간당 3명 정원. 날짜를 고르면 시간대별 현황(예약 수·남은 자리·마감)을 예약 전에 확인.
  동시에 예약이 몰려도 서버가 시간대 행 잠금으로 정원 초과를 막고, 마감이면 409 응답.
- **예약 달력:** 월별 예약 건수·예약자 이름(가운데 글자 가림: 조*준)·동물 종류, 1분마다 자동 새로고침.
  날짜를 누르면 확대 창에 그날 예약 전체, 한 건을 누르면 상세(연락처 포함).
- **미용 체험 게임(`game.html`):** 강아지·고양이·사자·호랑이 / 털 깎기·목욕·염색·애교 / 산책 달리기,
  오른쪽 위 유튜브 아이콘(고른 동물의 미용 영상 검색).

## API

| 주소 | 설명 |
|---|---|
| `GET /api/health` | 상태 확인 |
| `GET /api/grooming/bookings` | 최근 예약 50건 (이름 가림) |
| `GET /api/grooming/bookings/{번호}` | 예약 상세 (이름 가림, 연락처 전체) |
| `GET /api/grooming/bookings/availability?date=2026-11-11` | 시간대별 현황 |
| `GET /api/grooming/bookings/calendar?month=2026-11` | 월별 달력 (이름 가림) |
| `POST /api/grooming/bookings` | 예약 저장 (201 접수 / 409 마감 / 400 잘못된 요청) |

## 꼭 지킬 설정

- **시간대:** WAS 는 한국 시간(`TZ=Asia/Seoul`), MySQL 은 `--default-time-zone=+09:00`. 안 맞으면 예약 시각이 9시간 밀림.
- **문자셋:** MySQL `utf8mb4`, 초기화 SQL 앞에 `SET NAMES utf8mb4;` (DB Dockerfile 이 자동으로 넣음).
- **`data.sql` 은 두 곳:** `db-backend/data.sql` 과 `tomcat-backend/app/src/main/resources/db/mysql/data.sql` 을 똑같이 맞출 것.
  여러 줄 INSERT 에서는 **마지막 줄만 `;`**, 나머지 줄 끝은 `,`.
- **연락처 노출:** 예약 상세에 연락처가 그대로 보이므로, 외부에 공개하는 주소로 띄울 때는 로그인/IP 제한을 둘 것.
- 참고: `data.sql` 에는 수의사·보호자 데이터가 없어서 펫클리닉의 수의사 목록 화면은 비어 있음.

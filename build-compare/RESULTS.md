# 빌드 최적화 · non-root 결과 (2026-11-23, WSL ubuntu-pet, Docker 29.9.3 / buildx 0.38.1)

측정 방법
- 각 방식마다 `docker builder prune -af` 로 캐시를 비운 뒤 처음부터 빌드(**처음 빌드**)
- 이어서 소스 파일 하나만 바꾸고 다시 빌드(**소스만 변경**): Tomcat 은 `.java` 파일 1개, Apache 는 `index.html`
- 처음 빌드 시간은 메이븐 · apk · Tomcat 다운로드(네트워크)가 대부분이라 실행할 때마다 ±30초 정도 흔들림

## Tomcat (WAS)

| 방식 | Dockerfile | 이미지 크기 | 처음 빌드 | 소스만 변경 후 재빌드 | 재사용(CACHED) 단계 |
| --- | --- | --- | --- | --- | --- |
| ① 최적화 없음 | `build-compare/tomcat-naive.Dockerfile` | **1,114 MB** | 301.6초 | **130.9초** | 6개 (소스 COPY 이후 전부 다시) |
| ② 멀티스테이지 + 순서 + 캐시 마운트 | 이전 `tomcat-backend/Dockerfile` | 401 MB | 327.2초 | 14.2초 | 8개 |
| ③ ② + curl 제거 + non-root uid 고정 | 현재 `tomcat-backend/Dockerfile` | **399 MB** | 364.2초 | **13.9초** | 8개 |

①→③: 이미지 크기 **-64%**, 소스만 바꿨을 때 재빌드 **-89%** (130.9초 → 13.9초)

| 방법 | 효과 |
| --- | --- |
| 멀티스테이지 | JDK · Maven · 소스 · `~/.m2` 가 build 단계에만 남고, 실행 이미지에는 JRE + Tomcat + WAR 만 → 1.1GB → 0.4GB |
| 명령 순서 (`pom.xml` 먼저 COPY → 의존성 받기 → 소스 COPY) | 소스만 바뀌면 의존성 다운로드 단계가 CACHED |
| 캐시 마운트 (`--mount=type=cache,target=/root/.m2`) | 빌드 사이에 메이븐 저장소 재사용, `-o`(오프라인)로 다시 받지 않음 |
| 베이스 이미지 alpine + `openjdk21-jre-headless` | 실행에 JDK 대신 JRE(화면 없는 버전)만 |
| 다운로드 도구 같은 RUN 안에서 삭제 (`apk add --virtual .fetch curl` … `apk del .fetch`) | curl 이 이미지에 안 남음 (설치 레이어 221MB → 218MB) |
| `.dockerignore` | `target/`, `.mvn/`, `mvnw`, IDE · `.git` · `.env` 를 빌드 컨텍스트에서 제외 |

소스만 바꿨을 때 ③에서 재사용된 단계: `apk add openjdk21-jdk maven`, `WORKDIR`, `COPY app/pom.xml`, `mvn dependency:go-offline`, 실행 단계의 `apk add jre + Tomcat 설치`, `COPY setenv.sh`, `sed` → **다시 실행된 것은 `COPY app/`, `mvn package`, `COPY --from=build ...war` 뿐**

## Apache (WEB)

| 방식 | 이미지 크기 | 처음 빌드 | html 만 변경 후 재빌드 | 다시 실행된 단계 |
| --- | --- | --- | --- | --- |
| 이전 (html COPY 가 앞쪽) | 20 MB | 12.5초 | 3.2초 | 5개 (html · admin · conf · entrypoint COPY + RUN) |
| 현재 (자주 바뀌는 html COPY 를 맨 뒤로) | 20 MB | 13.4초 | **2.0초** | **1개** (html COPY 만) |

- 잘 안 바뀌는 설정 · 시작 스크립트를 먼저, 자주 바뀌는 화면 파일을 마지막에 → 화면만 고치면 마지막 COPY 만 다시 실행
- `COPY --chmod=755` 로 권한을 COPY 와 함께 지정 (별도 `chmod` 단계 없음)

## non-root 실행

| 컨테이너 | 이전 | 현재 |
| --- | --- | --- |
| Apache | PID 1(httpd 부모)이 **root**, 자식만 apache | 전부 **apache (uid 100)**, `USER 100:101` |
| Apache 포트 | 80 | **8080** (1024 미만 포트는 root 만 열 수 있음) |
| 관리자 비밀번호 파일 | root:apache 640 | apache:apache 600 |
| Tomcat | `USER tomcat` (uid 자동 배정) | **`USER 10001:10001`** (숫자 고정) |

로컬 확인 (`docker exec ... id`, `ps -o user,pid,args`)

```
== web(after) id
uid=100(apache) gid=101(apache) groups=101(apache)
== web(after) processes
USER     PID   COMMAND
apache       1 httpd -D FOREGROUND
apache      13 httpd -D FOREGROUND
...
== web(before) processes
USER     PID   COMMAND
root         1 httpd -D FOREGROUND
apache       9 httpd -D FOREGROUND
...
== was(after) id
uid=10001(tomcat) gid=10001(tomcat) groups=10001(tomcat)
== was(after) processes
USER     PID   COMMAND
tomcat       1 /usr/bin/java ...
```

non-root 로 바꾼 뒤 기능 확인: `/` 200, 관리자 화면 인증 없이 401 / 인증 후 200

USER 를 숫자로 적은 이유: 쿠버네티스 `runAsNonRoot: true` 는 이미지 사용자가 이름(`tomcat`)이면 root 인지 판단할 수 없어 파드 시작을 거부함.

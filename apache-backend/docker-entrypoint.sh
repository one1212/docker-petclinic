#!/bin/sh
# WEB 컨테이너 시작 스크립트
#   ADMIN_USER / ADMIN_PASSWORD 환경변수로 관리자 비밀번호 파일(bcrypt)을 만든 뒤 Apache 를 실행.
#   값이 없으면 빈 파일을 만들어 관리자 경로를 모두 막음.
#   (K8s 에서는 두 값을 Secret 에서 env 로 넣으면 됨)
set -eu

HTPASSWD_DIR=/etc/apache2/admin
HTPASSWD_FILE="$HTPASSWD_DIR/htpasswd"

mkdir -p "$HTPASSWD_DIR"
: > "$HTPASSWD_FILE"

if [ -n "${ADMIN_USER:-}" ] && [ -n "${ADMIN_PASSWORD:-}" ]; then
  # 비밀번호를 명령줄 인자로 넘기지 않고 표준입력(-i)으로 넘김 → ps 로 보이지 않음
  printf '%s\n' "$ADMIN_PASSWORD" | htpasswd -i -B "$HTPASSWD_FILE" "$ADMIN_USER" 2>/dev/null
  echo "[entrypoint] admin account configured (user: $ADMIN_USER)"
else
  echo "[entrypoint] ADMIN_USER / ADMIN_PASSWORD not set -> admin pages are locked (all 401)"
fi

# Apache 자식 프로세스(apache 사용자)만 읽을 수 있게
chown root:apache "$HTPASSWD_FILE"
chmod 640 "$HTPASSWD_FILE"

# Apache 프로세스에는 평문 비밀번호를 넘기지 않음
unset ADMIN_PASSWORD

exec "$@"

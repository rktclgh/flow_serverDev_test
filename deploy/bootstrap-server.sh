#!/usr/bin/env bash
# 서버 최초 준비. 한 번만 실행한다.
#
#   bash deploy/bootstrap-server.sh
#
# 하는 일
#   1) 배포 디렉토리 생성
#   2) .env 를 서버에서 생성 — 비밀값은 서버에서 만들어 서버에만 둔다.
#      로컬을 거치지 않으므로 화면·로그·셸 히스토리 어디에도 남지 않는다.
#   3) nginx vhost 와 rate limit 설정 설치
#
# 멱등하다. 이미 있으면 덮어쓰지 않는다 — 특히 .env 를 다시 만들면 DB 비밀번호가 바뀌어
# 기존 볼륨에 붙지 못한다.
set -euo pipefail

# 어디서 실행하든 저장소 루트를 기준으로 삼는다. deploy/ 안에서 실행하면
# deploy/deploy/... 를 찾다 실패한다. deploy.sh 에는 있는데 여기만 빠져 있었다.
cd "$(dirname "$0")/.."

SSH_HOST="${SSH_HOST:-linux}"
REMOTE_DIR="${REMOTE_DIR:-/home/song/extguard}"

echo "== 배포 디렉토리 =="
ssh "$SSH_HOST" "mkdir -p '$REMOTE_DIR'"

echo "== .env (없을 때만 생성) =="
ssh "$SSH_HOST" "bash -s" <<REMOTE
set -euo pipefail
cd '$REMOTE_DIR'
if [ -f .env ]; then
  echo "  이미 있음 — 건드리지 않는다"
else
  umask 077
  {
    echo "POSTGRES_DB=extguard"
    echo "POSTGRES_USER=extguard"
    echo "POSTGRES_PASSWORD=\$(openssl rand -hex 24)"
    echo "MINIO_ACCESS_KEY=extguard"
    echo "MINIO_SECRET_KEY=\$(openssl rand -hex 24)"
    echo "MINIO_BUCKET=extguard-uploads"
    # 필터가 32자 이상을 요구한다(기동 시점 검증)
    echo "APP_ADMIN_TOKEN=\$(openssl rand -hex 24)"
    echo "APP_UPLOAD_MAX_SIZE=10MB"
    echo "APP_POLICY_ALLOW_EXTENSIONLESS=false"
  } > .env
  chmod 600 .env
  echo "  생성 완료 (값은 출력하지 않는다)"
fi
REMOTE

echo "== nginx =="
scp deploy/extguard-limits.conf "$SSH_HOST:/tmp/extguard-limits.conf"
scp deploy/flowtest.rktclgh.site.conf "$SSH_HOST:/tmp/flowtest.rktclgh.site.conf"
# sudo 는 비밀번호를 요구하므로 -t 로 TTY 를 붙인다. 없으면
# "a terminal is required to read the password" 로 끝난다.
ssh -t "$SSH_HOST" 'sudo install -m 644 /tmp/extguard-limits.conf /etc/nginx/conf.d/extguard-limits.conf \
  && sudo install -m 644 /tmp/flowtest.rktclgh.site.conf /etc/nginx/sites-available/flowtest.rktclgh.site.conf \
  && sudo ln -sfn /etc/nginx/sites-available/flowtest.rktclgh.site.conf /etc/nginx/sites-enabled/flowtest.rktclgh.site.conf \
  && rm -f /tmp/extguard-limits.conf /tmp/flowtest.rktclgh.site.conf \
  && sudo nginx -t && sudo systemctl reload nginx && echo "  nginx 반영 완료"'

echo
echo "완료. 관리 토큰이 필요하면 서버에서 직접 읽어라:"
echo "  ssh $SSH_HOST 'grep APP_ADMIN_TOKEN $REMOTE_DIR/.env'"

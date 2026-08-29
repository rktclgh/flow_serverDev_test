#!/usr/bin/env bash
# 이미지를 만들어 Docker Hub 에 올리고, 서버에서 받아 띄운다.
#
#   bash deploy/deploy.sh
#
# CI 파이프라인을 두지 않는다. 배포가 하루에 몇 번 있는 일이 아니고, 파이프라인 자체가
# 유지보수 대상이 된다. 로컬에서 빌드해 올리고 서버는 받아서 띄우기만 한다.
#
# ★ 서버에서 빌드하지 않는 이유: 빌드가 실패하면 그 순간 서비스도 못 뜬다.
#   받아온 이미지는 실패할 자리가 없다.
set -euo pipefail

IMAGE_REPO="${IMAGE_REPO:-songchih/extguard}"
SSH_HOST="${SSH_HOST:-linux}"
REMOTE_DIR="${REMOTE_DIR:-/home/song/extguard}"
PLATFORM="${PLATFORM:-linux/amd64}"   # 서버는 x86_64, 로컬은 arm64

cd "$(dirname "$0")/.."

# 커밋 SHA 를 태그로 쓴다. 무엇이 떠 있는지 되짚을 수 있어야 한다.
SHA="$(git rev-parse HEAD)"
if [ -n "$(git status --porcelain)" ]; then
  echo "작업트리가 깨끗하지 않다. 커밋하지 않은 변경이 이미지에 들어가면" >&2
  echo "태그(커밋 SHA)와 실제 내용이 달라진다." >&2
  git status --short >&2
  exit 1
fi
IMAGE="$IMAGE_REPO:$SHA"

echo "== 빌드·푸시: $IMAGE ($PLATFORM) =="
# 빌드 스테이지는 Dockerfile 에서 BUILDPLATFORM 으로 고정돼 네이티브로 돈다.
# jar 와 JS 번들은 아키텍처에 무관하므로 에뮬레이션은 런타임 스테이지에만 걸린다.
docker buildx build --platform "$PLATFORM" -t "$IMAGE" --push .

echo "== compose 파일 동기화 =="
scp docker-compose.yml docker-compose.prod.yml "$SSH_HOST:$REMOTE_DIR/"

echo "== 기동 =="
ssh "$SSH_HOST" "cd '$REMOTE_DIR' && IMAGE='$IMAGE' docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d"

echo "== 헬스체크 =="
for i in $(seq 1 30); do
  code="$(ssh "$SSH_HOST" "curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:18081/health" || true)"
  if [ "$code" = "200" ]; then
    echo "  오리진 200 ($i회째)"
    break
  fi
  [ "$i" = "30" ] && { echo "  기동 실패. 로그:" >&2; ssh "$SSH_HOST" "cd '$REMOTE_DIR' && docker compose -f docker-compose.yml -f docker-compose.prod.yml logs --tail 40 app" >&2; exit 1; }
  sleep 2
done

echo "== 외부 경로 =="
curl -s -o /dev/null -w "  https://flowtest.rktclgh.site/health -> %{http_code}\n" --max-time 15 https://flowtest.rktclgh.site/health

echo
echo "배포 완료: $IMAGE"

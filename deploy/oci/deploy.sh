#!/usr/bin/env bash
# OCI VM 에서 한 서비스(app 또는 pytube)만 새 이미지로 교체한다. 나머지 컨테이너는 유지.
# Usage: deploy.sh {dev|stg|prod} {app|pytube} <image-tag>
#
# /opt/pfplay/{env}/ 아래의 docker-compose.yml · .env 를 사용하고, 배포된 태그는 versions.env 에 남긴다.
# 그래서 platform 배포가 pytube 버전을, streaming 배포가 app 버전을 되돌리지 않는다.
# 기존 scripts/deploy.sh 와 달리 readiness 타임아웃이면 실패(exit 1)로 끝난다 (#342).
set -euo pipefail

ENV="${1:-}" SERVICE="${2:-}" TAG="${3:-}"
case "$ENV" in
  dev)  PORT=8082 ;;
  stg)  PORT=8081 ;;
  prod) PORT=8080 ;;
  *) echo "Usage: $0 {dev|stg|prod} {app|pytube} <image-tag>" >&2; exit 1 ;;
esac
case "$SERVICE" in app|pytube) ;; *) echo "unknown service: $SERVICE" >&2; exit 1 ;; esac
[[ -n "$TAG" ]] || { echo "image tag required" >&2; exit 1; }

DIR="${PFPLAY_DEPLOY_ROOT:-/opt/pfplay}/$ENV"
cd "$DIR"
[[ -f .env && -f docker-compose.yml ]] || { echo "[oci-deploy:$ENV] missing .env or docker-compose.yml in $DIR" >&2; exit 1; }

# versions.env 갱신 — 처음 배포라면 아직 없는 쪽 태그는 기본값으로 시작한다.
touch versions.env
set_version() { grep -v "^$1=" versions.env > versions.env.tmp || true; echo "$1=$2" >> versions.env.tmp; mv versions.env.tmp versions.env; }
grep -q '^STREAMING_TAG=' versions.env || set_version STREAMING_TAG "${DEFAULT_STREAMING_TAG:-v1.0.0}"
set_version APP_PORT "$PORT"
if [[ "$SERVICE" == app ]]; then set_version APP_TAG "$TAG"; else set_version STREAMING_TAG "$TAG"; fi

COMPOSE=(docker compose -p "pfplay-$ENV" --env-file .env --env-file versions.env -f docker-compose.yml)

if ! grep -q '^APP_TAG=' versions.env; then
  echo "[oci-deploy:$ENV] app has never been deployed here; recorded $SERVICE=$TAG and stopping." >&2
  exit 0
fi

echo "[oci-deploy:$ENV] $SERVICE -> $TAG"
"${COMPOSE[@]}" pull "$SERVICE"
# 처음이면 나머지 서비스도 띄우되, 이미 떠 있는 컨테이너는 건드리지 않는다.
"${COMPOSE[@]}" up -d --no-recreate mysql redis pytube
"${COMPOSE[@]}" up -d "$SERVICE"

echo "[oci-deploy:$ENV] waiting for $SERVICE..."
for _ in $(seq 1 60); do
  if [[ "$SERVICE" == app ]]; then
    # actuator 가 없어 "Spring 이 HTTP 로 답하면 기동 완료"로 본다. 000 은 아직 연결 불가.
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/" || true)
    case "$code" in 2??|3??|401|403|404) echo "[oci-deploy:$ENV] app OK ($code)"; exit 0 ;; esac
  else
    if "${COMPOSE[@]}" exec -T pytube python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:9000/hello', timeout=3)" 2>/dev/null; then
      echo "[oci-deploy:$ENV] pytube OK"; exit 0
    fi
  fi
  sleep 3
done
echo "[oci-deploy:$ENV] $SERVICE did not become ready in 180s" >&2
"${COMPOSE[@]}" logs --tail 80 "$SERVICE" >&2 || true
exit 1

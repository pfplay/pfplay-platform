#!/usr/bin/env bash
# GHCR에서 image pull 후 app 컨테이너만 재기동 (mysql/redis/pytube 유지).
# Usage: ./scripts/deploy.sh {dev|stg|prod}
#
# CI에서 호출 시 환경변수 GHCR_USER, GHCR_TOKEN을 넘기면 자동 로그인.
# 로컬 수동 호출 시 docker login ghcr.io 가 이미 돼있다면 그대로 동작.
#
# 로컬에서 코드 변경분으로 다시 빌드하고 싶다면 이 스크립트 대신:
#   docker compose -f docker-compose.{env}.yml -p pfplay-{env} --env-file .env.{env} up -d --build app
set -euo pipefail

ENV="${1:-}"
case "$ENV" in
  dev)  PORT=9090 ;;
  stg)  PORT=8080 ;;
  prod) PORT=8080 ;;
  *) echo "Usage: $0 {dev|stg|prod}" >&2; exit 1 ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -n "${GHCR_TOKEN:-}" && -n "${GHCR_USER:-}" ]]; then
  echo "[deploy:$ENV] docker login ghcr.io..."
  echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin
fi

COMPOSE=(docker compose
  -f "docker-compose.${ENV}.yml"
  -p "pfplay-${ENV}"
  --env-file ".env.${ENV}")

echo "[deploy:$ENV] pulling app image..."
"${COMPOSE[@]}" pull app

echo "[deploy:$ENV] restarting app (mysql/redis/pytube intact)..."
"${COMPOSE[@]}" up -d --no-deps app

echo "[deploy:$ENV] waiting for app on http://localhost:${PORT}..."
# actuator 미사용 환경이라 "어떤 HTTP 응답이든 오면 app 기동 완료"로 판정.
# 2xx/3xx/401/403 은 Spring이 정상 응답하는 신호. 000은 connection refused.
# prod 프로필은 /v3/api-docs가 404라 이런 관대한 매칭이 필요함.
for i in $(seq 1 30); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${PORT}/" 2>/dev/null || echo 000)
  case "$code" in
    2??|3??|401|403)
      echo "[deploy:$ENV] OK ($code)"
      exit 0
      ;;
  esac
  sleep 2
done
echo "[deploy:$ENV] WARN: readiness check timed out (container may still be starting)" >&2
exit 0

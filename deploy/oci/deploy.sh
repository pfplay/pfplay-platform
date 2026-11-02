#!/usr/bin/env bash
# OCI VM 에서 app 을 새 이미지로 교체하고, pytube 를 streaming-versions.env 에 고정된 버전으로 맞춘다.
# Usage: deploy.sh {dev|stg|prod} <app-image-tag>
#
# /opt/pfplay/{env}/ 아래의 compose.yml · .env · streaming-versions.env 를 사용한다.
# mysql · redis 는 유지되고, pytube 는 태그가 바뀐 경우에만 재생성된다.
# 기존 scripts/deploy.sh 와 달리 readiness 타임아웃이면 실패(exit 1)로 끝난다 (#342).
set -euo pipefail

ENV="${1:-}" APP_TAG="${2:-}"
case "$ENV" in
  dev)  PORT=8082 ;;
  stg)  PORT=8081 ;;
  prod) PORT=8080 ;;
  *) echo "Usage: $0 {dev|stg|prod} <app-image-tag>" >&2; exit 1 ;;
esac
[[ -n "$APP_TAG" ]] || { echo "app image tag required" >&2; exit 1; }

DIR="${PFPLAY_DEPLOY_ROOT:-/opt/pfplay}/$ENV"
cd "$DIR"
for f in .env compose.yml streaming-versions.env; do
  [[ -f "$f" ]] || { echo "[oci-deploy:$ENV] missing $f in $DIR" >&2; exit 1; }
done

key="STREAMING_TAG_$(tr '[:lower:]' '[:upper:]' <<< "$ENV")"
STREAMING_TAG=$(sed -n "s/^$key=//p" streaming-versions.env)
[[ -n "$STREAMING_TAG" ]] || { echo "[oci-deploy:$ENV] $key not set in streaming-versions.env" >&2; exit 1; }

# compose 가 읽는 이번 배포의 태그 · 포트. 롤백 시에도 다시 쓰여진다.
printf 'APP_TAG=%s\nSTREAMING_TAG=%s\nAPP_PORT=%s\n' "$APP_TAG" "$STREAMING_TAG" "$PORT" > versions.env
COMPOSE=(docker compose -p "pfplay-$ENV" --env-file .env --env-file versions.env -f compose.yml)

echo "[oci-deploy:$ENV] app=$APP_TAG pytube=$STREAMING_TAG"
"${COMPOSE[@]}" pull app pytube
# 처음이면 전부 올라오고, 이후에는 이미지 · 설정이 바뀐 서비스만 재생성된다.
"${COMPOSE[@]}" up -d

echo "[oci-deploy:$ENV] waiting for app and pytube..."
for _ in $(seq 1 60); do
  # actuator 가 없어 "Spring 이 HTTP 로 답하면 기동 완료"로 본다. 000 은 아직 연결 불가.
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/" || true)
  case "$code" in
    2??|3??|401|403|404)
      if "${COMPOSE[@]}" exec -T pytube python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:9000/hello', timeout=3)" 2>/dev/null; then
        echo "[oci-deploy:$ENV] OK (app $code, pytube up)"; exit 0
      fi ;;
  esac
  sleep 3
done
echo "[oci-deploy:$ENV] app or pytube did not become ready in 180s" >&2
"${COMPOSE[@]}" ps >&2 || true
"${COMPOSE[@]}" logs --tail 80 app pytube >&2 || true
exit 1

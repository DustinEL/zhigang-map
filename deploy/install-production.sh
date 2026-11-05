#!/usr/bin/env bash
set -euo pipefail

PACKAGE_ROOT="${1:?Usage: install-production.sh /path/to/release-package}"
SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="${APP_ROOT:-/opt/zhigangmap}"
RELEASE_ID="$(basename "$(cd "$PACKAGE_ROOT" && pwd)")"
RELEASE_ROOT="$APP_ROOT/releases/$RELEASE_ID"
CURRENT_LINK="$APP_ROOT/current"
PREVIOUS_TARGET=""

if [ "$(id -u)" -ne 0 ]; then echo "Run as root." >&2; exit 1; fi
bash "$SCRIPT_ROOT/preflight-production.sh" "$PACKAGE_ROOT"
test -f "$PACKAGE_ROOT/backend/zhigang-backend.jar"
test -f "$PACKAGE_ROOT/frontend/index.html"
test -f /etc/zhigangmap/zhigang.env
if [ -e "$RELEASE_ROOT" ]; then echo "Release already exists: $RELEASE_ROOT" >&2; exit 1; fi

install -d -o www -g www "$APP_ROOT/releases"
cp -a "$PACKAGE_ROOT" "$RELEASE_ROOT"
chown -R www:www "$RELEASE_ROOT"
if [ -L "$CURRENT_LINK" ]; then PREVIOUS_TARGET="$(readlink -f "$CURRENT_LINK")"; fi
ln -sfn "$RELEASE_ROOT" "$APP_ROOT/current.next"
mv -Tf "$APP_ROOT/current.next" "$CURRENT_LINK"

install -m 0644 "$PACKAGE_ROOT/config/zhigang-backend.service" /etc/systemd/system/zhigang-backend.service
if [ -d /etc/nginx/conf.d ]; then
  install -m 0644 "$PACKAGE_ROOT/config/nginx-zhigangmap-rate-limit.conf" /etc/nginx/conf.d/zhigangmap-rate-limit.conf
fi
systemctl daemon-reload
systemctl restart zhigang-backend

healthy=false
for _ in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null; then healthy=true; break; fi
  sleep 2
done
if [ "$healthy" = true ]; then
  runtime_status="$(curl -fsS http://127.0.0.1:8080/api/runtime-status || true)"
  if ! printf '%s' "$runtime_status" | grep -q '"operatingMode":"MYSQL_FACT_SOURCE"'; then
    healthy=false
  fi
  if printf '%s' "$runtime_status" | grep -Eq '"dataMode":"(DEMO|ERROR)"'; then
    healthy=false
  fi
fi
if [ "$healthy" != true ]; then
  if [ -n "$PREVIOUS_TARGET" ]; then ln -sfn "$PREVIOUS_TARGET" "$CURRENT_LINK"; systemctl restart zhigang-backend; fi
  echo "Health check failed; previous release restored." >&2
  exit 1
fi

nginx -t
systemctl reload nginx
echo "Release active: $RELEASE_ID"

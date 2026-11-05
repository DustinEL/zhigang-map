#!/usr/bin/env bash
set -euo pipefail

PACKAGE_ROOT="${1:?Usage: preflight-production.sh /path/to/release-package}"
ENV_FILE="${ZHIGANG_ENV_FILE:-/etc/zhigangmap/zhigang.env}"

fail() {
  echo "Production preflight failed: $1" >&2
  exit 1
}

env_value() {
  awk -F= -v key="$1" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$ENV_FILE"
}

require_value() {
  local name="$1"
  local value
  value="$(env_value "$name")"
  if [ -z "$value" ] || [ "$value" = "CHANGE_ME" ]; then
    fail "$name is missing or still uses CHANGE_ME"
  fi
  printf '%s' "$value"
}

if [ "${BACKUP_CONFIRMED:-false}" != "true" ]; then
  fail "set BACKUP_CONFIRMED=true only after the website, Nginx, old backend and MySQL backups are complete"
fi

for command in sha256sum java nginx curl awk grep; do
  command -v "$command" >/dev/null 2>&1 || fail "$command is not installed"
done

test -f "$PACKAGE_ROOT/backend/zhigang-backend.jar" || fail "backend JAR is missing"
test -f "$PACKAGE_ROOT/frontend/index.html" || fail "frontend index.html is missing"
test -f "$PACKAGE_ROOT/SHA256SUMS.txt" || fail "SHA256SUMS.txt is missing"
test -f "$ENV_FILE" || fail "$ENV_FILE is missing"

(cd "$PACKAGE_ROOT" && sha256sum -c SHA256SUMS.txt >/dev/null) || fail "package checksum validation failed"

profiles="$(require_value SPRING_PROFILES_ACTIVE)"
case ",$profiles," in
  *,mysql,*) ;;
  *) fail "SPRING_PROFILES_ACTIVE must include mysql" ;;
esac
case ",$profiles," in
  *,prod,*) ;;
  *) fail "SPRING_PROFILES_ACTIVE must include prod" ;;
esac

[ "$(require_value DEV_PERMIT_ALL)" = "false" ] || fail "DEV_PERMIT_ALL must be false"
[ "$(require_value SECURE_COOKIE)" = "true" ] || fail "SECURE_COOKIE must be true"
[ "$(require_value SPRINGDOC_ENABLED)" = "false" ] || fail "SPRINGDOC_ENABLED must be false"
[ "$(require_value FLYWAY_ENABLED)" = "false" ] || fail "FLYWAY_ENABLED must be false"
[ "$(require_value ZHIGANG_REDIS_ENABLED)" = "false" ] || fail "ZHIGANG_REDIS_ENABLED must be false"

mysql_url="$(require_value MYSQL_URL)"
case "$mysql_url" in
  jdbc:mysql://127.0.0.1:*|jdbc:mysql://localhost:*|*REMOTE_MYSQL_HOST*|*CHANGE_ME*)
    fail "MYSQL_URL must point to the restricted remote MySQL server"
    ;;
esac

require_value MYSQL_USERNAME >/dev/null
require_value MYSQL_PASSWORD >/dev/null
editor_user="$(require_value PROFESSIONAL_USERNAME)"
reviewer_user="$(require_value REVIEWER_USERNAME)"
require_value PROFESSIONAL_PASSWORD >/dev/null
require_value REVIEWER_PASSWORD >/dev/null
[ "$editor_user" != "$reviewer_user" ] || fail "professional and reviewer usernames must be different"

if [ -r /proc/meminfo ]; then
  memory_kb="$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)"
  [ "${memory_kb:-0}" -ge 450000 ] || fail "server memory is below the supported 512MB class"
fi

echo "Production preflight passed."

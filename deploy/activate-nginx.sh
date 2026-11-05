#!/usr/bin/env bash
set -euo pipefail

PACKAGE_ROOT="${1:?Usage: activate-nginx.sh /path/to/release-package}"
NGINX_SITE_CONFIG="${NGINX_SITE_CONFIG:?Set NGINX_SITE_CONFIG to the active zhigangmap vhost file}"
RATE_LIMIT_CONFIG="${RATE_LIMIT_CONFIG:-/etc/nginx/conf.d/zhigangmap-rate-limit.conf}"
BACKUP_ROOT="${BACKUP_ROOT:-/opt/zhigangmap/backups/nginx}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
RATE_LIMIT_BACKUP=""

if [ "$(id -u)" -ne 0 ]; then echo "Run as root." >&2; exit 1; fi
test -f "$PACKAGE_ROOT/config/nginx-zhigangmap-ssl.conf"
test -f "$PACKAGE_ROOT/config/nginx-zhigangmap-rate-limit.conf"
test -f "$NGINX_SITE_CONFIG"

install -d "$BACKUP_ROOT"
cp -a "$NGINX_SITE_CONFIG" "$BACKUP_ROOT/$(basename "$NGINX_SITE_CONFIG").$TIMESTAMP"
install -d "$(dirname "$RATE_LIMIT_CONFIG")"
if [ -f "$RATE_LIMIT_CONFIG" ]; then
  RATE_LIMIT_BACKUP="$BACKUP_ROOT/$(basename "$RATE_LIMIT_CONFIG").$TIMESTAMP"
  cp -a "$RATE_LIMIT_CONFIG" "$RATE_LIMIT_BACKUP"
fi
install -m 0644 "$PACKAGE_ROOT/config/nginx-zhigangmap-rate-limit.conf" "$RATE_LIMIT_CONFIG"
install -m 0644 "$PACKAGE_ROOT/config/nginx-zhigangmap-ssl.conf" "$NGINX_SITE_CONFIG"

if ! nginx -t; then
  cp -a "$BACKUP_ROOT/$(basename "$NGINX_SITE_CONFIG").$TIMESTAMP" "$NGINX_SITE_CONFIG"
  if [ -n "$RATE_LIMIT_BACKUP" ]; then cp -a "$RATE_LIMIT_BACKUP" "$RATE_LIMIT_CONFIG"; else rm -f "$RATE_LIMIT_CONFIG"; fi
  nginx -t
  echo "Nginx configuration was restored." >&2
  exit 1
fi

systemctl reload nginx
echo "Nginx configuration active: $NGINX_SITE_CONFIG"

#!/usr/bin/env bash
set -euo pipefail

PACKAGE_ROOT="${1:?Usage: install-static.sh /path/to/extracted-static-site}"
APP_ROOT="${APP_ROOT:-/opt/zhigangmap-static}"
RELEASE_ID="$(date +%Y%m%d-%H%M%S)"
RELEASE_ROOT="$APP_ROOT/releases/$RELEASE_ID"
CURRENT_LINK="$APP_ROOT/current"
PREVIOUS_TARGET=""
WEB_USER="${WEB_USER:-}"

if [ "$(id -u)" -ne 0 ]; then echo "Run as root." >&2; exit 1; fi
if [ -z "$WEB_USER" ]; then
  for candidate in www-data nginx www; do
    if id "$candidate" >/dev/null 2>&1; then WEB_USER="$candidate"; break; fi
  done
fi
if [ -z "$WEB_USER" ]; then echo "Nginx system user not found; set WEB_USER explicitly." >&2; exit 1; fi
WEB_GROUP="${WEB_GROUP:-$(id -gn "$WEB_USER")}"
test -f "$PACKAGE_ROOT/index.html"
test -d "$PACKAGE_ROOT/assets"
test -f "$PACKAGE_ROOT/data/current.json"
python3 -m json.tool "$PACKAGE_ROOT/data/current.json" >/dev/null
install -d -o "$WEB_USER" -g "$WEB_GROUP" "$APP_ROOT/releases"
if [ -L "$CURRENT_LINK" ]; then PREVIOUS_TARGET="$(readlink -f "$CURRENT_LINK")"; fi
install -d "$RELEASE_ROOT"
cp -a "$PACKAGE_ROOT/index.html" "$PACKAGE_ROOT/assets" "$PACKAGE_ROOT/data" "$RELEASE_ROOT/"
for public_file in favicon.svg logo-mark.svg; do
  if [ -f "$PACKAGE_ROOT/$public_file" ]; then cp -a "$PACKAGE_ROOT/$public_file" "$RELEASE_ROOT/"; fi
done
chown -R "$WEB_USER:$WEB_GROUP" "$RELEASE_ROOT"
ln -sfn "$RELEASE_ROOT" "$APP_ROOT/current.next"
mv -Tf "$APP_ROOT/current.next" "$CURRENT_LINK"
if ! nginx -t; then
  if [ -n "$PREVIOUS_TARGET" ]; then ln -sfn "$PREVIOUS_TARGET" "$CURRENT_LINK"; fi
  echo "Nginx check failed; previous static release restored." >&2
  exit 1
fi
systemctl reload nginx
echo "Static release active: $RELEASE_ID"

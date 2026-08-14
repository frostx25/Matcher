#!/usr/bin/env sh
set -eu

check_url() {
  name="$1"
  url="$2"
  attempt=1
  while [ "$attempt" -le 3 ]; do
    code="$(curl --silent --show-error --location --max-time 10 --output /dev/null --write-out '%{http_code}' "$url" || true)"
    [ "$code" = "200" ] && return 0
    attempt=$((attempt + 1))
    sleep 2
  done
  logger -t vibeali-health "unhealthy service=$name http_status=${code:-network_error}"
  return 1
}

check_url public https://vibeali.shop/healthz
check_url admin https://admin.vibeali.shop/healthz
logger -t vibeali-health "healthy public=200 admin=200"

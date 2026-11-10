#!/usr/bin/env bash
# One command for the local Docker backend the API suite writes to.
#
#   scripts/local-backend.sh up        start or rebuild web, sidekiq, db, redis
#   scripts/local-backend.sh seed      two salons + GST + staff, tokens into
#                                      src/test/resources/config.local.properties
#   scripts/local-backend.sh status    containers, Sidekiq, a token check
#   scripts/local-backend.sh reset --yes
#                                      DROP the local database, reload schema,
#                                      db:seed, then seed. Destroys local data.
#   scripts/local-backend.sh all       up + seed + status
#
# BACKEND_DIR defaults to ../backend next to this repository. The backend's own
# compose file is never edited; docker/local-backend.override.yml is layered on.
# Never touches production: it only talks to the containers and localhost.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_DIR="${BACKEND_DIR:-$ROOT/../backend}"
COMPOSE_FILE="$BACKEND_DIR/docker/development/docker-compose.yml"
OVERRIDE="$ROOT/docker/local-backend.override.yml"
CONFIG="$ROOT/src/test/resources/config.local.properties"
API="${LOCAL_API:-http://localhost:3000}"

[ -f "$COMPOSE_FILE" ] || { echo "backend compose file not found: $COMPOSE_FILE (set BACKEND_DIR)"; exit 1; }

dc() { docker compose -f "$COMPOSE_FILE" -f "$OVERRIDE" "$@"; }
rails_runner() { dc exec -T web bash -lc "cd /nearz && bundle exec rails runner $1" 2>/dev/null; }

wait_for_api() {
  for _ in $(seq 1 60); do
    code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$API/api-docs/index.html" || true)
    [ "$code" = "200" ] && return 0
    sleep 3
  done
  echo "API at $API did not come up"; exit 1
}

ensure_timezone() {
  # Rails uses Asia/Calcutta; the Postgres 15 image only ships Asia/Kolkata,
  # and every report answers 500 without it. Lost when db is recreated.
  if ! dc exec -T db sh -c 'test -e /usr/share/zoneinfo/Asia/Calcutta'; then
    echo "installing tzdata-legacy in the db container"
    dc exec -T db sh -c 'apt-get update -qq && apt-get install -y -qq tzdata-legacy >/dev/null'
  fi
}

cmd_up() {
  dc up -d --build
  wait_for_api
  ensure_timezone
}

cmd_seed() {
  [ "$(git -C "$ROOT" check-ignore "$CONFIG" >/dev/null && echo ok)" = "ok" ] \
    || { echo "$CONFIG is not git-ignored; refusing to write tokens"; exit 1; }
  container=$(dc ps -q web)
  docker cp "$ROOT/scripts/local_seed.rb" "$container:/tmp/local_seed.rb"
  out=$(rails_runner /tmp/local_seed.rb | grep -E '^[A-Z_]+=' || true)
  value() { printf '%s\n' "$out" | sed -n "s/^$1=//p" | tail -1; }
  journey=$(value JOURNEY_SALON); other=$(value OTHER_SALON); other_staff=$(value OTHER_STAFF)
  journey_token=$(value JOURNEY_TOKEN); other_token=$(value OTHER_TOKEN)
  [ -n "$journey" ] && [ -n "$other" ] && [ -n "$journey_token" ] && [ -n "$other_token" ] \
    || { echo "seed did not return salons and tokens"; exit 1; }

  # The cross-salon check needs staff in the other salon. Created through the
  # API, as a salon owner would, so they pass every model validation.
  if [ "${other_staff:-0}" -lt 2 ]; then
    for n in 1 2; do
      phone="6000000$(printf '%03d' $((100 + n)))"
      curl -s -o /dev/null -m 30 -w "other-salon staff $n -> %{http_code}\n" \
        -X POST "$API/salons/$other/staff" \
        -H "Authorization: Bearer $other_token" -H 'Content-Type: application/json' \
        -d "{\"name\":\"QA Other Stylist $n\",\"phone\":\"$phone\",\"email\":\"qa.other.$n@example.test\",\"job_title\":\"Beautician\",\"experience_years\":2,\"shift_start\":\"09:00\",\"shift_end\":\"21:00\",\"weekly_off\":[],\"status\":\"active\"}"
    done
  fi

  umask 077
  cat > "$CONFIG" <<EOF
# Local Docker backend. Written by scripts/local-backend.sh seed on $(date +%F).
# Use with: mvn test -DconfigFile=config.local.properties
# Tokens are local JsonWebToken.encode tokens, valid 90 days.
baseUrl = $API

journeySalonId = $journey
journeyToken   = $journey_token

# Read-only role. Locally the journey salon stands in for it.
ownerSalonId = $journey
ownerToken   = $journey_token

# A second salon under a different owner, for the cross-salon checks.
otherSalonId = $other
otherToken   = $other_token
EOF
  echo "wrote $CONFIG (journey salon $journey, other salon $other)"
}

cmd_status() {
  dc ps --format '{{.Service}}\t{{.State}}\t{{.Status}}'
  echo "sidekiq processes: $(dc exec -T redis redis-cli scard processes)"
  echo "sidekiq retry/dead: $(dc exec -T redis redis-cli zcard retry)/$(dc exec -T redis redis-cli zcard dead)"
  if [ -f "$CONFIG" ]; then
    salon=$(sed -n 's/^journeySalonId *= *//p' "$CONFIG"); token=$(sed -n 's/^journeyToken *= *//p' "$CONFIG")
    echo "journey token check: $(curl -s -o /dev/null -m 20 -w '%{http_code}' -H "Authorization: Bearer $token" "$API/salons/$salon/working_days")"
  fi
}

cmd_reset() {
  [ "${1:-}" = "--yes" ] || { echo "reset DROPS the local database. Re-run as: $0 reset --yes"; exit 1; }
  dc exec -T web bash -lc 'cd /nearz && bundle exec rails db:drop db:create db:schema:load db:seed'
  cmd_seed
}

case "${1:-}" in
  up) cmd_up ;;
  seed) cmd_seed ;;
  status) cmd_status ;;
  reset) shift; cmd_reset "$@" ;;
  all) cmd_up; cmd_seed; cmd_status ;;
  *) sed -n '2,15p' "$0"; exit 1 ;;
esac

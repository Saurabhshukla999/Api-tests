#!/usr/bin/env bash
# One command for the local backend the API suite writes to.
#
#   scripts/local-backend.sh all         pin + up + seed + status (start here)
#   scripts/local-backend.sh pin         fetch origin/new_master_aws into the
#                                        pinned checkout and regenerate overrides
#   scripts/local-backend.sh up          build and start web, sidekiq, db, redis
#   scripts/local-backend.sh seed        two salons + GST + staff; tokens into
#                                        src/test/resources/config.local.properties
#   scripts/local-backend.sh status      pinned commit, containers, Sidekiq, token
#   scripts/local-backend.sh reset --yes DROP the stack's database, load the
#                                        schema, db:seed, then seed
#
# STACK=pinned (default): docker/pinned/docker-compose.yml runs a CLEAN checkout
#   of origin/new_master_aws (BACKEND_SRC, default ../backend-pinned, a git
#   worktree of BACKEND_REPO, default ../backend) on port 3100, with its own
#   database. The backend working folder is never used or changed.
# STACK=dev: the backend's own development compose file on port 3000, running
#   whatever is checked out in the backend folder (the old behaviour).
# CI: set BACKEND_SRC to a checkout of the branch under test and SKIP_PIN=1.
#
# Never touches production: only the containers and localhost.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STACK="${STACK:-pinned}"
CONFIG="$ROOT/src/test/resources/config.local.properties"
PIN_REF="${PIN_REF:-origin/new_master_aws}"
BACKEND_REPO="${BACKEND_REPO:-$ROOT/../backend}"

winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

if [ "$STACK" = "pinned" ]; then
  PINNED_DIR="$ROOT/docker/pinned"
  BACKEND_SRC="${BACKEND_SRC:-$ROOT/../backend-pinned}"
  API="${LOCAL_API:-http://localhost:3100}"
  WEB_CONTAINER="nearz-tests-web-1"
  dc() {
    BACKEND_SRC="$(winpath "$BACKEND_SRC")" PINNED_DIR="$(winpath "$PINNED_DIR")" \
      docker compose -f "$PINNED_DIR/docker-compose.yml" "$@"
  }
elif [ "$STACK" = "dev" ]; then
  BACKEND_DIR="${BACKEND_DIR:-$BACKEND_REPO}"
  COMPOSE_FILE="$BACKEND_DIR/docker/development/docker-compose.yml"
  [ -f "$COMPOSE_FILE" ] || { echo "backend compose file not found: $COMPOSE_FILE (set BACKEND_DIR)"; exit 1; }
  API="${LOCAL_API:-http://localhost:3000}"
  WEB_CONTAINER="development-web-1"
  dc() { docker compose -f "$COMPOSE_FILE" -f "$ROOT/docker/local-backend.override.yml" "$@"; }
else
  echo "STACK must be pinned or dev"; exit 1
fi

rails_runner() { dc exec -T web bash -lc "cd /nearz && bundle exec rails runner $1" 2>/dev/null; }

# ---------------------------------------------------------------------------
# Pinning: a clean, detached checkout of new_master_aws
# ---------------------------------------------------------------------------
cmd_pin() {
  [ "$STACK" = "pinned" ] || { echo "pin only applies to STACK=pinned"; return; }
  if [ "${SKIP_PIN:-}" != "1" ]; then
    git -C "$BACKEND_REPO" fetch -q origin "${PIN_REF#origin/}"
    if [ ! -e "$BACKEND_SRC/.git" ]; then
      git -C "$BACKEND_REPO" worktree add --detach "$BACKEND_SRC" "$PIN_REF"
      # Docker creates the mounted initializer's mount point inside the
      # checkout; keep it out of `git status` (a local exclude, no commit).
      exclude="$(git -C "$BACKEND_REPO" rev-parse --path-format=absolute --git-common-dir)/info/exclude"
      grep -qx 'config/initializers/zz_nearz_test_stack.rb' "$exclude" 2>/dev/null \
        || echo 'config/initializers/zz_nearz_test_stack.rb' >> "$exclude"
    else
      if [ -n "$(git -C "$BACKEND_SRC" status --porcelain)" ]; then
        echo "the pinned checkout $BACKEND_SRC has local changes; it must stay clean:"
        git -C "$BACKEND_SRC" status --short | head -10
        exit 1
      fi
      git -C "$BACKEND_SRC" checkout -q --detach "$PIN_REF"
    fi
  fi
  generate_overrides
  echo "pinned: $(git -C "$BACKEND_SRC" log -1 --format='%h %ad %s' --date=short)"
}

# Copy three config files from the pinned checkout and apply exact patches.
# A patch that no longer matches stops the script instead of drifting.
generate_overrides() {
  local out="$PINNED_DIR/.generated"
  PYTHON=""
  for candidate in python3 python; do
    if "$candidate" -c 'import sys' >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
  done
  [ -n "$PYTHON" ] || { echo "python is needed to generate the overrides"; exit 1; }
  mkdir -p "$out"
  patch_file() {   # src dest search replace
    local src="$BACKEND_SRC/$1" dest="$out/$2"
    tr -d '\r' < "$src" > "$dest"
    shift 2
    while [ $# -ge 2 ]; do
      grep -qF -- "$1" "$dest" || { echo "override patch no longer matches $src: '$1'"; exit 1; }
      SEARCH="$1" REPLACE="$2" "$PYTHON" - "$dest" <<'PY'
import os, sys
p = sys.argv[1]; t = open(p, encoding="utf-8").read()
s, r = os.environ["SEARCH"], os.environ["REPLACE"]
if s not in t:
    sys.exit("patch target missing")
open(p, "w", encoding="utf-8", newline="\n").write(t.replace(s, r, 1))   # first occurrence only
PY
      shift 2
    done
  }
  # Database and Redis live in other containers, not on localhost.
  # The first `host: localhost` is the development section's (test follows it).
  patch_file config/database.yml database.yml \
    "  host: localhost" \
    "  host: <%= ENV.fetch(\"DATABASE_HOST\", \"localhost\") %>"
  awk '/^development:/{d=1} /^test:/{d=0} d && /DATABASE_HOST/{ok=1} END{exit !ok}' "$out/database.yml" \
    || { echo "database.yml: the development section did not get the DATABASE_HOST patch"; exit 1; }
  patch_file config/initializers/sidekiq.rb sidekiq.rb \
    'redis_url = Rails.application.credentials.dig(Rails.env.to_sym, :redis) { "redis://localhost:6379/0" }' \
    'redis_url = ENV["REDIS_URL"] || Rails.application.credentials.dig(Rails.env.to_sym, :redis) || "redis://localhost:6379/0"'
  # Report caching on the stack's Redis, as in production; uploads on local
  # disk because development points at Cloudinary without configuring it.
  patch_file config/environments/development.rb development.rb \
    "config.cache_store = :redis_cache_store, {url: 'redis://localhost:6379'}" \
    'config.cache_store = :redis_cache_store, { url: ENV.fetch("REDIS_URL", "redis://localhost:6379") }' \
    "config.active_storage.service = :cloudinary" \
    "config.active_storage.service = :local" \
    "config.eager_load = false" \
    "config.eager_load = true"   # load all code at boot, as production does: no cold first request
}

wait_for_api() {
  # Any HTTP answer means Rails is up. A brand-new database answers 500
  # (pending migrations) until `all` loads the schema, so do not wait for 200.
  for _ in $(seq 1 100); do
    code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$API/api-docs/index.html" || true)
    [ "$code" != "000" ] && [ -n "$code" ] && return 0
    sleep 3
  done
  echo "API at $API did not come up"; dc logs --tail 40 web; exit 1
}

ensure_timezone() {
  # Built into docker/pinned/Dockerfile.db; the dev stack loses it on recreate.
  if ! dc exec -T db sh -c 'test -e /usr/share/zoneinfo/Asia/Calcutta'; then
    echo "installing tzdata-legacy in the db container"
    dc exec -T db sh -c 'apt-get update -qq && apt-get install -y -qq tzdata-legacy >/dev/null'
  fi
}

cmd_up() {
  [ "$STACK" != "pinned" ] || [ -f "$PINNED_DIR/.generated/development.rb" ] || cmd_pin
  dc up -d --build
  wait_for_api
  ensure_timezone
}

cmd_seed() {
  git -C "$ROOT" check-ignore -q "$CONFIG" || { echo "$CONFIG is not git-ignored; refusing to write tokens"; exit 1; }
  container=$(dc ps -q web)
  docker cp "$ROOT/scripts/local_seed.rb" "$container:/tmp/local_seed.rb"
  out=$(rails_runner /tmp/local_seed.rb | grep -E '^[A-Z_]+=' || true)
  value() { printf '%s\n' "$out" | sed -n "s/^$1=//p" | tail -1; }
  journey=$(value JOURNEY_SALON); other=$(value OTHER_SALON); other_staff=$(value OTHER_STAFF)
  journey_token=$(value JOURNEY_TOKEN); other_token=$(value OTHER_TOKEN)
  [ -n "$journey" ] && [ -n "$other" ] && [ -n "$journey_token" ] && [ -n "$other_token" ] \
    || { echo "seed did not return salons and tokens (is the database loaded? try reset --yes)"; exit 1; }

  # The cross-salon checks need staff in the other salon, created through the API.
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
# Local backend ($STACK stack). Written by scripts/local-backend.sh seed on $(date +%F).
# Use with: mvn test -DconfigFile=config.local.properties
# Tokens are local JsonWebToken.encode tokens, valid 90 days, for this stack only.
baseUrl = $API
localWebContainer = $WEB_CONTAINER

journeySalonId = $journey
journeyToken   = $journey_token

# Read-only role. Locally the journey salon stands in for it.
ownerSalonId = $journey
ownerToken   = $journey_token

# A second salon under a different owner, for the cross-salon checks.
otherSalonId = $other
otherToken   = $other_token
EOF
  echo "wrote $CONFIG (stack $STACK, journey salon $journey, other salon $other)"
}

cmd_status() {
  if [ "$STACK" = "pinned" ]; then
    pinned=$(git -C "$BACKEND_SRC" rev-parse --short HEAD)
    tip=$(git -C "$BACKEND_REPO" rev-parse --short "$PIN_REF" 2>/dev/null || echo "?")
    dirty=$([ -z "$(git -C "$BACKEND_SRC" status --porcelain)" ] && echo clean || echo "MODIFIED")
    echo "pinned checkout: $pinned ($dirty); $PIN_REF is $tip$([ "$pinned" = "$tip" ] && echo ' - up to date' || echo ' - run pin to update')"
  fi
  dc ps --format '{{.Service}}\t{{.State}}\t{{.Status}}'
  echo "sidekiq processes: $(dc exec -T redis redis-cli scard processes)"
  echo "sidekiq retry/dead: $(dc exec -T redis redis-cli zcard retry)/$(dc exec -T redis redis-cli zcard dead)"
  if [ -f "$CONFIG" ]; then
    salon=$(sed -n 's/^journeySalonId *= *//p' "$CONFIG"); token=$(sed -n 's/^journeyToken *= *//p' "$CONFIG")
    echo "journey token check on $API: $(curl -s -o /dev/null -m 20 -w '%{http_code}' -H "Authorization: Bearer $token" "$API/salons/$salon/working_days")"
  fi
}

cmd_reset() {
  [ "${1:-}" = "--yes" ] || { echo "reset DROPS the $STACK stack's database. Re-run as: $0 reset --yes"; exit 1; }
  # The web server and Sidekiq hold connections, so the drop would fail with
  # "database is being accessed by other users". Stop them, rebuild the
  # database in a one-off container, then start them again.
  dc stop web sidekiq
  # SKIP_TEST_DATABASE: in development Rails also drops/creates the test
  # database, which this stack does not run.
  dc run --rm -T -e DISABLE_SPRING=1 -e SKIP_TEST_DATABASE=true web \
    bash -lc 'cd /nearz && bundle exec rails db:drop db:create db:schema:load db:seed'
  dc up -d web sidekiq
  wait_for_api
  cmd_seed
}

case "${1:-}" in
  pin) cmd_pin ;;
  up) cmd_up ;;
  seed) cmd_seed ;;
  status) cmd_status ;;
  reset) shift; cmd_reset "$@" ;;
  all)
    cmd_pin
    cmd_up
    # A brand-new pinned database has no schema yet: load it once.
    if [ "$STACK" = "pinned" ] && ! dc exec -T web bash -lc "cd /nearz && bundle exec rails runner 'Salon.count'" >/dev/null 2>&1; then
      cmd_reset --yes
    fi
    # A newer pin may bring migrations; a no-op when the schema is current.
    # Run through DatabaseTasks.migrate, not `rails db:migrate`, which would
    # dump db/schema.rb back into the pinned checkout and make it dirty.
    if [ "$STACK" = "pinned" ]; then
      dc exec -T -e DISABLE_SPRING=1 web bash -lc \
        "cd /nearz && bundle exec rails runner 'ActiveRecord::Tasks::DatabaseTasks.migrate'" 2>&1 | grep -E "migrat|==" || true
    fi
    cmd_seed
    cmd_status ;;
  *) sed -n '2,24p' "$0"; exit 1 ;;
esac

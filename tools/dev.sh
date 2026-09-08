#!/usr/bin/env bash
# Starts the whole Sift stack locally: Compose infrastructure (Postgres, RabbitMQ, Keycloak,
# SearXNG), the host-run operator, the server and the Vite dev server for the web UI. All
# connection defaults live in the committed `tools/dev.env` (development-only values).
#
# Usage:
#   tools/dev.sh                          # infrastructure + operator + server + web UI
#   tools/dev.sh --no-operator            # skip the operator (no kind cluster needed)
#   tools/dev.sh --no-web                 # backend only
#   tools/dev.sh --only web               # just the Vite dev server (repeatable: --only server)
#   tools/dev.sh --context kind-sift      # kind context for the operator
#   tools/dev.sh --stop                   # stop the Compose services and exit
#
# Ctrl-C stops every process this script started; the Compose services keep running so the
# next start is fast (`tools/dev.sh --stop` shuts them down).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${SIFT_DEV_ENV_FILE:-$ROOT/tools/dev.env}"
LOG_DIR="$ROOT/build/dev"

usage() { sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }
log() { printf '\033[1;36m[dev]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[dev]\033[0m %s\n' "$*" >&2; exit 1; }

with_operator=true
with_server=true
with_web=true
with_compose=true
only=()
stop_only=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --no-operator) with_operator=false; shift ;;
    --no-server) with_server=false; shift ;;
    --no-web) with_web=false; shift ;;
    --no-compose) with_compose=false; shift ;;
    --only) only+=("$2"); shift 2 ;;
    --context) export SIFT_DEV_CONTEXT="$2"; shift 2 ;;
    --stop) stop_only=true; shift ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ ${#only[@]} -gt 0 ]]; then
  with_operator=false; with_server=false; with_web=false; with_compose=false
  for component in "${only[@]}"; do
    case "$component" in
      compose|infra) with_compose=true ;;
      operator) with_operator=true ;;
      server) with_server=true ;;
      web) with_web=true ;;
      *) fail "--only expects one of: compose, operator, server, web" ;;
    esac
  done
fi

[[ -f "$ENV_FILE" ]] || fail "Missing $ENV_FILE"
# shellcheck disable=SC1090
source "$ENV_FILE"

cd "$ROOT"

if [[ "$stop_only" == true ]]; then
  log "Stopping Compose services"
  exec docker compose stop
fi

require() { command -v "$1" >/dev/null 2>&1 || fail "$2"; }

# --- preflight -------------------------------------------------------------------------------
require docker "docker is required (start Docker Desktop)"
docker info >/dev/null 2>&1 || fail "Docker is not running"
if [[ "$with_operator" == true || "$with_server" == true ]]; then
  [[ -x "$ROOT/kotlin" ]] || fail "Missing ./kotlin toolchain wrapper"
fi
if [[ "$with_web" == true ]]; then
  require pnpm "pnpm is required for the web UI (corepack enable pnpm); or pass --no-web"
fi

KUBECONFIG_FILE="$ROOT/.kubeconfig"
if [[ "$with_operator" == true ]]; then
  require kubectl "kubectl is required for the operator; or pass --no-operator"
  [[ -f "$KUBECONFIG_FILE" ]] || fail "Missing $KUBECONFIG_FILE; create a kind cluster first (see docs/system-components/local-kind-development.md) or pass --no-operator"
  kube=(kubectl --kubeconfig "$KUBECONFIG_FILE" --context "$SIFT_DEV_CONTEXT" --request-timeout=10s)
  "${kube[@]}" get nodes >/dev/null 2>&1 ||
    fail "Cannot reach the $SIFT_DEV_CONTEXT cluster; start it or pass --no-operator"
  # ADR 0009: local helpers never create clusters or install CRDs implicitly.
  "${kube[@]}" get crd codereviews.sift.org >/dev/null 2>&1 || fail "CodeReview CRD is missing. Install it once:
  kubectl --kubeconfig \"\$PWD/.kubeconfig\" --context $SIFT_DEV_CONTEXT apply -f k8s/manifests/crds/codereviews.sift.org-v1.yml"
  "${kube[@]}" get namespace "$SIFT_OPERATOR_NAMESPACE" >/dev/null 2>&1 || fail "Namespace $SIFT_OPERATOR_NAMESPACE is missing. Prepare the cluster once:
  python3 k8s/local/dev.py --context $SIFT_DEV_CONTEXT apply"

  # Every review Job mounts `sift-local-credentials` for the model access and the RabbitMQ password
  # (the per-repository Git token is separate and optional). Without it the Pod never starts and only
  # kubectl events reveal `secret "sift-local-credentials" not found`, so verify it up front.
  missing_keys=()
  secret_keys="$("${kube[@]}" get secret "$SIFT_DEV_CREDENTIALS_SECRET" -n "$SIFT_OPERATOR_NAMESPACE" \
    -o 'jsonpath={.data}' 2>/dev/null || true)"
  for key in model-api-key proxy-token rabbitmq-password; do
    grep -q "\"$key\"" <<<"$secret_keys" || missing_keys+=("$key")
  done
  if [[ ${#missing_keys[@]} -gt 0 ]]; then
    provision=(python3 k8s/local/dev.py --context "$SIFT_DEV_CONTEXT" secrets)
    if [[ -n "${OPENAI_API_KEY:-}" && -n "${SIFT_MODEL_PROXY_TOKEN:-}" ]]; then
      log "Provisioning Secret $SIFT_OPERATOR_NAMESPACE/$SIFT_DEV_CREDENTIALS_SECRET from the environment (missing: ${missing_keys[*]})"
    elif [[ -f "$ROOT/agents/code-review/resources/application-local.yaml" ]]; then
      log "Provisioning Secret $SIFT_OPERATOR_NAMESPACE/$SIFT_DEV_CREDENTIALS_SECRET from agents/code-review/resources/application-local.yaml (missing: ${missing_keys[*]})"
      provision+=(--from-agent-local)
    else
      fail "Secret $SIFT_OPERATOR_NAMESPACE/$SIFT_DEV_CREDENTIALS_SECRET is missing the keys: ${missing_keys[*]}.
  Review Jobs cannot start without it (the repository access token is unrelated and stays optional).
  Provide the model credentials once, either from the environment
    OPENAI_API_KEY=… SIFT_MODEL_PROXY_TOKEN=… python3 k8s/local/dev.py --context $SIFT_DEV_CONTEXT secrets
  or interactively (hidden prompts)
    python3 k8s/local/dev.py --context $SIFT_DEV_CONTEXT secrets
  then start again, or run without the operator: tools/dev.sh --no-operator"
    fi
    "${provision[@]}" || fail "Could not provision Secret $SIFT_OPERATOR_NAMESPACE/$SIFT_DEV_CREDENTIALS_SECRET; see the output above"
  fi
  log "Review credentials ready (Secret $SIFT_OPERATOR_NAMESPACE/$SIFT_DEV_CREDENTIALS_SECRET)"
fi

# A foreign process on the server port is the classic cause of "Sign-in unavailable" in the web UI:
# the Vite proxy happily forwards /api to whatever answers there, so the SPA gets a 404 for
# /api/v1/auth/config instead of the OIDC configuration.
port_owner() {
  command -v lsof >/dev/null 2>&1 || return 1
  lsof -nP -iTCP:"$1" -sTCP:LISTEN -Fpc 2>/dev/null |
    awk '/^p/ { pid = substr($0, 2) } /^c/ { print substr($0, 2) " (pid " pid ")"; exit }'
}

require_free_port() {
  local port="$1" what="$2" override="$3" owner
  owner="$(port_owner "$port")" || return 0
  [[ -n "$owner" ]] || return 0
  fail "Port $port is already in use by $owner, so $what cannot start.
  Stop that process or pick another port: $override=<port> tools/dev.sh"
}

if [[ "$with_server" == true ]]; then
  require_free_port "$SERVER_PORT" "the server" SERVER_PORT
fi
if [[ "$with_web" == true ]]; then
  require_free_port "$SIFT_DEV_WEB_PORT" "the Vite dev server" SIFT_DEV_WEB_PORT
  if [[ "$with_server" == false ]]; then
    # The SPA bootstraps from /api/v1/auth/config through the proxy; without a Sift server behind
    # it every screen fails with "Sign-in unavailable".
    api_url="${SIFT_API_URL:-http://localhost:$SERVER_PORT}"
    curl -fsS -o /dev/null "$api_url/api/v1/auth/config" ||
      log "WARNING: no Sift server answers $api_url/api/v1/auth/config - the web UI will show 'Sign-in unavailable' until one does (start it with tools/dev.sh --only server, or point SIFT_API_URL elsewhere)"
  fi
fi

mkdir -p "$LOG_DIR"

# --- infrastructure --------------------------------------------------------------------------
if [[ "$with_compose" == true ]]; then
  services=(postgres rabbitmq keycloak)
  [[ "$with_operator" == true ]] && services+=(searxng)
  log "Starting Compose services: ${services[*]}"
  docker compose up -d --wait "${services[@]}"
fi

# Keycloak imports `config/keycloak/sift-realm.json` on every start; verify the realm the server
# is configured against actually exists and announces the public PKCE client used by the web UI.
issuer="$SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI"
log "Waiting for the OIDC issuer $issuer"
discovery=""
for _ in $(seq 1 60); do
  if discovery="$(curl -fsS "$issuer/.well-known/openid-configuration" 2>/dev/null)"; then
    break
  fi
  discovery=""
  sleep 2
done
if [[ -z "$discovery" ]] || ! grep -q '"authorization_endpoint"' <<<"$discovery"; then
  fail "Realm '$SIFT_DEV_KEYCLOAK_REALM' is not available at $issuer.
  It is imported from config/keycloak/sift-realm.json on Keycloak startup; if you changed the file, recreate the container:
  docker compose rm -sf keycloak && docker compose up -d --wait keycloak"
fi
grep -q "\"issuer\":\"$issuer\"" <<<"${discovery//[[:space:]]/}" ||
  log "WARNING: the discovery document reports a different issuer than the server is configured with"
# The SPA exchanges the authorization code from the browser, so Keycloak must answer the token
# endpoint with CORS headers for the Vite origin. A container created before a realm change still
# runs the old client config, which shows up as "Sign-in failed" right after the redirect back.
web_origin="http://localhost:$SIFT_DEV_WEB_PORT"
if ! curl -sS -o /dev/null -D - -H "Origin: $web_origin" \
  -d "grant_type=authorization_code&client_id=$SIFT_SERVER_AUTH_CLIENT_ID&code=probe" \
  "$issuer/protocol/openid-connect/token" 2>/dev/null | grep -qi '^access-control-allow-origin:'; then
  log "WARNING: Keycloak does not allow $web_origin on the token endpoint - sign-in will fail after the redirect.
  The realm is imported only when the container is created; recreate it:
  docker compose rm -sf keycloak && docker compose up -d --wait keycloak"
fi
log "OIDC realm ready (client $SIFT_SERVER_AUTH_CLIENT_ID, users dev/dev and e2e/e2e)"

# --- child process handling ------------------------------------------------------------------
pids=()
names=()

# `./kotlin run` and `pnpm dev` spawn their own children (JVM, Vite); kill the whole subtree.
kill_tree() {
  local pid="$1" child
  for child in $(pgrep -P "$pid" 2>/dev/null); do
    kill_tree "$child"
  done
  kill "$pid" 2>/dev/null || true
}

cleanup() {
  trap - INT TERM EXIT
  log "Shutting down"
  for pid in ${pids[@]+"${pids[@]}"}; do
    kill_tree "$pid"
  done
  for pid in ${pids[@]+"${pids[@]}"}; do
    wait "$pid" 2>/dev/null || true
  done
  log "Compose services are still running; stop them with tools/dev.sh --stop"
}
trap 'cleanup; exit 130' INT TERM
trap cleanup EXIT

start() {
  local name="$1"; shift
  local log_file="$LOG_DIR/$name.log"
  log "Starting $name (log: ${log_file#"$ROOT/"})"
  # Prefix every line so the interleaved output stays readable, and keep the full log on disk.
  # stdin is /dev/null: background children must never read from the terminal (that suspends them
  # with SIGTTIN), and SIGHUP is ignored so a closing/detaching terminal does not kill them.
  ( trap '' HUP; "$@" </dev/null 2>&1 | tee "$log_file" | awk -v prefix="[$name] " '{ print prefix $0; fflush() }' ) &
  pids+=("$!")
  names+=("$name")
}

wait_for_http() {
  local name="$1" url="$2" attempts="${3:-90}"
  for _ in $(seq 1 "$attempts"); do
    curl -fsS -o /dev/null "$url" && return 0
    sleep 2
  done
  fail "$name did not become ready at $url; see $LOG_DIR"
}

# --- operator --------------------------------------------------------------------------------
if [[ "$with_operator" == true ]]; then
  # k8s/local/dev.py validates the context against the local kind cluster before exec'ing the
  # operator with the host kubeconfig identity.
  start operator env -u SPRING_CONFIG_ADDITIONAL_LOCATION -u SPRING_PROFILES_ACTIVE \
    python3 "$ROOT/k8s/local/dev.py" --context "$SIFT_DEV_CONTEXT" run
fi

# --- server ----------------------------------------------------------------------------------
if [[ "$with_server" == true ]]; then
  start server env KUBECONFIG="$KUBECONFIG_FILE" "$ROOT/kotlin" run --module server
  wait_for_http "server" "http://$SERVER_ADDRESS:$SERVER_PORT/actuator/health"
  log "Server ready on http://$SERVER_ADDRESS:$SERVER_PORT (OpenAPI: /v3/api-docs.yaml)"
fi

# --- web -------------------------------------------------------------------------------------
if [[ "$with_web" == true ]]; then
  if [[ ! -d "$ROOT/web/node_modules" ]]; then
    log "Installing web dependencies"
    (cd "$ROOT/web" && pnpm install --frozen-lockfile)
  fi
  start web env SIFT_API_URL="http://localhost:$SERVER_PORT" \
    pnpm --dir "$ROOT/web" --filter @sift/web dev --port "$SIFT_DEV_WEB_PORT" --strictPort
  log "Web UI on http://localhost:$SIFT_DEV_WEB_PORT (log in as dev/dev)"
fi

if [[ ${#pids[@]} -eq 0 ]]; then
  log "Nothing else to run"
  exit 0
fi

log "Running: ${names[*]} - press Ctrl-C to stop"
# `wait -n` needs bash 4.3; macOS ships 3.2, so poll instead. A component that dies (or is
# restarted by hand) must not take the others down - only Ctrl-C shuts the stack down.
alive=("${pids[@]}")
while :; do
  running=0
  for index in "${!pids[@]}"; do
    if kill -0 "${pids[$index]}" 2>/dev/null; then
      running=$((running + 1))
    elif [[ "${alive[$index]}" != exited ]]; then
      alive[index]=exited
      log "${names[$index]} exited - the other components keep running (log: $LOG_DIR/${names[$index]}.log)"
    fi
  done
  if [[ $running -eq 0 ]]; then
    log "All components exited"
    exit 1
  fi
  sleep 2
done

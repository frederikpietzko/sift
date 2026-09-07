#!/usr/bin/env bash
# Runs the gated e2e scenario (`SIFT_E2E=true ./kotlin check tests --module e2e`) with credentials
# sourced from the git-ignored `tools/e2e.env`. Copy `tools/e2e.env.example` to get started.
#
# Usage:
#   tools/e2e.sh                          # full e2e run
#   tools/e2e.sh --destroy                # also delete the kind cluster in teardown
#   tools/e2e.sh --pr owner/repo#42       # override the sample pull request
#   tools/e2e.sh --timeout 45m            # override the scenario budget
#   tools/e2e.sh -- --include-classes '*HappyPath*'   # extra args forwarded to ./kotlin check
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${SIFT_E2E_ENV_FILE:-$ROOT/tools/e2e.env}"

usage() { sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Create it from tools/e2e.env.example (it is git-ignored)." >&2
  exit 1
fi

# Export every assignment from the env file; command-line flags below override it.
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

extra=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --destroy) export SIFT_E2E_DESTROY=true; shift ;;
    --pr) export SIFT_E2E_PR="$2"; shift 2 ;;
    --timeout) export SIFT_E2E_TIMEOUT="$2"; shift 2 ;;
    --) shift; extra=("$@"); break ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

for required in OPENAI_API_KEY SIFT_MODEL_PROXY_TOKEN; do
  if [[ -z "${!required:-}" ]]; then
    echo "$required is not set; add it to $ENV_FILE" >&2
    exit 1
  fi
done

export SIFT_E2E=true
cd "$ROOT"
echo "Running e2e (PR=${SIFT_E2E_PR:-default}, timeout=${SIFT_E2E_TIMEOUT:-20m}, destroy=${SIFT_E2E_DESTROY:-false})"
# ${extra[@]+"${extra[@]}"} keeps bash 3.2 (macOS default) happy under `set -u` when no extra args were given.
exec ./kotlin check tests --module e2e ${extra[@]+"${extra[@]}"}

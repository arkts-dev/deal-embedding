#!/usr/bin/env bash
# Demos consume the framework's packaged AAR; library compilation stays framework-owned.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
exec "$ROOT/tools/build-demos.sh" "$@"

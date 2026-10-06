#!/usr/bin/env bash
set -euo pipefail

if [[ ${SSH_ORIGINAL_COMMAND:-} =~ ^deploy\ ([0-9a-f]{40})$ ]]; then
  exec /bin/bash /opt/torresoft/apps/hub/deploy/release.sh "${BASH_REMATCH[1]}"
fi
echo 'Esta chave aceita apenas: deploy <SHA completo da main>.' >&2
exit 1

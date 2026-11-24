#!/usr/bin/env bash
set -euo pipefail

# Валидация Kustomize-манифестов для base, dev и prod окружений.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if command -v kustomize >/dev/null 2>&1; then
  KUSTOMIZE_CMD=(kustomize build)
elif command -v kubectl >/dev/null 2>&1; then
  KUSTOMIZE_CMD=(kubectl kustomize)
else
  echo "Ошибка: не найден ни kustomize, ни kubectl" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

TARGETS=(
  "base"
  "overlays/dev"
  "overlays/prod"
)

for target in "${TARGETS[@]}"; do
  out_file="${TMP_DIR}/$(echo "${target}" | tr '/' '_').yaml"
  echo "==> Сборка ${target}"
  "${KUSTOMIZE_CMD[@]}" "${SCRIPT_DIR}/${target}" > "${out_file}"

  if command -v python3 >/dev/null 2>&1; then
    python3 -c '
import sys, yaml
with open(sys.argv[1], "r", encoding="utf-8") as f:
    docs = [d for d in yaml.safe_load_all(f) if d is not None]
assert len(docs) > 0, "Пустой вывод kustomize"
print(f"    YAML OK: {len(docs)} манифестов")
' "${out_file}"
  elif command -v python >/dev/null 2>&1; then
    python -c '
import sys, yaml
with open(sys.argv[1], "r", encoding="utf-8") as f:
    docs = [d for d in yaml.safe_load_all(f) if d is not None]
assert len(docs) > 0, "Пустой вывод kustomize"
print(f"    YAML OK: {len(docs)} манифестов")
' "${out_file}"
  fi

  if command -v kubeconform >/dev/null 2>&1; then
    echo "    Проверка kubeconform..."
    kubeconform -strict -summary "${out_file}"
  fi
done

echo "Все Kustomize-манифесты успешно провалидированы."

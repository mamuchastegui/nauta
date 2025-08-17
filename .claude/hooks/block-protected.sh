set -euo pipefail
read -r input
if echo "$input" | grep -E '"file_path".*"(.*migrations/|.*generated/)' -q; then
  echo "Editing protected paths is blocked by policy." >&2
  exit 2
fi
exit 0

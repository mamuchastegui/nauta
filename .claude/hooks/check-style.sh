set -euo pipefail
cd "$CLAUDE_PROJECT_DIR"
./gradlew -q ktlintCheck detekt || {
  echo "Style/static analysis failed. Please run './gradlew ktlintFormat detekt'." >&2
  exit 2
}

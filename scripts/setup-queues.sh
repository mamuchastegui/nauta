#!/bin/bash
# Setup SQS queues in LocalStack (idempotent)
# Usage: ./scripts/setup-queues.sh [dev|test] [--test]

set -euo pipefail

PROFILE=${1:-dev}
LOCALSTACK_ENDPOINT="http://localhost:4566"

echo "Setting up SQS queues for profile: $PROFILE"

# Basic checks
command -v aws >/dev/null 2>&1 || { echo "❌ aws CLI not found"; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "❌ curl not found"; exit 1; }

# Wait for LocalStack
echo "Waiting for LocalStack to be ready..."
until curl -fsS "$LOCALSTACK_ENDPOINT/_localstack/health" >/dev/null; do
  echo "LocalStack not ready, waiting..."
  sleep 2
done
echo "LocalStack is ready!"

# Dummy credentials for LocalStack
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1

# Queue names by profile
if [ "$PROFILE" = "test" ]; then
  QUEUE_NAME="ingest-queue-test"
  DLQ_NAME="ingest-dlq-test"
else
  QUEUE_NAME="ingest-queue"
  DLQ_NAME="ingest-dlq"
fi

# Function: extract endpoint from QueueUrl (strategy agnostic)
extract_endpoint() {
  local queue_url="$1"
  echo "$queue_url" | sed -E 's#(https?://[^/]+).*#\1#'
}

# Function: get queue URL if exists (no retry for initial check)
get_queue_url() {
  local name="$1"
  aws sqs get-queue-url \
    --endpoint-url "$LOCALSTACK_ENDPOINT" \
    --queue-name "$name" \
    --output text --query 'QueueUrl' 2>/dev/null || true
}

# Function: create queue if missing (idempotent)
create_queue_if_missing() {
  local name="$1"
  local url
  
  url="$(get_queue_url "$name")"
  if [ -n "$url" ]; then
    echo "Queue '$name' already exists: $url" >&2
    echo "$url"
    return 0
  fi
  
  echo "Creating queue '$name'..." >&2
  url="$(aws sqs create-queue \
    --endpoint-url "$LOCALSTACK_ENDPOINT" \
    --queue-name "$name" \
    --output text --query 'QueueUrl')"
  
  # Log endpoint strategy info
  local actual_endpoint
  actual_endpoint="$(extract_endpoint "$url")"
  echo "📡 Endpoint: $actual_endpoint" >&2
  
  echo "Created: $url" >&2
  echo "$url"
}

# 1) DLQ: create if missing
echo "Ensuring Dead Letter Queue: $DLQ_NAME"
DLQ_URL="$(create_queue_if_missing "$DLQ_NAME")"

echo "Getting DLQ ARN..."
# Use endpoint extracted from DLQ URL for consistency
DLQ_ENDPOINT="$(extract_endpoint "$DLQ_URL")"
DLQ_ARN="$(aws sqs get-queue-attributes \
  --endpoint-url "$DLQ_ENDPOINT" \
  --queue-url "$DLQ_URL" \
  --attribute-names QueueArn \
  --output text --query 'Attributes.QueueArn')"

# 2) Desired attributes for main queue
VISIBILITY_TIMEOUT="60"         # seconds
MESSAGE_RETENTION="1209600"     # 14 days
MAX_RECEIVE_COUNT="3"

REDRIVE_POLICY_JSON='{"deadLetterTargetArn":"'"$DLQ_ARN"'","maxReceiveCount":"'"$MAX_RECEIVE_COUNT"'"}'
ESCAPED_REDRIVE_POLICY_JSON="$(printf '%s' "$REDRIVE_POLICY_JSON" | sed 's/"/\\"/g')"

# Temporary file with attributes
TMP_ATTRS="$(mktemp)"
trap 'rm -f "$TMP_ATTRS"' EXIT
cat > "$TMP_ATTRS" <<EOF
{
  "VisibilityTimeout": "$VISIBILITY_TIMEOUT",
  "MessageRetentionPeriod": "$MESSAGE_RETENTION",
  "RedrivePolicy": "$ESCAPED_REDRIVE_POLICY_JSON"
}
EOF

# 3) Main queue: create if missing, if exists -> set-attributes
echo "Ensuring main queue: $QUEUE_NAME"
MAIN_QUEUE_URL="$(get_queue_url "$QUEUE_NAME")"

if [ -z "$MAIN_QUEUE_URL" ]; then
  echo "Does not exist, creating with attributes..."
  MAIN_QUEUE_URL="$(aws sqs create-queue \
    --endpoint-url "$LOCALSTACK_ENDPOINT" \
    --queue-name "$QUEUE_NAME" \
    --attributes file://"$TMP_ATTRS" \
    --output text --query 'QueueUrl')"
else
  echo "Already exists: $MAIN_QUEUE_URL"
  echo "Updating attributes (set-queue-attributes)..."
  
  # Use extracted endpoint for consistency
  MAIN_ENDPOINT="$(extract_endpoint "$MAIN_QUEUE_URL")"
  aws sqs set-queue-attributes \
    --endpoint-url "$MAIN_ENDPOINT" \
    --queue-url "$MAIN_QUEUE_URL" \
    --attributes file://"$TMP_ATTRS"
fi

# Verify final strategy
echo "📊 Endpoint strategy verification:"
echo "   DLQ: $(extract_endpoint "$DLQ_URL")"
echo "   Main: $(extract_endpoint "$MAIN_QUEUE_URL")"

echo "✅ Successfully ensured queues:"
echo "   Main Queue: $MAIN_QUEUE_URL"
echo "   DLQ       : $DLQ_URL"
echo

# Exports útiles (Spring relaxed binding)
echo "Environment variables (copy & paste):"
echo "export SQS_INGEST_QUEUE_URL=\"$MAIN_QUEUE_URL\""
echo "export SQS_INGEST_DLQ_URL=\"$DLQ_URL\""
echo "export APP_SQS_INGEST_QUEUE_URL=\"$MAIN_QUEUE_URL\""
echo "export APP_SQS_INGEST_DLQ_URL=\"$DLQ_URL\""

# Save to file for quick loading
ENV_OUT=".env.localstack"
cat > "$ENV_OUT" <<ENV
SQS_ENDPOINT=$LOCALSTACK_ENDPOINT
SQS_INGEST_QUEUE_URL=$MAIN_QUEUE_URL
SQS_INGEST_DLQ_URL=$DLQ_URL
APP_SQS_INGEST_QUEUE_URL=$MAIN_QUEUE_URL
APP_SQS_INGEST_DLQ_URL=$DLQ_URL
ENV
echo
echo "👉 Also saved to $ENV_OUT (you can: set -a; source $ENV_OUT; set +a)"

# Optional test
if [ "${2:-}" = "--test" ]; then
  echo
  echo "Sending test message..."
  TEST_ENDPOINT="$(extract_endpoint "$MAIN_QUEUE_URL")"
  aws sqs send-message \
    --endpoint-url "$TEST_ENDPOINT" \
    --queue-url "$MAIN_QUEUE_URL" \
    --message-body "{\"test\":\"message\",\"timestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
  echo "✅ Test message sent successfully!"
  
  # Verify message was received
  echo "Verifying reception..."
  MESSAGES="$(aws sqs receive-message \
    --endpoint-url "$TEST_ENDPOINT" \
    --queue-url "$MAIN_QUEUE_URL" \
    --max-number-of-messages 1 \
    --wait-time-seconds 2 \
    --output json 2>/dev/null || echo '{}')"
  
  if echo "$MESSAGES" | grep -q '"Messages"'; then
    echo "✅ Message received successfully!"
  else
    echo "⚠️  No messages received (might be processed already)"
  fi
fi

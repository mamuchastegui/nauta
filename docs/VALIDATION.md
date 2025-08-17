# 🔧 VALIDATION COMMANDS

## Local Environment (LocalStack + Docker Postgres)

### 1. Full Setup

```bash
make setup-dev
# Should display: ✅ Development environment ready!
# And create .env.localstack with SQS URLs
```

### 2. Validate Services

```bash
make validate-local
# Checks docker, LocalStack health, Postgres
```

### 3. Start Application

```bash
make run-local
# Or directly: make run
# Should connect to nauta_dev DB and LocalStack SQS
```

### 4. Manual SQS Test

```bash
# With queues active, send a test message:
aws sqs send-message \
  --endpoint-url http://localhost:4566 \
  --queue-url http://localhost:4566/000000000000/ingest-queue \
  --message-body '{"test":"hello"}' \
  --region us-east-1
```

## Dev-AWS Environment (RDS + AWS SQS)

### 1. AWS SSO Login

```bash
aws sso login --profile <your-profile>
# Or: aws configure list  # to verify credentials
```

### 2. Start with RDS

```bash
make run-dev-aws
# Uses dev-aws profile, connects to RDS + real SQS
```

## Cleanup/Reset

### 1. Clean Docker

```bash
make clean-env
# = docker compose down -v + docker system prune -f
```

### 2. Re-create SQS Queues

```bash
make setup-queues
# Re-runs ./scripts/setup-queues.sh dev
```

### 3. Test with Message

```bash
./scripts/setup-queues.sh dev --test
# Creates queues + sends test message
```

## Troubleshooting

### Error: JWT\_SECRET

```bash
grep JWT_SECRET .env
# Should display: JWT_SECRET=z8Q7qh5vE2mY9!B4r@cF1wT6^kLp3X0u
```

### Error: database "nauta" does not exist

```bash
docker compose exec postgres psql -U nauta -l
# Should display nauta_dev database
```

### Error: LocalStack SQS

```bash
curl http://localhost:4566/_localstack/health
aws sqs list-queues --endpoint-url http://localhost:4566
```

### Application Logs

```bash
./gradlew bootRun --info
# Or with debug: SPRING_PROFILES_ACTIVE=local ./gradlew bootRun --debug
```

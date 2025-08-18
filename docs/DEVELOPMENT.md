# Development Commands

This document provides a comprehensive reference for all development commands and workflows.

## Quick Reference

```bash
# Environment Setup
make setup-dev          # Start PostgreSQL + LocalStack, create SQS queues
make validate-local     # Validate all services are healthy
make check-env          # Check environment variables

# Application
make run-local          # Run with local profile (PostgreSQL + LocalStack)
make run-dev-aws        # Run with dev-aws profile (RDS + AWS SQS)
make build              # Build the application
make test               # Run tests

# Environment Management
make clean-env          # Clean docker environment completely
make clean-queues       # Delete all SQS queues in LocalStack
make reset-local        # Complete reset: clean + setup

# Docker Management
make docker-up          # Start all services
make docker-down        # Stop all services

# Code Quality & Testing
make test               # Run tests
make coverage           # Generate test coverage report
make check-deps         # Check for outdated dependencies
./gradlew ktlintCheck   # Kotlin linting
./gradlew detekt        # Static analysis
```

## Environment Setup Commands

### Initial Setup

```bash
# Clone and setup (first time)
git clone <repository-url>
cd nauta-takehome
cp .env.example .env
# Edit .env with your JWT_SECRET and other values
make setup-dev
```

### Setup Development Environment

```bash
make setup-dev
```

**What it does:**
- Starts PostgreSQL container with `nauta_dev` database
- Starts LocalStack container with SQS service
- Waits for services to be ready (10 seconds)
- Creates SQS queues (`ingest-queue` and `ingest-dlq`) with proper configuration
- Generates `.env.localstack` with queue URLs

**Expected output:**
```
Setting up development environment...
docker compose up -d postgres localstack
Waiting for services to be ready...
Setting up SQS queues for profile: dev
✅ Development environment ready!
```

### Setup Test Environment

```bash
make setup-test
```

**What it does:**
- Starts PostgreSQL test container on port 5433
- Starts LocalStack container
- Creates test-specific SQS queues (`ingest-queue-test`, `ingest-dlq-test`)

## Application Commands

### Run Locally (Default)

```bash
make run-local
# or simply
make run
```

**What it does:**
- Sets `SPRING_PROFILES_ACTIVE=local`
- Loads `.env` and `.env.localstack` files
- Validates JWT_SECRET exists
- Shows loaded environment variables (masked)
- Starts Spring Boot application with Gradle

**Expected output:**
```
🚀 Starting with local profile...
🔑 JWT_SECRET: z8Q7qh5v...
📦 SQS_INGEST_QUEUE_URL: http://localhost.localstack.cloud:4566/queue/us-east-1/000000000000/ingest-queue
[Gradle daemon starts]
[Spring Boot application starts]
```

### Run Against AWS (Dev Environment)

```bash
make run-dev-aws
```

**What it does:**
- Sets `SPRING_PROFILES_ACTIVE=dev-aws`
- Validates AWS credentials are configured
- Loads `.env` file
- Shows AWS identity being used
- Connects to RDS PostgreSQL and AWS SQS

**Prerequisites:**
```bash
# Configure AWS SSO
aws sso login --profile your-profile

# Or use AWS credentials
aws configure
```

**Expected output:**
```
🚀 Starting with dev-aws profile...
🔑 JWT_SECRET: z8Q7qh5v...
🌍 AWS Identity: arn:aws:sts::123456789012:assumed-role/DeveloperRole/user@example.com
[Spring Boot application starts]
```

## Build and Test Commands

### Build Application

```bash
make build
```

**What it does:**
- Compiles Kotlin source code
- Runs static analysis (Detekt)
- Runs linting (KtLint)
- Packages JAR file

### Run Tests

```bash
make test
```

**What it does:**
- Runs unit tests
- Runs integration tests with TestContainers
- Generates test reports

### Clean Build Artifacts

```bash
make clean
```

**What it does:**
- Removes `build/` directory
- Cleans Gradle cache for this project

## Environment Management Commands

### Validate Local Environment

```bash
make validate-local
```

**What it checks:**
- Docker containers are running and healthy
- LocalStack SQS service is available
- PostgreSQL accepts connections
- SQS queues exist and are accessible

**Expected output:**
```
🔍 Validating local setup...
=== Docker Services ===
[Container status table]

=== LocalStack Health ===
✅ LocalStack SQS ready

=== Postgres Health ===
✅ Postgres ready

=== SQS Queues ===
✅ SQS queues found

✅ Local environment validation complete
```

### Check Environment Variables

```bash
make check-env
```

**What it shows:**
- `.env` file status and variables (sensitive values redacted)
- `.env.localstack` file status and SQS URLs
- Missing files or variables

**Expected output:**
```
=== Environment Check ===
✅ .env found
JWT_SECRET=<REDACTED>
LOCAL_DB_HOST=<REDACTED>
[other variables redacted]

✅ .env.localstack found
SQS_INGEST_QUEUE_URL=http://localhost.localstack.cloud:4566/queue/us-east-1/000000000000/ingest-queue
[other SQS URLs]
```

### Clean Environment

```bash
make clean-env
```

**What it does:**
- Stops and removes all Docker containers
- Removes all Docker volumes (data loss!)
- Removes `.env.localstack` file
- Prunes Docker system

**⚠️ Warning:** This will delete all database data in Docker containers.

### Clean SQS Queues

```bash
make clean-queues
```

**What it does:**
- Lists all SQS queues in LocalStack
- Deletes each queue using the correct endpoint
- Removes `.env.localstack` file

### Complete Reset

```bash
make reset-local
```

**What it does:**
- Runs `clean-env` (removes everything)
- Runs `setup-dev` (recreates everything)
- Equivalent to: `make clean-env && make setup-dev`

## Docker Management Commands

### Start All Services

```bash
make docker-up
```

Starts PostgreSQL, PostgreSQL-test, and LocalStack containers.

### Stop All Services

```bash
make docker-down
```

Stops all containers but preserves volumes.

### Check Container Status

```bash
docker compose ps
```

Shows status of all containers defined in `docker-compose.yml`.

### View Container Logs

```bash
# All services
docker compose logs

# Specific service
docker compose logs postgres
docker compose logs localstack

# Follow logs
docker compose logs -f localstack
```

## SQS Queue Management

### Create Development Queues

```bash
make setup-queues
# or directly
./scripts/setup-queues.sh dev
```

### Create Test Queues

```bash
make setup-queues-test
# or directly
./scripts/setup-queues.sh test
```

### Test SQS Message Flow

```bash
./scripts/setup-queues.sh dev --test
```

**What it does:**
- Creates/updates queues
- Sends a test message to the main queue
- Attempts to receive the message
- Shows success/failure status

### Manual SQS Operations

```bash
# List queues
aws sqs list-queues --endpoint-url http://localhost:4566 --region us-east-1

# Send message
aws sqs send-message \
  --endpoint-url http://localhost:4566 \
  --queue-url "QUEUE_URL" \
  --message-body '{"test": "message"}' \
  --region us-east-1

# Receive messages
aws sqs receive-message \
  --endpoint-url http://localhost:4566 \
  --queue-url "QUEUE_URL" \
  --region us-east-1
```

## Code Quality Commands

### Check for Outdated Dependencies

```bash
make check-deps
```

Uses Gradle's dependency update plugin to show available updates.

### Kotlin Linting

```bash
# Check style
./gradlew ktlintCheck

# Auto-fix style issues
./gradlew ktlintFormat
```

### Static Analysis

```bash
# Run Detekt
./gradlew detekt

# View HTML report
open build/reports/detekt/detekt.html
```

### Generate Documentation

```bash
# Generate KDoc documentation
./gradlew dokkaHtml

# View generated docs
open build/dokka/html/index.html
```

## Database Commands

### Connect to Local Database

```bash
# Using Docker
docker compose exec postgres psql -U nauta -d nauta_dev

# Using local psql (if installed)
psql -h localhost -U nauta -d nauta_dev
```

### Database Migrations

```bash
# Migrations run automatically on startup when flyway.enabled=true
# To run manually:
./gradlew flywayMigrate

# Check migration status
./gradlew flywayInfo

# Validate migrations
./gradlew flywayValidate
```

### Reset Database

```bash
# Warning: This deletes all data
make clean-env && make setup-dev
```

## Debugging and Profiling

### Debug Mode

```bash
# Enable Spring Boot debug logging
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun --debug-jvm

# Enable SQL logging
SPRING_PROFILES_ACTIVE=local LOGGING_LEVEL_SQL=DEBUG ./gradlew bootRun
```

### JVM Profiling

```bash
# Enable JFR (Java Flight Recorder)
JAVA_OPTS="-XX:+FlightRecorder -XX:StartFlightRecording=duration=60s,filename=profile.jfr" \
  ./gradlew bootRun

# Enable JMX
JAVA_OPTS="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9999 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false" \
  ./gradlew bootRun
```

## Continuous Integration

### Pre-commit Checks

```bash
# Run all quality checks before committing
./gradlew check ktlintCheck detekt test
```

### CI/CD Pipeline Commands

```bash
# Build for production
./gradlew clean build -Pprod

# Build Docker image (if Dockerfile exists)
docker build -t nauta-takehome .

# Run integration tests
./gradlew integrationTest
```

## Troubleshooting Commands

### Check System Resources

```bash
# Check Docker resource usage
docker stats

# Check disk space
df -h

# Check LocalStack logs
docker compose logs localstack | tail -50
```

### Network Debugging

```bash
# Test LocalStack connectivity
curl http://localhost:4566/_localstack/health

# Test database connectivity
nc -zv localhost 5432

# Test application health
curl http://localhost:8080/actuator/health
```

### Performance Monitoring

```bash
# Application metrics
curl http://localhost:8080/actuator/metrics

# JVM metrics
curl http://localhost:8080/actuator/metrics/jvm.memory.used

# HTTP request metrics
curl http://localhost:8080/actuator/metrics/http.server.requests
```

For more detailed troubleshooting, see [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
# Troubleshooting Guide

This document provides solutions to common issues you might encounter while developing with the Nauta Takehome application.

## Quick Diagnostics

### Health Check Commands

```bash
# Validate complete local setup
make validate-local

# Check environment variables
make check-env

# Reset if something is broken
make reset-local
```

### System Status

```bash
# Check Docker containers
docker compose ps

# Check LocalStack health
curl http://localhost:4566/_localstack/health

# Check application health
curl http://localhost:8080/actuator/health
```

## Common Issues

### 1. SQS NonExistentQueue Error

**Problem**: AWS CLI commands fail with "NonExistentQueue" even though queues exist.

**Symptoms:**
```
An error occurred (AWS.SimpleQueueService.NonExistentQueue) when calling the GetQueueAttributes operation: The specified queue does not exist.
```

**Root Cause**: Mixed endpoint strategies between LocalStack's domain strategy (`localhost.localstack.cloud`) and path strategy (`localhost:4566`).

**Solution:**
```bash
# Clean and recreate queues
make clean-queues && make setup-dev

# Verify queue URLs are consistent
aws sqs list-queues --endpoint-url http://localhost:4566 --region us-east-1
```

**Prevention**: The setup scripts are now endpoint-agnostic and extract the correct endpoint from each queue URL.

### 2. JWT_SECRET Not Found

**Problem**: Application fails to start with "Could not resolve placeholder 'JWT_SECRET'".

**Symptoms:**
```
Caused by: java.lang.IllegalArgumentException: Could not resolve placeholder 'JWT_SECRET' in value "${JWT_SECRET}"
```

**Solutions:**

#### Check .env file exists
```bash
ls -la .env
# Should exist and be readable
```

#### Check JWT_SECRET is set
```bash
grep JWT_SECRET .env
# Should show: JWT_SECRET=your-secret-key
```

#### Fix missing or invalid .env
```bash
# Copy template if missing
cp .env.example .env

# Generate a secure JWT secret
openssl rand -base64 32

# Edit .env and set JWT_SECRET to the generated value
```

#### Verify environment loading
```bash
make check-env
# Should show JWT_SECRET=<REDACTED>
```

### 3. Database Connection Failed

**Problem**: Application cannot connect to PostgreSQL database.

**Symptoms:**
```
org.postgresql.util.PSQLException: Connection to localhost:5432 refused
```

**Solutions:**

#### Check PostgreSQL container
```bash
docker compose ps postgres
# Should show "Up" and "healthy"
```

#### Check database configuration
```bash
grep LOCAL_DB_ .env
# Should show:
# LOCAL_DB_NAME=nauta_dev  (not 'nauta')
# LOCAL_DB_USERNAME=nauta
# LOCAL_DB_PASSWORD=nauta123
```

#### Test database connection
```bash
docker compose exec postgres pg_isready -U nauta -d nauta_dev
# Should show: accepting connections
```

#### Connect manually to verify
```bash
docker compose exec postgres psql -U nauta -d nauta_dev
# Should connect successfully
```

#### Fix database issues
```bash
# Restart PostgreSQL container
docker compose restart postgres

# Or reset completely
make clean-env && make setup-dev
```

### 4. LocalStack SQS Issues

**Problem**: SQS operations fail or LocalStack is not responding.

**Symptoms:**
```
Could not connect to the endpoint URL: "http://localhost:4566"
```

**Solutions:**

#### Check LocalStack health
```bash
curl http://localhost:4566/_localstack/health
# Should return JSON with "sqs": "available"
```

#### Check LocalStack logs
```bash
docker compose logs localstack | tail -20
# Look for errors or startup issues
```

#### Verify SQS service is enabled
```bash
docker compose logs localstack | grep -i sqs
# Should show SQS service starting
```

#### Reset LocalStack
```bash
# Remove LocalStack data and restart
docker compose down
docker volume rm nauta-takehome_localstack_data
make setup-dev
```

#### Test SQS manually
```bash
# List queues (should work after setup)
aws sqs list-queues --endpoint-url http://localhost:4566 --region us-east-1

# Create test queue
aws sqs create-queue --endpoint-url http://localhost:4566 --queue-name test --region us-east-1
```

### 5. Environment Variable Loading Issues

**Problem**: Environment variables are not loaded correctly when running the application.

**Symptoms:**
- Application starts but uses wrong database
- SQS endpoints are not set correctly
- JWT secret not found despite being in .env

**Solutions:**

#### Verify make targets
```bash
# Use the correct make target
make run-local  # For local development
make run-dev-aws  # For AWS development

# Avoid using raw gradlew
# ./gradlew bootRun  # May not load .env files
```

#### Check shell environment
```bash
# Test environment loading
set -a
source .env
source .env.localstack
set +a
echo $JWT_SECRET
echo $SQS_INGEST_QUEUE_URL
```

#### Validate profile activation
```bash
# Check that SPRING_PROFILES_ACTIVE is set
make run-local 2>&1 | grep SPRING_PROFILES_ACTIVE
# Should show: export SPRING_PROFILES_ACTIVE=local
```

### 6. AWS Credentials Issues (dev-aws profile)

**Problem**: Application fails to connect to AWS services.

**Symptoms:**
```
Unable to load credentials from any of the providers in the chain
```

**Solutions:**

#### Check AWS credentials
```bash
aws sts get-caller-identity
# Should return your AWS account info
```

#### Configure AWS SSO
```bash
aws sso login --profile your-dev-profile
aws sso login  # If using default profile
```

#### Test AWS connectivity
```bash
# Test SQS access
aws sqs list-queues --region us-east-1

# Test RDS connectivity (if accessible)
aws rds describe-db-instances --region us-east-2
```

#### Verify profile in .env
```bash
grep AWS_ .env
# Check that AWS region and other settings are correct
```

### 7. Port Already in Use

**Problem**: Cannot start services because ports are already in use.

**Symptoms:**
```
Error starting userland proxy: listen tcp4 0.0.0.0:5432: bind: address already in use
```

**Solutions:**

#### Check what's using the port
```bash
# Check port 5432 (PostgreSQL)
lsof -i :5432

# Check port 4566 (LocalStack)
lsof -i :4566

# Check port 8080 (Application)
lsof -i :8080
```

#### Stop conflicting services
```bash
# Stop local PostgreSQL
brew services stop postgresql
# or
sudo systemctl stop postgresql

# Kill processes using ports
sudo lsof -ti:5432 | xargs sudo kill -9
```

#### Use different ports
```bash
# Edit docker-compose.yml to use different host ports
# Change "5432:5432" to "5433:5432" for PostgreSQL
# Change "4566:4566" to "4567:4566" for LocalStack
```

### 8. Memory or Resource Issues

**Problem**: Containers fail to start due to insufficient resources.

**Symptoms:**
```
docker: Error response from daemon: failed to create task
```

**Solutions:**

#### Check Docker resources
```bash
docker stats
docker system df
```

#### Clean up Docker
```bash
docker system prune -f
docker volume prune -f
```

#### Increase Docker memory (Docker Desktop)
- Open Docker Desktop → Settings → Resources
- Increase Memory to at least 4GB
- Increase Disk space if needed

### 9. SSL/TLS Issues

**Problem**: SSL certificate errors when connecting to AWS services.

**Symptoms:**
```
SSL certificate problem: certificate verify failed
```

**Solutions:**

#### Update certificates
```bash
# macOS
brew install ca-certificates

# Ubuntu/Debian
sudo apt-get update && sudo apt-get install ca-certificates
```

#### Check system time
```bash
date
# Ensure system time is correct
```

#### Bypass SSL for LocalStack (development only)
```bash
export AWS_ENDPOINT_URL_SQS=http://localhost:4566
export AWS_USE_SSL=false
```

## Environment-Specific Issues

### LocalStack Environment

#### Queue URLs Format Issues
```bash
# Problem: Inconsistent queue URL formats
# Solution: Use endpoint-agnostic approach
ENDPOINT=$(echo "$QUEUE_URL" | sed -E 's#(https?://[^/]+).*#\1#')
aws sqs get-queue-attributes --endpoint-url "$ENDPOINT" --queue-url "$QUEUE_URL"
```

#### LocalStack Container Memory
```bash
# If LocalStack is slow or unresponsive
docker compose restart localstack
docker compose logs localstack
```

### AWS Environment

#### RDS Connection Issues
```bash
# Check security groups allow connections
# Verify RDS endpoint is correct in .env
# Test connectivity from development machine
telnet your-rds-endpoint.region.rds.amazonaws.com 5432
```

#### SQS Permission Issues
```bash
# Verify IAM permissions for SQS operations
aws sqs get-queue-attributes --queue-url "your-sqs-url"
aws iam get-user
aws iam list-attached-user-policies --user-name your-username
```

## Performance Issues

### Slow Application Startup

**Causes and Solutions:**

#### Large Docker images
```bash
# Check image sizes
docker images | grep nauta

# Clean unused images
docker image prune -f
```

#### Database migrations
```bash
# Check migration status
./gradlew flywayInfo

# Optimize migrations if too many
```

#### JVM startup time
```bash
# Use JVM warming flags
export JAVA_OPTS="-XX:+UseG1GC -XX:+UseStringDeduplication"
```

### Slow SQS Operations

#### Network latency to LocalStack
```bash
# Test latency
time curl http://localhost:4566/_localstack/health
```

#### Too many retry attempts
```bash
# Check application logs for retry patterns
grep -i retry logs/application.log
```

## Advanced Debugging

### Enable Debug Logging

#### Application Debug Mode
```bash
# Enable Spring Boot debug logging
LOGGING_LEVEL_ORG_SPRINGFRAMEWORK=DEBUG make run-local

# Enable SQL logging
LOGGING_LEVEL_ORG_HIBERNATE_SQL=DEBUG make run-local

# Enable SQS debug logging
LOGGING_LEVEL_SOFTWARE_AMAZON_AWSSDK=DEBUG make run-local
```

#### LocalStack Debug Mode
```bash
# Enable LocalStack debug logging (edit docker-compose.yml)
environment:
  DEBUG: 1
  LS_LOG: trace
```

### Profiling and Monitoring

#### JVM Profiling
```bash
# Enable JFR
JAVA_OPTS="-XX:+FlightRecorder -XX:StartFlightRecording=duration=60s,filename=profile.jfr" \
  make run-local

# Analyze with VisualVM or Mission Control
```

#### Database Performance
```bash
# Enable PostgreSQL query logging
docker compose exec postgres psql -U nauta -d nauta_dev -c "ALTER SYSTEM SET log_statement = 'all';"
docker compose restart postgres
```

#### Network Monitoring
```bash
# Monitor Docker network traffic
docker exec -it nauta-takehome-postgres-1 tcpdump -i eth0
```

## Getting Help

### Collect Diagnostic Information

```bash
#!/bin/bash
# Save this as debug-info.sh and run it
echo "=== System Information ==="
uname -a
docker --version
docker compose version

echo "=== Docker Containers ==="
docker compose ps

echo "=== Environment Variables ==="
make check-env

echo "=== LocalStack Health ==="
curl -s http://localhost:4566/_localstack/health | jq .

echo "=== SQS Queues ==="
aws sqs list-queues --endpoint-url http://localhost:4566 --region us-east-1

echo "=== Application Health ==="
curl -s http://localhost:8080/actuator/health 2>/dev/null | jq . || echo "Application not responding"

echo "=== Recent Logs ==="
docker compose logs --tail=20
```

### Support Channels

1. **Documentation**: Check other docs in this directory
2. **Issues**: Create GitHub issues with diagnostic output
3. **Logs**: Always include relevant log files
4. **Environment**: Specify your OS, Docker version, and environment

### Useful Commands for Support

```bash
# Generate full diagnostic report
./debug-info.sh > diagnostic-report.txt

# Export Docker logs
docker compose logs > docker-logs.txt

# Export environment (safe - no secrets)
make check-env > environment-status.txt

# Test basic connectivity
curl -v http://localhost:4566/_localstack/health > localstack-test.txt 2>&1
curl -v http://localhost:8080/actuator/health > app-health-test.txt 2>&1
```
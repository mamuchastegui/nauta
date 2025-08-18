.PHONY: help setup setup-dev setup-test clean docker-up docker-down build run test

help: ## Show this help message
	@echo 'Usage: make [target]'
	@echo ''
	@echo 'Available targets:'
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "  %-15s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

setup: setup-dev ## Setup development environment (alias for setup-dev)

setup-dev: ## Setup development environment with Docker and SQS queues
	@echo "Setting up development environment..."
	docker compose up -d postgres localstack
	@echo "Waiting for services to be ready..."
	sleep 10
	./scripts/setup-queues.sh dev
	@echo "✅ Development environment ready!"

setup-test: ## Setup test environment with Docker and SQS queues
	@echo "Setting up test environment..."
	docker compose up -d postgres-test localstack
	@echo "Waiting for services to be ready..."
	sleep 10
	./scripts/setup-queues.sh test
	@echo "✅ Test environment ready!"

setup-queues: ## Create SQS queues in LocalStack (dev profile)
	./scripts/setup-queues.sh dev

setup-queues-test: ## Create SQS queues in LocalStack (test profile)
	./scripts/setup-queues.sh test

docker-up: ## Start all Docker services
	docker compose up -d

docker-down: ## Stop all Docker services
	docker compose down

docker-clean: ## Stop and remove all Docker services and volumes
	docker compose down -v --remove-orphans
	docker system prune -f

build: ## Build the application
	./gradlew build

run: run-local ## Run the application (alias for run-local)

run-local: ## Run with local profile (docker postgres + localstack SQS)
	@echo "🚀 Starting with local profile..."
	@if [ ! -f .env ]; then echo "❌ .env file not found"; exit 1; fi
	@if ! grep -q "JWT_SECRET" .env; then echo "❌ JWT_SECRET not found in .env"; exit 1; fi
	@set -a; \
	 export SPRING_PROFILES_ACTIVE=local; \
	 unset AWS_PROFILE AWS_DEFAULT_PROFILE AWS_SDK_LOAD_CONFIG AWS_SESSION_TOKEN; \
	 export AWS_ACCESS_KEY_ID=test; \
     export AWS_SECRET_ACCESS_KEY=test; \
     export AWS_REGION=us-east-1; \
	 [ -f .env ] && . .env; \
	 [ -f .env.localstack ] && . .env.localstack; \
	 set +a; \
	 echo "🔑 JWT_SECRET: $${JWT_SECRET:0:8}..."; \
	 echo "🌐 Using LocalStack @ $$SQS_ENDPOINT"; \
	 echo "📦 SQS_INGEST_QUEUE_URL: $$SQS_INGEST_QUEUE_URL"; \
	 ./gradlew --no-daemon bootRun \
       -Dspring-boot.run.profiles=local \
       -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"

run-dev-aws: ## Run with dev-aws profile (RDS + AWS SQS)
	@echo "🚀 Starting with dev-aws profile..."
	@if [ ! -f .env ]; then echo "❌ .env file not found"; exit 1; fi
	@if ! grep -q "JWT_SECRET" .env; then echo "❌ JWT_SECRET not found in .env"; exit 1; fi
	@if ! aws sts get-caller-identity >/dev/null 2>&1; then echo "❌ AWS credentials not configured. Run: aws sso login --profile <profile>"; exit 1; fi
	@set -a; \
	 export SPRING_PROFILES_ACTIVE=dev-aws; \
	 [ -f .env ] && . .env; \
	 set +a; \
	 echo "🔑 JWT_SECRET: $${JWT_SECRET:0:8}..."; \
	 echo "🌍 AWS Identity: $$(aws sts get-caller-identity --query 'Arn' --output text)"; \
	 ./gradlew --no-daemon bootRun

test: ## Run tests
	./gradlew test

coverage: ## Generate test coverage report
	@echo "🧪 Generating test coverage report..."
	./gradlew test jacocoTestReport
	@echo "📊 Coverage report generated:"
	@echo "  HTML: file://$(PWD)/build/reports/jacoco/test/html/index.html"
	@echo "  XML:  $(PWD)/build/reports/jacoco/test/jacocoTestReport.xml"
	@if command -v open >/dev/null 2>&1; then \
		echo "🌐 Opening report in browser..."; \
		open build/reports/jacoco/test/html/index.html; \
	fi

clean: ## Clean build artifacts
	./gradlew clean

check-deps: ## Check for outdated dependencies
	./gradlew dependencyUpdates

check-env: ## Check environment variables
	@echo "=== Environment Check ==="
	@if [ -f .env ]; then \
		echo "✅ .env found"; \
		grep -E "^(JWT_SECRET|LOCAL_DB_|DATABASE_)" .env | sed 's/=.*/=<REDACTED>/' || true; \
	else \
		echo "❌ .env not found"; \
	fi
	@echo
	@if [ -f .env.localstack ]; then \
		echo "✅ .env.localstack found"; \
		cat .env.localstack; \
	else \
		echo "❌ .env.localstack not found (run make setup-dev)"; \
	fi

validate-local: ## Validate local environment is working (requires setup-dev first)
	@echo "🔍 Validating local setup..."
	@echo "=== Docker Services ==="
	@docker compose ps
	@echo
	@echo "=== LocalStack Health ==="
	@if curl -fsS http://localhost:4566/_localstack/health | jq '.services.sqs' 2>/dev/null; then \
		echo "✅ LocalStack SQS ready"; \
	else \
		echo "❌ LocalStack not ready"; exit 1; \
	fi
	@echo
	@echo "=== Postgres Health ==="
	@if docker compose exec postgres pg_isready -U nauta -d nauta_dev >/dev/null; then \
		echo "✅ Postgres ready"; \
	else \
		echo "❌ Postgres not ready"; exit 1; \
	fi
	@echo
	@echo "=== SQS Queues ==="
	@if aws sqs list-queues --endpoint-url http://localhost:4566 --region us-east-1 --output table 2>/dev/null | grep -E "(ingest-queue|ingest-dlq)"; then \
		echo "✅ SQS queues found"; \
	else \
		echo "❌ SQS queues not found. Run: make setup-queues"; \
	fi
	@echo
	@echo "✅ Local environment validation complete"

clean-env: ## Clean docker environment completely
	@echo "🧹 Cleaning docker environment..."
	docker compose down -v --remove-orphans
	@echo "Removing LocalStack cache..."
	@rm -f .env.localstack
	@echo "Pruning docker system..."
	docker system prune -f
	@echo "✅ Environment cleaned"

clean-queues: ## Delete all SQS queues in LocalStack
	@echo "🧹 Cleaning SQS queues..."
	@aws sqs list-queues --endpoint-url http://localhost:4566 --region us-east-1 --query 'QueueUrls[]' --output text 2>/dev/null | tr '\t' '\n' | \
	while read -r queue_url; do \
		if [ -n "$$queue_url" ]; then \
			echo "Deleting: $$queue_url"; \
			ENDPOINT=$$(echo "$$queue_url" | sed -E 's#(https?://[^/]+).*#\1#'); \
			aws sqs delete-queue --endpoint-url "$$ENDPOINT" --queue-url "$$queue_url" || true; \
		fi; \
	done
	@rm -f .env.localstack
	@echo "✅ SQS queues cleaned"

reset-local: clean-env setup-dev ## Complete reset: clean + setup
	@echo "✅ Local environment reset complete"

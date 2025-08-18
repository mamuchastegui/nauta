# Nauta Takehome - Hexagonal Architecture Project

A **Kotlin + Spring Boot 3** project implementing **hexagonal architecture** for email ingestion and order processing with **AWS SQS** message queuing and **PostgreSQL** persistence.

## 🏗️ Architecture

The project follows hexagonal (ports and adapters) architecture with clear separation of concerns:

```
src/main/kotlin/com/nauta/takehome/
├── domain/                     # Core business entities and value objects
│   ├── BookingRef.kt          # Value object with basic validation
│   ├── PurchaseRef.kt         # Purchase reference value object
│   ├── ContainerRef.kt        # Container ref with ISO 6346 validation
│   ├── InvoiceRef.kt          # Invoice reference value object
│   ├── Order.kt               # Order entity
│   ├── Container.kt           # Container entity
│   ├── Booking.kt             # Booking entity
│   └── Invoice.kt             # Invoice entity
├── application/               # Use cases and business logic
│   └── IngestService.kt       # Email ingestion and message processing
├── infrastructure/
│   ├── web/                   # REST API controllers
│   │   ├── EmailController.kt    # POST /api/email (returns 202)
│   │   ├── OrderController.kt    # GET /api/orders, /api/orders/{purchaseId}/containers
│   │   └── ContainerController.kt # GET /api/containers, /api/containers/{containerId}/orders
│   ├── messaging/             # Event bus and SQS integration
│   │   ├── EventBus.kt           # Port interface
│   │   ├── SqsEventBus.kt        # SQS adapter implementation
│   │   └── SqsIngestConsumer.kt  # Message consumer with backoff and DLQ
│   ├── persistence/           # Repository implementations with native SQL
│   │   ├── JdbcOrderRepository.kt
│   │   ├── JdbcContainerRepository.kt
│   │   ├── JdbcBookingRepository.kt
│   │   └── JdbcInvoiceRepository.kt
│   ├── security/              # JWT authentication and tenant scoping
│   │   ├── TenantContext.kt      # Thread-local tenant context
│   │   ├── JwtAuthenticationFilter.kt # JWT HS256 validation
│   │   └── SecurityConfig.kt     # Spring Security configuration
│   └── config/                # Application configuration
│       └── ApplicationConfig.kt  # Beans, Jackson Kotlin, SQS client
```

## 🚀 Quick Start

### Prerequisites

- **Java 21+**
- **Docker & Docker Compose**
- **AWS CLI** (for LocalStack SQS setup)

### 1. Clone and Setup

```bash
git clone git@github.com:mamuchastegui/nauta.git
cd nauta/nauta-takehome
```

### 2. Environment Configuration

```bash
# Copy environment template
cp .env.example .env

# Edit .env - set your JWT_SECRET (32+ characters)
vim .env
```

See [Environment Configuration](docs/ENVIRONMENT.md) for detailed setup instructions.

### 3. Start Development Environment

```bash
# Start infrastructure services (PostgreSQL + LocalStack SQS)
make setup-dev

# Validate environment is ready
make validate-local
```

### 4. Run Application

```bash
# Run with local profile (docker services)
make run-local

# Run with AWS dev profile (RDS + AWS SQS)
make run-dev-aws
```

The application starts on `http://localhost:8080`

## 📚 Documentation

| Document | Description |
|----------|-------------|
| [Environment Configuration](docs/ENVIRONMENT.md) | Environment variables, profiles, and configuration |
| [API Testing Guide](docs/API_TESTING.md) | JWT tokens, API endpoints, and testing scenarios |
| [Development Commands](docs/DEVELOPMENT.md) | All make commands, Docker operations, and workflows |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Common issues and solutions |

## 🧪 Quick Test

```bash
# Generate JWT token at https://jwt.io with payload:
# {"tenant_id": "tenant-123", "sub": "user-123", "exp": 1940998800}

# Test email ingestion
curl -X POST http://localhost:8080/api/email \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "booking": "BK123456",
    "orders": [{"purchase": "PO789012", "invoices": [{"invoice": "INV345678"}]}],
    "containers": [{"container": "ABCD1234567"}]
  }'

# Query results
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" http://localhost:8080/api/orders
```

See [API Testing Guide](docs/API_TESTING.md) for comprehensive testing examples.

## 🛠️ Common Commands

```bash
# Environment
make setup-dev         # Start PostgreSQL + LocalStack, create SQS queues
make validate-local    # Check all services are healthy
make clean-env         # Clean and reset environment

# Application  
make run-local         # Run with local profile
make run-dev-aws       # Run with AWS dev profile
make test              # Run tests
make coverage          # Generate test coverage report

# Troubleshooting
make check-env         # Check environment variables
make reset-local       # Complete reset: clean + setup
```

See [Development Commands](docs/DEVELOPMENT.md) for complete command reference.

## 📊 Monitoring & Testing

### Health Endpoints
- **Health Check**: `http://localhost:8080/actuator/health`
- **Metrics**: `http://localhost:8080/actuator/metrics`

*Note: Prometheus endpoint available but not exposed by default. Add `prometheus` to `management.endpoints.web.exposure.include` if needed.*

### Test Coverage
Generate and view test coverage report:
```bash
make coverage  # Generates HTML report and opens in browser
```

**Current Coverage:** ~75% overall
- **Domain Logic**: 86% (business rules and value objects)
- **Security**: 90% (JWT authentication and tenant isolation)
- **Web Controllers**: 84% (API endpoints)
- **Persistence**: 82% (repository implementations)
- **Messaging**: 45% (SQS event publishing and consuming)

## ✅ Implementation Status

### Core Features Implemented
- [x] **Hexagonal architecture** with clear domain boundaries
- [x] **Kotlin value classes** with comprehensive business validation (ContainerRef ISO 6346, BookingRef, PurchaseRef)
- [x] **Multi-tenant JWT authentication** with complete tenant data isolation
- [x] **SQS messaging** with circuit breaker resilience and error handling
- [x] **Docker development environment** with PostgreSQL and LocalStack
- [x] **REST API controllers** with proper HTTP status codes and tenant scoping
- [x] **Configuration management** for local and AWS environments
- [x] **Database migrations** with Flyway schema management and performance indexes
- [x] **M:N relationship model** with confidence scoring and linking reasons
- [x] **Progressive linking algorithms** with booking-based and fallback strategies
- [x] **Enhanced error handling** with circuit breakers and fallback mechanisms
- [x] **Repository implementations** with complete upsert logic and tenant isolation
- [x] **Unit and integration testing** with TestContainers for realistic testing scenarios
- [x] **Performance optimization** with strategic database indexing for tenant-based queries
- [x] **System resilience** with Resilience4j circuit breakers for SQS messaging
- [x] **Basic monitoring** with Spring Boot Actuator health and metrics endpoints
- [x] **Code quality** with ktlint and detekt static analysis

### Technical Implementation Summary

**Core functionality delivered:**
- Multi-tenant data isolation with JWT authentication
- Progressive data linking algorithms with confidence scoring
- Event-driven architecture using SQS messaging with circuit breaker resilience
- Database performance optimization with 7 strategic indexes for tenant-based queries
- Comprehensive test suite covering domain logic and integration scenarios
- Code quality maintained with automated linting and static analysis tools

**Architecture highlights:**
- Hexagonal architecture with clear domain boundaries
- Kotlin value classes with business validation (ISO 6346 for container references)
- Repository pattern with upsert operations for data consistency
- Docker-based development environment with LocalStack for local testing

## 🏆 Technical Highlights

- **Clean Architecture**: Hexagonal design with clear separation between domain, application, and infrastructure layers
- **Type Safety**: Kotlin value classes with domain-specific validation (ISO 6346 format for container references)
- **Multi-tenancy**: JWT-based authentication with tenant data isolation at the database level
- **Progressive Linking**: Intelligent algorithms for order-container relationships with confidence scoring
- **Performance**: Strategic database indexing and HikariCP connection pooling
- **Resilience**: Circuit breaker pattern implemented for SQS messaging with fallback handling
- **Testing**: Unit tests for domain logic and integration tests using TestContainers
- **Development**: Docker Compose setup with LocalStack for local SQS simulation

## 🤝 Contributing

1. Follow the existing hexagonal architecture patterns
2. Add tests for new functionality  
3. Update documentation for significant changes
4. Use the development commands for consistent workflow

## 📋 Need Help?

- **Environment issues**: See [Environment Configuration](docs/ENVIRONMENT.md)
- **API problems**: Check [API Testing Guide](docs/API_TESTING.md)  
- **Build/deployment**: Review [Development Commands](docs/DEVELOPMENT.md)
- **Errors/debugging**: Consult [Troubleshooting Guide](docs/TROUBLESHOOTING.md)
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
git clone https://github.com/mamuchastegui/nauta
cd nauta-takehome
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

# Troubleshooting
make check-env         # Check environment variables
make reset-local       # Complete reset: clean + setup
```

See [Development Commands](docs/DEVELOPMENT.md) for complete command reference.

## 📊 Monitoring & Health

- **Health Check**: `http://localhost:8080/actuator/health`
- **Metrics**: `http://localhost:8080/actuator/metrics`
- **Prometheus**: `http://localhost:8080/actuator/prometheus`

## ✅ Implementation Status

### ✅ Completed
- [x] **Hexagonal architecture** with clear domain boundaries
- [x] **Kotlin value classes** with business validation
- [x] **Multi-tenant JWT authentication** with tenant scoping
- [x] **SQS messaging** with LocalStack support and endpoint agnostic scripts
- [x] **Docker development environment** with PostgreSQL and LocalStack
- [x] **REST API controllers** with proper HTTP status codes
- [x] **Configuration management** for local and AWS environments
- [x] **Database migrations** with Flyway schema management
- [x] **M:N relationship model** with order_containers linking table
- [x] **Progressive linking algorithms** with confidence scoring
- [x] **Enhanced error handling** with specific exception types
- [x] **Repository implementations** with complete upsert logic

### 🚧 Next Phase: Testing & Performance

#### Priority 1: Core Testing
1. **Integration Tests** - End-to-end testing with TestContainers for M:N relationship validation
2. **API Contract Tests** - Verify format compliance
3. **Multi-tenant Isolation Tests** - Ensure complete data separation between tenants
4. **Progressive Linking Tests** - Validate relationship inference algorithms

#### Priority 2: Performance & Observability  
5. **Query Performance Optimization** - Analyze and optimize database queries with proper indexing
6. **Connection Pool Tuning** - Optimize HikariCP settings for concurrent load
7. **Comprehensive Monitoring** - Add domain-specific metrics and health checks
8. **Load Testing** - Validate system performance under realistic traffic

#### Priority 3: Advanced Features
9. **Advanced Linking Intelligence** - ML-based relationship inference with confidence scoring
10. **Bulk Operation APIs** - Efficient endpoints for large-scale data operations
11. **Data Quality Monitoring** - Automated detection of orphaned entities and inconsistencies
12. **API Documentation** - Complete OpenAPI/Swagger specification with examples

## 🏆 Project Highlights

- **Clean Architecture**: Hexagonal design with clear separation of concerns
- **Type Safety**: Kotlin value classes for business identifiers with validation
- **Multi-tenancy**: JWT-based tenant scoping for data isolation  
- **Flexible Relationships**: M:N model enables complex logistics scenarios
- **Progressive Linking**: Smart algorithms infer relationships with confidence scoring
- **Message Queuing**: Asynchronous processing with SQS, DLQ, and retry logic
- **DevOps Ready**: Docker Compose for local development with LocalStack
- **Production Foundation**: Scalable architecture ready for event-driven evolution

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
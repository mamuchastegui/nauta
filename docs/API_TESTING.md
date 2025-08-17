# API Testing Guide

This document provides comprehensive examples for testing the Nauta Takehome API endpoints.

## Prerequisites

1. **Application Running**: Start the application with `make run-local`
2. **JWT Token**: Generate a valid JWT token with `tenant_id` claim
3. **Environment**: Ensure LocalStack and PostgreSQL are running (`make validate-local`)

## JWT Token Generation

The API uses JWT authentication with HS256 algorithm. All requests require a valid token with a `tenant_id` claim.

### Option 1: Using jwt.io (Recommended)

1. Go to [jwt.io](https://jwt.io)
2. In **PAYLOAD** section, use:
   ```json
   {
     "tenant_id": "tenant-123",
     "sub": "user-123",
     "iat": 1640995200,
     "exp": 1940998800
   }
   ```
3. In **"Verify Signature"** section, paste your `JWT_SECRET` from `.env`
4. Copy the encoded JWT token from the **"Encoded"** section

### Option 2: Command Line (Linux/macOS)

```bash
# Create header and payload
HEADER='{"alg":"HS256","typ":"JWT"}'
PAYLOAD='{"tenant_id":"tenant-123","sub":"user-123","iat":1640995200,"exp":1940998800}'

# Base64 encode (URL-safe)
HEADER_B64=$(echo -n $HEADER | base64 | tr -d '\n' | tr '/+' '_-' | tr -d '=')
PAYLOAD_B64=$(echo -n $PAYLOAD | base64 | tr -d '\n' | tr '/+' '_-' | tr -d '=')

# Sign with your JWT_SECRET (make sure the env is set)
SIGNATURE=$(echo -n "$HEADER_B64.$PAYLOAD_B64" | openssl dgst -sha256 -hmac "$JWT_SECRET" -binary | base64 | tr -d '\n' | tr '/+' '_-' | tr -d '=')

# Complete token
JWT_TOKEN="$HEADER_B64.$PAYLOAD_B64.$SIGNATURE"
echo $JWT_TOKEN
```

### Option 3: Using curl and jq

```bash
# Assuming you have a JWT generation endpoint or script
curl -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"user_id": "user-123", "tenant_id": "tenant-123"}' | jq -r '.token'
```

## API Endpoints

### 1. Email Ingestion

**Endpoint**: `POST /api/email`  
**Purpose**: Ingest email data for processing via SQS  
**Response**: `202 Accepted` (asynchronous processing)

#### Basic Request

```bash
curl -X POST http://localhost:8080/api/email \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "booking": {
      "booking_ref": "BK123456"
    },
    "orders": [{
      "purchase_ref": "PO789012",
      "invoices": [{
        "invoice_ref": "INV345678"
      }]
    }],
    "containers": [{
      "container_ref": "ABCD1234567"
    }]
  }'
```

#### Complex Request with Multiple Items

```bash
curl -X POST http://localhost:8080/api/email \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "booking": {
      "booking_ref": "BK789123"
    },
    "orders": [
      {
        "purchase_ref": "PO111222",
        "invoices": [
          {"invoice_ref": "INV111"},
          {"invoice_ref": "INV222"}
        ]
      },
      {
        "purchase_ref": "PO333444",
        "invoices": [
          {"invoice_ref": "INV333"}
        ]
      }
    ],
    "containers": [
      {"container_ref": "CONT1234567"},
      {"container_ref": "CONT7654321"}
    ]
  }'
```

#### Expected Response

```json
{
  "status": "accepted",
  "message": "Email queued for processing",
  "timestamp": "2024-01-15T10:30:00Z"
}
```

### 2. Query Orders

**Endpoint**: `GET /api/orders`  
**Purpose**: Retrieve all orders for the authenticated tenant

```bash
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  http://localhost:8080/api/orders
```

#### Expected Response

```json
[
  {
    "purchaseRef": "PO789012",
    "tenantId": "tenant-123",
    "invoices": [
      {
        "invoiceRef": "INV345678",
        "tenantId": "tenant-123"
      }
    ]
  }
]
```

### 3. Query Containers

**Endpoint**: `GET /api/containers`  
**Purpose**: Retrieve all containers for the authenticated tenant

```bash
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  http://localhost:8080/api/containers
```

#### Expected Response

```json
[
  {
    "containerRef": "ABCD1234567",
    "tenantId": "tenant-123"
  }
]
```

### 4. Get Containers for Specific Order

**Endpoint**: `GET /api/orders/{purchaseRef}/containers`  
**Purpose**: Retrieve containers associated with a specific order

```bash
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  http://localhost:8080/api/orders/PO789012/containers
```

#### Expected Response

```json
[
  {
    "containerRef": "ABCD1234567",
    "tenantId": "tenant-123"
  }
]
```

### 5. Get Orders for Specific Container

**Endpoint**: `GET /api/containers/{containerRef}/orders`  
**Purpose**: Retrieve orders associated with a specific container

```bash
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  http://localhost:8080/api/containers/ABCD1234567/orders
```

#### Expected Response

```json
[
  {
    "purchaseRef": "PO789012",
    "tenantId": "tenant-123",
    "invoices": [
      {
        "invoiceRef": "INV345678",
        "tenantId": "tenant-123"
      }
    ]
  }
]
```

## Testing Scenarios

### Scenario 1: Complete Workflow Test

```bash
# 1. Set your JWT token
JWT_TOKEN="your-jwt-token-here"

# 2. Ingest email data
curl -X POST http://localhost:8080/api/email \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "booking": {"booking_ref": "BK999888"},
    "orders": [{
      "purchase_ref": "PO999888",
      "invoices": [{"invoice_ref": "INV999888"}]
    }],
    "containers": [{"container_ref": "TEST1234567"}]
  }'

# 3. Wait a few seconds for async processing
sleep 5

# 4. Verify order was created
curl -H "Authorization: Bearer $JWT_TOKEN" \
  http://localhost:8080/api/orders

# 5. Verify container was created
curl -H "Authorization: Bearer $JWT_TOKEN" \
  http://localhost:8080/api/containers

# 6. Test relationships
curl -H "Authorization: Bearer $JWT_TOKEN" \
  http://localhost:8080/api/orders/PO999888/containers

curl -H "Authorization: Bearer $JWT_TOKEN" \
  http://localhost:8080/api/containers/TEST1234567/orders
```

### Scenario 2: Multi-Tenant Isolation Test

```bash
# Generate two JWT tokens with different tenant_ids
TENANT_A_TOKEN="jwt-token-with-tenant-a"
TENANT_B_TOKEN="jwt-token-with-tenant-b"

# Insert data for Tenant A
curl -X POST http://localhost:8080/api/email \
  -H "Authorization: Bearer $TENANT_A_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "booking": {"booking_ref": "BK_TENANT_A"},
    "orders": [{"purchase_ref": "PO_TENANT_A", "invoices": [{"invoice_ref": "INV_TENANT_A"}]}],
    "containers": [{"container_ref": "CONTA111111"}]
  }'

# Insert data for Tenant B
curl -X POST http://localhost:8080/api/email \
  -H "Authorization: Bearer $TENANT_B_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "booking": {"booking_ref": "BK_TENANT_B"},
    "orders": [{"purchase_ref": "PO_TENANT_B", "invoices": [{"invoice_ref": "INV_TENANT_B"}]}],
    "containers": [{"container_ref": "CONTB222222"}]
  }'

# Verify Tenant A only sees their data
curl -H "Authorization: Bearer $TENANT_A_TOKEN" \
  http://localhost:8080/api/orders
# Should only return PO_TENANT_A

# Verify Tenant B only sees their data
curl -H "Authorization: Bearer $TENANT_B_TOKEN" \
  http://localhost:8080/api/orders
# Should only return PO_TENANT_B
```

## Error Handling

### Authentication Errors

```bash
# Missing token
curl http://localhost:8080/api/orders
# Response: 401 Unauthorized

# Invalid token
curl -H "Authorization: Bearer invalid-token" \
  http://localhost:8080/api/orders
# Response: 401 Unauthorized

# Expired token
curl -H "Authorization: Bearer expired-jwt-token" \
  http://localhost:8080/api/orders
# Response: 401 Unauthorized
```

### Validation Errors

```bash
# Invalid container reference (fails ISO 6346 validation)
curl -X POST http://localhost:8080/api/email \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "containers": [{"container_ref": "INVALID"}]
  }'
# Response: 400 Bad Request with validation details
```

### Not Found Errors

```bash
# Non-existent order
curl -H "Authorization: Bearer $JWT_TOKEN" \
  http://localhost:8080/api/orders/NONEXISTENT/containers
# Response: 404 Not Found

# Non-existent container
curl -H "Authorization: Bearer $JWT_TOKEN" \
  http://localhost:8080/api/containers/NONEXISTENT/orders
# Response: 404 Not Found
```

## Monitoring and Health Checks

### Application Health

```bash
# Basic health check
curl http://localhost:8080/actuator/health

# Detailed health information
curl http://localhost:8080/actuator/health/db
curl http://localhost:8080/actuator/health/diskSpace
```

### Metrics

```bash
# Prometheus metrics
curl http://localhost:8080/actuator/prometheus

# Application metrics
curl http://localhost:8080/actuator/metrics
curl http://localhost:8080/actuator/metrics/http.server.requests
```

## Troubleshooting API Issues

### Check SQS Message Processing

```bash
# Send test message to SQS queue
./scripts/setup-queues.sh dev --test

# Check if messages are being processed
curl -H "Authorization: Bearer $JWT_TOKEN" \
  http://localhost:8080/api/orders
```

### Database Connection Issues

```bash
# Check database health
curl http://localhost:8080/actuator/health/db

# Verify database tables exist
make validate-local
```

### JWT Issues

```bash
# Verify JWT_SECRET is loaded
make check-env | grep JWT_SECRET

# Test token generation manually
echo "YOUR_JWT_TOKEN" | cut -d. -f2 | base64 -d
# Should decode to valid JSON with tenant_id
```

For more troubleshooting information, see [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
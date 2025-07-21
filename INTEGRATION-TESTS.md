# Integration Tests

This project includes comprehensive integration tests that validate the GraphQL Kafka Connector against real services using Docker Compose.

## Overview

The integration tests verify:
- ✅ **Basic Data Ingestion**: Connector polls GraphQL API and produces records
- ✅ **Pagination Handling**: Proper cursor-based pagination across multiple poll cycles  
- ✅ **Error Handling**: Graceful handling of connection failures and invalid endpoints
- ✅ **Circuit Breaker**: Circuit breaker pattern activation under repeated failures
- ✅ **JMX Metrics**: Availability and correctness of monitoring metrics
- ✅ **Authentication**: Proper header handling for API authentication
- ✅ **GraphQL Variables**: Custom variable injection into queries
- ✅ **Offset Management**: Proper offset tracking and resumption after restarts

## Test Architecture

### Services
- **Kafka Broker**: Full Kafka cluster with Zookeeper
- **Mock GraphQL API**: Node.js Apollo Server with realistic test data
- **Integration Test Runner**: Java tests that verify end-to-end functionality

### Test Data
The mock GraphQL API provides:
- **100 Users**: With pagination, filtering, and realistic data
- **50 Products**: With categories, pricing, and inventory status
- **Relay-style Pagination**: Standard cursor-based pagination pattern

## Running Integration Tests

### Method 1: Automated Script (Recommended)

```bash
# Run integration tests with automatic service management
./run-integration-tests.sh

# Keep services running after tests for manual inspection
./run-integration-tests.sh --keep-running

# Generate coverage report
./run-integration-tests.sh --coverage
```

### Method 2: Manual Docker Compose

```bash
# Start all services
docker-compose up -d

# Wait for services to be ready
./wait-for-services.sh

# Run only integration tests
mvn failsafe:integration-test failsafe:verify

# Clean up
docker-compose down -v
```

### Method 3: Isolated Test Environment

```bash
# Use integration-specific compose file
docker-compose -f docker-compose.integration.yml up -d

# Run tests in container
docker-compose -f docker-compose.integration.yml run --rm integration-tests
```

## Test Configuration

Integration tests are configured separately from unit tests:

- **Unit Tests**: `mvn test` - Runs quickly without external dependencies
- **Integration Tests**: `mvn failsafe:integration-test` - Requires Docker services

### Maven Configuration

```xml
<!-- Unit tests exclude integration tests -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <excludes>
      <exclude>**/*IntegrationTest.java</exclude>
    </excludes>
  </configuration>
</plugin>

<!-- Integration tests run separately -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-failsafe-plugin</artifactId>
  <configuration>
    <includes>
      <include>**/*IntegrationTest.java</include>
    </includes>
  </configuration>
</plugin>
```

## Service Endpoints

When integration tests are running, you can access:

- 🌐 **GraphQL Playground**: http://localhost:4000/graphql
- 📊 **Kafka UI**: http://localhost:8080  
- 🔌 **Kafka Connect REST**: http://localhost:8083
- ❤️ **Health Check**: http://localhost:4000/health

## Test Coverage

Integration tests cover scenarios that unit tests cannot:

### Network & Infrastructure
- Real HTTP connections to GraphQL endpoints
- Kafka broker interaction and topic creation
- Service discovery and health checking
- Connection pooling and timeout behavior

### Error Scenarios  
- Network partitions and service unavailability
- GraphQL API errors and malformed responses
- Kafka broker failures and recovery
- Authentication failures and token expiration

### Performance & Scalability
- Memory usage under sustained load
- Connection pool exhaustion
- Large payload handling
- Pagination with thousands of records

## Troubleshooting

### Common Issues

**Services won't start:**
```bash
# Check Docker resources
docker system prune -f

# Verify ports are available
lsof -i :4000 :8080 :8083 :9092
```

**Tests timeout:**
```bash
# Check service logs
docker-compose logs mock-graphql-api
docker-compose logs kafka-connect
docker-compose logs kafka
```

**Integration test failures:**
```bash
# Run with verbose logging
mvn failsafe:integration-test -X

# Check individual test methods
mvn failsafe:integration-test -Dtest=GraphQLSourceConnectorIntegrationTest#testBasicDataIngestion
```

### Manual Testing

You can manually test the connector using the running services:

```bash
# Create a connector
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @connectors/users-connector.json

# Check connector status
curl http://localhost:8083/connectors/graphql-users-connector/status

# View produced messages in Kafka UI
open http://localhost:8080
```

## Development

### Adding New Integration Tests

1. Create test methods in `GraphQLSourceConnectorIntegrationTest.java`
2. Follow the naming pattern `test*()` for automatic discovery
3. Use the helper method `createBasicConnectorConfig()` for consistent setup
4. Add service wait logic if testing new external dependencies

### Extending Mock Data

1. Edit `test-graphql-api/server.js` to add new entities or fields
2. Update GraphQL schema and resolvers as needed
3. Restart services: `docker-compose restart mock-graphql-api`

### CI/CD Integration

```bash
# In your CI pipeline
./run-integration-tests.sh
echo $? # Check exit code for pass/fail
```

The integration test suite provides comprehensive validation that the GraphQL Kafka Connector works correctly in realistic production-like environments.
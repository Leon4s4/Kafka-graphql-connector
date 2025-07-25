# Kafka GraphQL Source Connector

[![CI/CD Pipeline](https://github.com/your-username/kafka-graphql-connector/actions/workflows/ci.yml/badge.svg)](https://github.com/your-username/kafka-graphql-connector/actions/workflows/ci.yml)
[![CodeQL](https://github.com/your-username/kafka-graphql-connector/actions/workflows/github-code-scanning/codeql/badge.svg)](https://github.com/your-username/kafka-graphql-connector/actions/workflows/github-code-scanning/codeql)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.java.net/projects/jdk/17/)
[![Kafka](https://img.shields.io/badge/Kafka-3.7.0-red.svg)](https://kafka.apache.org/)

A robust Kafka Connect source connector that streams data from GraphQL APIs to Kafka topics with support for pagination, authentication, error handling, and comprehensive monitoring.

## Features

- **GraphQL API Integration**: Execute custom GraphQL queries with cursor-based pagination
- **Flexible Query Configuration**: Full GraphQL query configuration with JSONPath data extraction
- **Robust Error Handling**: Circuit breaker pattern with exponential backoff for connection failures
- **Authentication Support**: Custom headers for Bearer tokens, API keys, and other auth methods
- **Performance Optimized**: Connection pooling, configurable timeouts, and efficient pagination
- **Comprehensive Monitoring**: JMX metrics, structured logging with correlation IDs
- **Offset Management**: Reliable cursor-based offset tracking for at-least-once delivery
- **Graceful Shutdown**: Proper resource cleanup and connection management

## Requirements

- Java 17+
- Apache Kafka 2.8.0+
- Maven 3.6+

## Quick Start

### 1. Build the Connector

```bash
mvn clean package
```

The resulting JAR will be located in the `target/` directory.

### 2. Deploy to Kafka Connect

Copy the JAR to your Kafka Connect plugins directory:

```bash
cp target/graphql-kafka-connector-0.1.0-SNAPSHOT.jar /path/to/kafka/connect/plugins/
```

### 3. Configure the Connector

Create a configuration file or use the REST API:

```json
{
  "name": "graphql-source-connector",
  "config": {
    "connector.class": "com.example.graphqlconnector.GraphQLSourceConnector",
    "tasks.max": "1",
    "graphql.endpoint.url": "https://api.example.com/graphql",
    "graphql.query": "query GetUsers($first: Int!, $after: String) { users(first: $first, after: $after) { edges { node { id name email createdAt } cursor } pageInfo { hasNextPage endCursor } } }",
    "data.path": "users.edges[*].node",
    "pagination.cursor.path": "users.pageInfo.endCursor",
    "pagination.hasmore.path": "users.pageInfo.hasNextPage",
    "record.key.path": "id",
    "result.size": "100",
    "polling.interval.ms": "30000",
    "kafka.topic.name": "graphql_users"
  }
}
```

### 4. Start the Connector

```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @your-config.json
```

## Configuration Reference

### Required Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `graphql.endpoint.url` | String | GraphQL API endpoint URL |
| `graphql.query` | String | Full GraphQL query with pagination variables ($first, $after) |
| `data.path` | String | JSONPath expression to extract data records from GraphQL response |
| `kafka.topic.name` | String | Kafka topic name where records will be published |

### Optional Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `graphql.variables` | String | "{}" | Additional GraphQL query variables as JSON object |
| `graphql.headers` | String | "" | Custom headers (format: key1:value1,key2:value2) |
| `pagination.cursor.path` | String | "*.pageInfo.endCursor" | JSONPath to extract next page cursor |
| `pagination.hasmore.path` | String | "*.pageInfo.hasNextPage" | JSONPath to extract hasMore pagination flag |
| `record.key.path` | String | "id" | JSONPath to extract record key from each data record |
| `result.size` | Integer | 100 | Number of records to request per GraphQL query |
| `polling.interval.ms` | Long | 30000 | Interval between queries (milliseconds) |
| `query.timeout.ms` | Long | 30000 | Timeout for GraphQL queries |
| `max.retries` | Integer | 3 | Maximum retry attempts for failed queries |
| `retry.backoff.ms` | Long | 1000 | Backoff time between retries |

## GraphQL Query Configuration

The connector uses your custom GraphQL query with pagination variables. You provide the full query and configure data extraction paths:

### Example Query
```graphql
query GetUsers($first: Int!, $after: String) {
  users(first: $first, after: $after) {
    edges {
      node {
        id
        name
        email
        createdAt
        profile {
          avatar
          bio
        }
      }
      cursor
    }
    pageInfo {
      hasNextPage
      endCursor
    }
  }
}
```

### Data Extraction Configuration
```json
{
  "data.path": "users.edges[*].node",
  "pagination.cursor.path": "users.pageInfo.endCursor",
  "pagination.hasmore.path": "users.pageInfo.hasNextPage",
  "record.key.path": "id"
}
```

## Authentication

### Bearer Token Authentication

```json
{
  "graphql.headers": "Authorization:Bearer your-token-here"
}
```

### API Key Authentication

```json
{
  "graphql.headers": "X-API-Key:your-api-key,Content-Type:application/json"
}
```

### Multiple Headers

```json
{
  "graphql.headers": "Authorization:Bearer token,X-Custom-Header:value,Content-Type:application/json"
}
```

### GraphQL Variables

Add custom variables to your GraphQL queries:

```json
{
  "graphql.variables": "{\"status\": \"ACTIVE\", \"category\": \"premium\"}"
}
```

## Complete Configuration Examples

### Basic User Sync
```json
{
  "name": "graphql-users-connector",
  "config": {
    "connector.class": "com.example.graphqlconnector.GraphQLSourceConnector",
    "tasks.max": "1",
    
    "graphql.endpoint.url": "https://api.example.com/graphql",
    "graphql.query": "query GetUsers($first: Int!, $after: String) { users(first: $first, after: $after) { edges { node { id name email createdAt status } cursor } pageInfo { hasNextPage endCursor } } }",
    "graphql.variables": "{}",
    
    "data.path": "users.edges[*].node",
    "pagination.cursor.path": "users.pageInfo.endCursor",
    "pagination.hasmore.path": "users.pageInfo.hasNextPage", 
    "record.key.path": "id",
    
    "result.size": "100",
    "polling.interval.ms": "30000",
    "kafka.topic.name": "user_events",
    
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "true"
  }
}
```

### Advanced Configuration with Authentication
```json
{
  "name": "graphql-products-connector", 
  "config": {
    "connector.class": "com.example.graphqlconnector.GraphQLSourceConnector",
    "tasks.max": "1",
    
    "graphql.endpoint.url": "https://api.example.com/graphql",
    "graphql.query": "query GetProducts($first: Int!, $after: String, $category: String) { products(first: $first, after: $after, filter: {category: $category}) { edges { node { id name description price category inStock updatedAt } cursor } pageInfo { hasNextPage endCursor } } }",
    "graphql.variables": "{\"category\": \"electronics\"}",
    "graphql.headers": "Authorization:Bearer your-token-here,X-API-Version:v2",
    
    "data.path": "products.edges[*].node",
    "pagination.cursor.path": "products.pageInfo.endCursor",
    "pagination.hasmore.path": "products.pageInfo.hasNextPage",
    "record.key.path": "id",
    
    "result.size": "50",
    "polling.interval.ms": "15000",
    "kafka.topic.name": "product_catalog",
    "query.timeout.ms": "45000",
    "max.retries": "5",
    "retry.backoff.ms": "2000"
  }
}
```

## Monitoring and Observability

### Logging

The connector provides structured logging with:
- **Correlation IDs**: Track requests across the system
- **Performance Metrics**: Query execution times and record counts
- **Error Context**: Detailed error information with retry attempts
- **Debug Information**: Query generation and response parsing

### Log Levels

- `INFO`: Connector lifecycle, record processing statistics, offset commits
- `DEBUG`: Query details, parsing information, configuration validation
- `WARN`: Retry attempts, non-fatal errors, circuit breaker status
- `ERROR`: Fatal errors, configuration issues, connection failures

### JMX Metrics

The connector exposes comprehensive JMX metrics via MBean `com.example.graphqlconnector:type=GraphQLSourceTask,entity=<entity_name>`:

#### Health & Status
- `ConnectorStatus`: Current health status (HEALTHY, DEGRADED, UNHEALTHY, STOPPING)
- `isHealthy()`: Boolean health indicator
- `isCircuitBreakerOpen()`: Circuit breaker state
- `ConsecutiveFailures`: Number of consecutive failures

#### Performance Metrics
- `TotalRecordsProcessed`: Total records processed since start
- `RecordsPerSecond`: Current processing rate
- `AverageQueryTimeMs`: Average GraphQL query execution time
- `LastQueryTimeMs`: Last query execution time

#### Error Tracking
- `TotalFailedPollCycles`: Total number of failed poll cycles
- `ErrorRate`: Percentage of failed operations
- `LastErrorMessage`: Most recent error message
- `LastErrorTime`: Timestamp of last error

#### Connection Statistics  
- `ActiveConnections`: Number of active HTTP connections
- `IdleConnections`: Number of idle connections in pool
- `TotalBytesReceived`: Total bytes received from GraphQL endpoint

#### Operations
- `resetMetrics()`: Reset all metrics counters
- `resetErrorTracking()`: Clear error tracking state
- `getHealthSummary()`: Comprehensive health summary string

#### Example JMX Query
```bash
# Get health summary via JMX
jconsole -J-Djava.class.path=$KAFKA_HOME/libs/kafka-clients*.jar
# Navigate to: com.example.graphqlconnector -> GraphQLSourceTask -> getHealthSummary()
```

## Error Handling

### Circuit Breaker Pattern

- **Connection Errors**: Circuit breaker opens after 10 consecutive failures
- **Exponential Backoff**: Configurable retry delays with exponential increase
- **GraphQL Errors**: Logged and processing continues with cursor reset
- **Automatic Recovery**: Circuit breaker closes after successful operations
- **Graceful Degradation**: Continues polling with reduced frequency during failures

### Error Tolerance

Configure error handling in your connector:

```json
{
  "errors.retry.timeout": "300000",
  "errors.retry.delay.max.ms": "60000",
  "errors.tolerance": "none"
}
```

## Performance Tuning

### Connection Pooling

The connector uses connection pooling with:
- Max connections: 10
- Keep-alive: 5 minutes
- Automatic retry on connection failure

### Memory Optimization

- Streaming JSON processing
- Efficient pagination handling
- Configurable result set sizes
- Backpressure handling

### Recommended Settings

For high-throughput scenarios:

```json
{
  "result.size": "1000",
  "polling.interval.ms": "10000",
  "query.timeout.ms": "60000",
  "max.retries": "5",
  "retry.backoff.ms": "2000"
}
```

For real-time scenarios:

```json
{
  "result.size": "100",
  "polling.interval.ms": "5000",
  "query.timeout.ms": "15000",
  "max.retries": "3",
  "retry.backoff.ms": "1000"
}
```

## Troubleshooting

### Common Issues

1. **Connection Timeouts**
   - Increase `query.timeout.ms`
   - Check network connectivity
   - Verify GraphQL endpoint accessibility

2. **Authentication Failures**
   - Verify header format in `graphql.headers`
   - Check token expiration
   - Validate API permissions

3. **GraphQL Errors**
   - Verify GraphQL query syntax is valid
   - Check that query variables ($first, $after) are supported
   - Review data extraction paths (JSONPath expressions)

4. **Cursor/Offset Issues**
   - Ensure cursor field exists at the configured JSONPath
   - Verify pagination configuration matches GraphQL schema
   - Check that record key path returns unique identifiers

### Debug Mode

Enable debug logging:

```bash
log4j.logger.com.example.graphqlconnector=DEBUG
```


## Development

### Building from Source

```bash
git clone <repository-url>
cd kafka-graphql-connector
mvn clean package
```

### Running Tests

```bash
# Unit tests only
mvn test

# Integration tests (requires Docker)
./run-integration-tests.sh

# All tests with coverage
mvn clean test jacoco:report

# Performance benchmarks
./run-performance-tests.sh
# or
mvn test -Pperformance -Dtest=GraphQLConnectorPerformanceTest
```

### CI/CD Pipeline

The project includes comprehensive GitHub Actions workflows:

- **🚀 CI/CD Pipeline**: Automated testing, building, and releases
- **🔍 PR Checks**: Fast feedback for pull requests  
- **🌙 Nightly Builds**: Extended testing and performance validation
- **📦 Automatic Releases**: Version tagging and JAR distribution

**Build Status**: All tests are automated and must pass before merging.

See [CI-CD.md](CI-CD.md) for complete pipeline documentation.

### Code Coverage

```bash
mvn jacoco:report
```

Coverage reports are automatically generated and tracked in CI/CD.

## Performance Benchmarking

The project includes a comprehensive performance testing framework to measure throughput, memory usage, and reliability under load.

### Running Performance Tests

```bash
# Run all performance benchmarks
./run-performance-tests.sh

# Or run with Maven directly
mvn test -Pperformance -Dtest=GraphQLConnectorPerformanceTest
```

### Benchmark Test Suite

The performance framework includes 5 comprehensive benchmark tests:

1. **Throughput Benchmark** - Measures records/second processing rate
   - Target: >= 5 records/second
   - Duration: 60 seconds
   - Metrics: throughput, response times, latency distribution

2. **Memory Usage Benchmark** - Monitors memory consumption patterns
   - Target: < 100MB memory increase
   - Duration: 30 seconds
   - Metrics: heap usage, memory growth, GC pressure

3. **Concurrent Load Benchmark** - Tests multiple connector tasks simultaneously
   - Configuration: 3 parallel tasks
   - Duration: 30 seconds per task
   - Metrics: concurrent throughput, task success rate

4. **Error Rate Under Load** - Validates error handling under stress
   - Configuration: Aggressive polling (10ms intervals)
   - Target: < 50% error rate under stress
   - Metrics: error rate, recovery time, circuit breaker behavior

5. **JMX Metrics Performance** - Benchmarks JMX method call performance
   - Target: < 10ms per JMX method call
   - Iterations: 1000 calls per method
   - Metrics: method response times, monitoring overhead

### Performance Results

Results are automatically saved to `target/performance-results.json` with detailed metrics:

```json
{
  "timestamp": "2025-01-21T12:00:00Z",
  "duration": "PT2M30S",
  "results": {
    "throughput": {
      "throughputPerSecond": 12.5,
      "avgResponseTimeMs": 850.2,
      "maxResponseTimeMs": 2100
    },
    "memoryUsage": {
      "memoryIncreaseMB": 45.2,
      "avgMemoryUsageMB": 312.8,
      "maxMemoryUsageMB": 425.1
    },
    "concurrentLoad": {
      "tasksSuccessful": 3,
      "concurrentThroughputPerSecond": 25.3
    },
    "errorRate": {
      "errorRate": 8.5,
      "avgPollTimeMs": 125.3
    },
    "jmxPerformance": {
      "getConnectorStatus": 2.1,
      "getTotalRecordsProcessed": 1.8,
      "getHealthSummary": 3.2
    }
  }
}
```

### CI/CD Integration

Performance benchmarks run automatically in:

- **Nightly Builds**: Full performance validation with regression detection
- **Manual Triggers**: On-demand performance testing via GitHub Actions

Performance thresholds are enforced to detect regressions:
- Minimum throughput: 5 records/second
- Maximum memory increase: 100MB
- Maximum error rate: 10%

### Performance Tuning

For optimal performance in your environment:

```json
{
  "result.size": "100-1000",
  "polling.interval.ms": "1000-10000", 
  "query.timeout.ms": "30000-60000",
  "max.retries": "3-5"
}
```

Monitoring recommendations:
- Track JMX metrics for real-time performance
- Monitor memory usage trends
- Set up alerting on error rate spikes
- Use circuit breaker status for health checks

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Ensure all tests pass
5. Submit a pull request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Support

For issues and questions:
- Check the [troubleshooting guide](#troubleshooting)
- Review connector logs for error details
- Open an issue with reproduction steps
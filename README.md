# Kafka GraphQL Source Connector

A robust Kafka Connect source connector that streams data from GraphQL APIs to Kafka topics with support for pagination, authentication, error handling, and comprehensive monitoring.

## Features

- **GraphQL API Integration**: Execute configurable GraphQL queries with cursor-based pagination
- **Robust Error Handling**: Retry logic with exponential backoff for connection failures
- **Authentication Support**: Custom headers for Bearer tokens, API keys, and other auth methods
- **Performance Optimized**: Connection pooling, configurable timeouts, and efficient pagination
- **Comprehensive Monitoring**: Structured logging with correlation IDs and metrics
- **Offset Management**: Reliable offset tracking for exactly-once delivery semantics
- **Flexible Configuration**: Extensive configuration options for various use cases

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
    "entity.name": "users",
    "result.size": "100",
    "selected.columns": "id,name,email,createdAt",
    "polling.interval.ms": "30000",
    "topic.prefix": "graphql_"
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
| `entity.name` | String | Name of the GraphQL entity/type to query |
| `result.size` | Integer | Maximum records per query (pagination size) |
| `selected.columns` | List | Comma-separated list of fields to extract |

### Optional Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `graphql.headers` | String | "" | Custom headers (format: key1:value1,key2:value2) |
| `polling.interval.ms` | Long | 30000 | Interval between queries (milliseconds) |
| `topic.prefix` | String | "" | Prefix for generated topic names |
| `offset.field` | String | "id" | Field name used for offset tracking |
| `query.timeout.ms` | Long | 30000 | Timeout for GraphQL queries |
| `max.retries` | Integer | 3 | Maximum retry attempts for failed queries |
| `retry.backoff.ms` | Long | 1000 | Backoff time between retries |

## GraphQL Query Format

The connector generates GraphQL queries following this pattern:

```graphql
query GetEntity($first: Int!, $after: String) {
  [entity.name](first: $first, after: $after) {
    edges {
      node {
        [selected.columns]
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

## Monitoring and Observability

### Logging

The connector provides structured logging with:
- **Correlation IDs**: Track requests across the system
- **Performance Metrics**: Query execution times and record counts
- **Error Context**: Detailed error information with retry attempts
- **Debug Information**: Query generation and response parsing

### Log Levels

- `INFO`: Connector lifecycle, record processing statistics
- `DEBUG`: Query details, parsing information, configuration validation
- `WARN`: Retry attempts, non-fatal errors
- `ERROR`: Fatal errors, configuration issues, connection failures

### Metrics

The connector tracks:
- Records processed per second
- Query execution time
- Error rates and types
- Offset lag
- Connection pool statistics

## Error Handling

### Retry Logic

- **Connection Errors**: Exponential backoff with configurable max retries
- **GraphQL Errors**: Logged and processing continues
- **Serialization Errors**: Malformed records are skipped
- **Offset Errors**: Fail fast to prevent data loss

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
  "max.retries": "5"
}
```

For real-time scenarios:

```json
{
  "result.size": "100",
  "polling.interval.ms": "5000",
  "query.timeout.ms": "15000"
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
   - Verify entity name exists in GraphQL schema
   - Check selected columns are valid fields
   - Review GraphQL query syntax

4. **Offset Issues**
   - Ensure offset field exists in selected columns
   - Verify field contains unique, sortable values
   - Check offset storage configuration

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
mvn test
```

### Code Coverage

```bash
mvn jacoco:report
```

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
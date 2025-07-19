# GraphQL Kafka Source Connector Requirements

## 1. Overview

This document outlines the requirements for developing a custom Kafka source connector that queries GraphQL APIs and publishes the results to Kafka topics. The connector will support configurable entity queries, pagination, column selection, and offset management.

## 2. Functional Requirements

### 2.1 Core Functionality
- **GraphQL Query Execution**: The connector must be able to execute GraphQL queries against a configurable GraphQL endpoint
- **Data Streaming**: Stream query results to designated Kafka topics
- **Offset Management**: Track and persist query offsets using Kafka Connect's offset storage mechanism
- **Configurable Polling**: Support configurable polling intervals for data retrieval

### 2.2 Configuration Parameters

The connector must support the following configuration parameters:

#### Required Configuration
- `graphql.endpoint.url` (string): The GraphQL API endpoint URL
- `entity.name` (string): The name of the GraphQL entity/type to query
- `result.size` (int): Maximum number of records to return per query (pagination size)
- `selected.columns` (list): Comma-separated list of fields/columns to include in the result

#### Optional Configuration
- `graphql.headers` (map): Custom HTTP headers for GraphQL requests (e.g., authentication)
- `polling.interval.ms` (long): Interval between queries in milliseconds (default: 30000)
- `topic.prefix` (string): Prefix for generated topic names (default: entity name)
- `offset.field` (string): Field name used for offset tracking (default: "id")
- `query.timeout.ms` (long): Timeout for GraphQL queries in milliseconds (default: 30000)
- `max.retries` (int): Maximum number of retry attempts for failed queries (default: 3)
- `retry.backoff.ms` (long): Backoff time between retry attempts (default: 1000)

### 2.3 Offset Management
- Use `offsetStorageReader().offset(partition)` to retrieve the last processed offset
- Support both string and numeric offset types
- Implement offset commits after successful message production
- Handle initial startup when no offset exists (start from beginning or latest)

### 2.4 GraphQL Query Generation
The connector must dynamically generate GraphQL queries based on configuration:

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

## 3. Technical Requirements

### 3.1 Architecture
- Extend `org.apache.kafka.connect.source.SourceConnector`
- Implement `org.apache.kafka.connect.source.SourceTask`
- Use Kafka Connect framework for configuration validation and task management

### 3.2 Dependencies
- Apache Kafka Connect API (2.8.0+)
- HTTP client library (e.g., Apache HttpClient or OkHttp)
- JSON processing library (e.g., Jackson or Gson)
- GraphQL query builder/parser library

### 3.3 Data Format
- **Output Format**: JSON messages containing the selected columns
- **Message Key**: Use the offset field value as the message key
- **Message Value**: JSON object with selected fields from GraphQL response
- **Headers**: Include metadata (query timestamp, entity name, etc.)

### 3.4 Error Handling
- **Connection Errors**: Implement retry logic with exponential backoff
- **GraphQL Errors**: Log errors and continue processing (configurable behavior)
- **Serialization Errors**: Log and skip malformed records
- **Offset Errors**: Fail fast to prevent data loss

### 3.5 Performance Requirements
- Support concurrent task execution for multiple partitions
- Implement efficient pagination handling
- Minimize memory footprint for large result sets
- Support backpressure handling

## 4. Implementation Specifications

### 4.1 Connector Class Structure

```java
public class GraphQLSourceConnector extends SourceConnector {
    // Configuration validation
    // Task configuration generation
    // Version information
}

public class GraphQLSourceTask extends SourceTask {
    // GraphQL client initialization
    // Polling logic implementation
    // Offset management
    // Record production
}
```

### 4.2 Configuration Validation
- Validate GraphQL endpoint accessibility
- Verify entity existence through introspection query
- Validate selected columns against entity schema
- Ensure required parameters are provided

### 4.3 Partition Strategy
- Single partition per connector instance (simple approach)
- Optional: Support multiple partitions based on entity subsets
- Use entity name and configuration hash as partition identifier

### 4.4 Offset Storage Format
```json
{
  "entity": "entityName",
  "last_cursor": "cursor_value",
  "last_id": "record_id",
  "timestamp": "2025-07-19T10:00:00Z"
}
```

## 5. Monitoring and Observability

### 5.1 Metrics
- Records processed per second
- Query execution time
- Error rates and types
- Offset lag
- Connection pool statistics

### 5.2 Logging
- Structured logging with correlation IDs
- Debug logs for query execution
- Error logs with full context
- Performance logs for query timing

## 6. Security Requirements

### 6.1 Authentication Support
- Bearer token authentication
- API key authentication
- Custom header-based authentication
- Support for token refresh mechanisms

### 6.2 SSL/TLS
- Support HTTPS endpoints
- Certificate validation
- Custom truststore configuration

## 7. Testing Requirements

### 7.1 Unit Tests
- Configuration validation tests
- GraphQL query generation tests
- Offset management tests
- Error handling scenarios

### 7.2 Integration Tests
- End-to-end connector functionality
- GraphQL endpoint integration
- Kafka topic production verification
- Offset persistence validation

### 7.3 Performance Tests
- Load testing with large datasets
- Memory usage profiling
- Concurrent execution testing

## 8. Deployment and Operations

### 8.1 Packaging
- Standard Kafka Connect plugin structure
- Include all required dependencies
- Provide configuration templates

### 8.2 Documentation
- Configuration parameter reference
- Setup and deployment guide
- Troubleshooting guide
- Performance tuning recommendations

## 9. Future Enhancements

### 9.1 Advanced Features
- Support for GraphQL mutations
- Schema evolution handling
- Multiple entity support in single connector
- Dynamic configuration updates

### 9.2 Performance Optimizations
- Connection pooling
- Batch query support
- Asynchronous processing
- Compressed data transfer

## 10. Acceptance Criteria

The connector is considered complete when it:
- Successfully connects to GraphQL endpoints
- Executes configurable queries with proper pagination
- Maintains offset state across restarts
- Handles errors gracefully with retry logic
- Produces well-formed messages to Kafka topics
- Passes all unit and integration tests
- Includes comprehensive documentation
# JMX Monitoring Guide

The GraphQL Source Connector now provides comprehensive JMX monitoring capabilities for operational visibility and troubleshooting.

## MBean Registration

The connector automatically registers a JMX MBean with the name:
```
com.example.graphqlconnector:type=GraphQLSourceTask,entity=<entity_name>
```

## Available Metrics

### Operational Status
- `ConnectorStatus`: Current connector status (HEALTHY, DEGRADED, UNHEALTHY, STOPPING)
- `IsHealthy`: Boolean indicating overall health
- `IsCircuitBreakerOpen`: Boolean indicating if circuit breaker is active
- `ConsecutiveFailures`: Number of consecutive failures
- `LastFailureTime`: Timestamp of last failure

### Performance Metrics
- `TotalRecordsProcessed`: Total number of records processed
- `TotalPollCycles`: Total number of poll cycles executed
- `TotalSuccessfulPollCycles`: Number of successful poll cycles
- `TotalFailedPollCycles`: Number of failed poll cycles
- `RecordsPerSecond`: Current processing rate
- `AverageQueryTimeMs`: Average GraphQL query execution time
- `LastQueryTimeMs`: Last query execution time

### GraphQL Specific Metrics
- `EntityName`: GraphQL entity being queried
- `GraphQLEndpoint`: GraphQL API endpoint URL
- `CurrentCursor`: Current pagination cursor
- `LastCommittedCursor`: Last successfully committed cursor
- `TotalGraphQLQueries`: Total number of GraphQL queries executed
- `TotalGraphQLErrors`: Total number of GraphQL query errors
- `TotalRetryAttempts`: Total number of retry attempts

### Resource Metrics
- `ActiveConnections`: Number of active HTTP connections
- `IdleConnections`: Number of idle HTTP connections
- `TotalBytesReceived`: Total bytes received from GraphQL API

### Error Tracking
- `LastErrorMessage`: Message from the last error
- `LastErrorTime`: Timestamp of last error
- `ErrorRate`: Percentage of failed operations

### Configuration Info
- `PollingIntervalMs`: Configured polling interval
- `ResultSize`: Configured batch size
- `SelectedColumns`: Configured fields being extracted

## Management Operations

### Reset Operations
- `resetMetrics()`: Reset all performance metrics
- `resetErrorTracking()`: Reset error counters and health status

### Health Summary
- `getHealthSummary()`: Get comprehensive status summary

## Monitoring with JConsole

1. Start JConsole: `jconsole`
2. Connect to your Kafka Connect process
3. Navigate to MBeans tab
4. Find: `com.example.graphqlconnector -> GraphQLSourceTask -> <entity_name>`

## Monitoring with JMX CLI Tools

### Using jmxterm
```bash
# Connect to process
echo "open <pid>" | java -jar jmxterm.jar

# List available beans
echo "beans" | java -jar jmxterm.jar -l <pid>

# Get specific metric
echo "get -b com.example.graphqlconnector:type=GraphQLSourceTask,entity=users TotalRecordsProcessed" | java -jar jmxterm.jar -l <pid>

# Get health summary
echo "run -b com.example.graphqlconnector:type=GraphQLSourceTask,entity=users getHealthSummary" | java -jar jmxterm.jar -l <pid>
```

### Using JMX REST APIs
Many monitoring tools can expose JMX metrics via REST APIs for integration with monitoring systems.

## Monitoring Recommendations

### Key Metrics to Monitor
1. **ConnectorStatus**: Alert if not "HEALTHY"
2. **ErrorRate**: Alert if > 5%
3. **ConsecutiveFailures**: Alert if > 5
4. **RecordsPerSecond**: Monitor for performance degradation
5. **IsCircuitBreakerOpen**: Alert if true

### Sample Alerting Rules
```yaml
# Prometheus alerting rules example
groups:
- name: graphql-connector
  rules:
  - alert: GraphQLConnectorUnhealthy
    expr: graphql_connector_is_healthy == 0
    for: 2m
    labels:
      severity: critical
    annotations:
      summary: "GraphQL Connector is unhealthy"

  - alert: GraphQLConnectorHighErrorRate
    expr: graphql_connector_error_rate > 0.05
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "GraphQL Connector error rate above 5%"
```

## Integration Examples

### Prometheus JMX Exporter
Configure the JMX exporter to scrape metrics:

```yaml
# jmx_exporter_config.yml
rules:
- pattern: 'com.example.graphqlconnector<type=GraphQLSourceTask, entity=(.*)><>(.*):'
  name: graphql_connector_$2
  labels:
    entity: $1
```

### Grafana Dashboard
Create dashboards with panels for:
- Records processed over time
- Query performance trends  
- Error rates and types
- Connection pool utilization
- Circuit breaker status

## Troubleshooting with JMX

### Performance Issues
1. Check `AverageQueryTimeMs` for slow queries
2. Monitor `ActiveConnections` for connection pool exhaustion
3. Review `ErrorRate` for API issues

### Data Issues
1. Compare `CurrentCursor` vs `LastCommittedCursor` for processing lag
2. Check `TotalRetryAttempts` for API reliability issues
3. Monitor `ConsecutiveFailures` for persistent problems

### Resource Issues
1. Track `TotalBytesReceived` for bandwidth usage
2. Monitor `IdleConnections` for connection efficiency
3. Use connection pool metrics for tuning

The JMX metrics provide comprehensive visibility into the connector's operation, enabling proactive monitoring and faster troubleshooting.
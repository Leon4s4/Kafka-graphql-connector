# Kafka GraphQL Connector

This project provides a simple Kafka Connect source connector that reads data from a GraphQL endpoint and publishes the results to Kafka topics.

## Building

Use Maven to build the connector jar:

```bash
mvn package
```

The resulting jar will be located in the `target` directory and can be deployed as a Kafka Connect plugin.

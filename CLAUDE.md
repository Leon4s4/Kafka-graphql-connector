# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Kafka Connect source connector that reads data from GraphQL endpoints and publishes the results to Kafka topics. The connector follows the standard Kafka Connect framework architecture.

## Build Commands

- **Build the project**: `mvn package`
- **Clean build**: `mvn clean package`
- **Run tests**: `mvn test`
- **Run tests with coverage**: `mvn jacoco:report`

The resulting JAR will be in the `target/` directory and can be deployed as a Kafka Connect plugin.

## Architecture

### Core Components

1. **GraphQLSourceConnector** (`src/main/java/com/example/graphqlconnector/GraphQLSourceConnector.java`): Main connector class that extends `SourceConnector`
2. **GraphQLSourceTask** (`src/main/java/com/example/graphqlconnector/GraphQLSourceTask.java`): Task implementation that performs the actual data polling from GraphQL endpoints
3. **GraphQLSourceConnectorConfig** (`src/main/java/com/example/graphqlconnector/GraphQLSourceConnectorConfig.java`): Configuration management with all connector settings

### Data Flow

- Tasks poll GraphQL endpoints using cursor-based pagination
- Results are converted to Kafka Connect `Struct` objects
- Offset management tracks the last processed cursor/ID
- Records are published to Kafka topics (with optional prefix)

### Key Configuration Parameters

- `graphql.endpoint.url`: GraphQL endpoint URL
- `entity.name`: GraphQL entity to query
- `selected.columns`: Fields to extract from GraphQL response
- `result.size`: Number of records per query (default: 100)
- `polling.interval.ms`: Delay between polls (default: 30s)
- `offset.field`: Field used for offset tracking (default: "id")

### Dependencies

- Apache Kafka Connect API (3.7.0)
- OkHttp for HTTP client (4.12.0)
- Jackson for JSON processing (2.17.1)
- Java 17+

## Important Implementation Details

- Uses cursor-based pagination with GraphQL edges/pageInfo pattern
- Implements safeguards against infinite loops (MAX_ITERATIONS = 1000)
- Supports custom headers for authentication
- Includes retry logic and timeout configuration
- Offset storage tracks both cursor and ID fields for resume capability
- Sleep occurs after poll completion to respect polling intervals
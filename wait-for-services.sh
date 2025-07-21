#!/bin/bash

# Wait for services to be ready and run integration tests

set -e

echo "Waiting for services to be ready..."

# Wait for Kafka
echo "Waiting for Kafka..."
while ! nc -z kafka 29092; do
  sleep 1
done
echo "Kafka is ready"

# Wait for GraphQL API
echo "Waiting for GraphQL API..."
while ! curl -f http://mock-graphql-api:4000/health > /dev/null 2>&1; do
  sleep 1
done
echo "GraphQL API is ready"

# Run integration tests
echo "Running integration tests..."
mvn failsafe:integration-test failsafe:verify

echo "Integration tests completed successfully!"
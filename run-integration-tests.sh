#!/bin/bash

# Integration Test Runner for GraphQL Kafka Connector
# This script starts the required services and runs integration tests

set -e

echo "🚀 Starting GraphQL Kafka Connector Integration Tests"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    print_error "Docker is not running. Please start Docker and try again."
    exit 1
fi

# Check if docker-compose is available
if ! command -v docker-compose &> /dev/null; then
    print_error "docker-compose is not installed. Please install it and try again."
    exit 1
fi

# Build the project first
print_status "Building the project..."
mvn clean compile -q
if [ $? -eq 0 ]; then
    print_success "Project built successfully"
else
    print_error "Failed to build project"
    exit 1
fi

# Start Docker services
print_status "Starting Docker services..."
docker-compose up -d

# Function to wait for a service to be healthy
wait_for_service() {
    local service_name=$1
    local health_url=$2
    local max_attempts=30
    local attempt=1
    
    print_status "Waiting for $service_name to be ready..."
    
    while [ $attempt -le $max_attempts ]; do
        if curl -f -s "$health_url" > /dev/null 2>&1; then
            print_success "$service_name is ready"
            return 0
        fi
        
        print_status "Attempt $attempt/$max_attempts: $service_name not ready yet..."
        sleep 2
        attempt=$((attempt + 1))
    done
    
    print_error "$service_name failed to start within expected time"
    return 1
}

# Wait for services to be ready
wait_for_service "Mock GraphQL API" "http://localhost:4000/health"
wait_for_service "Kafka Connect" "http://localhost:8083/connectors"

# Check Kafka broker
print_status "Checking Kafka broker..."
if docker-compose exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092 > /dev/null 2>&1; then
    print_success "Kafka broker is ready"
else
    print_error "Kafka broker is not ready"
    docker-compose logs kafka
    exit 1
fi

# Run integration tests
print_status "Running integration tests..."
mvn failsafe:integration-test -q

if [ $? -eq 0 ]; then
    print_success "Integration tests passed!"
else
    print_error "Integration tests failed!"
    
    print_status "Showing recent logs from services..."
    echo -e "\n${YELLOW}=== Mock GraphQL API Logs ===${NC}"
    docker-compose logs --tail=20 mock-graphql-api
    
    echo -e "\n${YELLOW}=== Kafka Connect Logs ===${NC}"
    docker-compose logs --tail=20 kafka-connect
    
    echo -e "\n${YELLOW}=== Kafka Logs ===${NC}"
    docker-compose logs --tail=20 kafka
    
    exit 1
fi

# Verify integration test results
mvn failsafe:verify -q
if [ $? -eq 0 ]; then
    print_success "All integration tests verified successfully!"
else
    print_error "Integration test verification failed!"
    exit 1
fi

# Optional: Show test coverage report
if [ "$1" == "--coverage" ]; then
    print_status "Generating test coverage report..."
    mvn jacoco:report -q
    print_success "Coverage report generated in target/site/jacoco/"
fi

# Optional: Keep services running
if [ "$1" == "--keep-running" ]; then
    print_success "Integration tests completed. Services are still running."
    print_status "Access points:"
    echo "  🌐 Mock GraphQL API: http://localhost:4000/graphql"
    echo "  📊 Kafka UI: http://localhost:8080"
    echo "  🔌 Kafka Connect: http://localhost:8083"
    echo ""
    print_status "To stop services run: docker-compose down"
else
    # Clean up
    print_status "Cleaning up Docker services..."
    docker-compose down -v
    print_success "Integration tests completed successfully! 🎉"
fi
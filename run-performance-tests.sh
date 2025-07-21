#!/bin/bash

set -e

echo "🚀 Starting Performance Benchmark Tests"
echo "======================================="

# Function to check if Docker Compose is available
check_docker_compose() {
    if ! command -v docker-compose &> /dev/null; then
        echo "❌ docker-compose is not installed or not in PATH"
        exit 1
    fi
}

# Function to wait for service to be ready
wait_for_service() {
    local url=$1
    local name=$2
    local max_attempts=30
    local attempt=0

    echo "⏳ Waiting for $name to be ready..."
    
    while [ $attempt -lt $max_attempts ]; do
        if curl -s -f "$url" > /dev/null 2>&1; then
            echo "✅ $name is ready"
            return 0
        fi
        
        attempt=$((attempt + 1))
        echo "   Attempt $attempt/$max_attempts..."
        sleep 2
    done
    
    echo "❌ $name failed to start within expected time"
    return 1
}

# Function to cleanup on exit
cleanup() {
    echo "🧹 Cleaning up services..."
    docker-compose -f docker-compose.test.yml down -v --remove-orphans 2>/dev/null || true
}

# Set up cleanup trap
trap cleanup EXIT INT TERM

# Check prerequisites
check_docker_compose

# Start test services
echo "🐳 Starting test environment..."
docker-compose -f docker-compose.test.yml up -d

# Wait for services to be ready
wait_for_service "http://localhost:4000/health" "Mock GraphQL API" || {
    echo "❌ Failed to start GraphQL API"
    docker-compose -f docker-compose.test.yml logs mock-graphql-api
    exit 1
}

wait_for_service "http://localhost:9092" "Kafka" || {
    echo "⚠️  Kafka might not be fully ready, but proceeding..."
}

# Build the project first
echo "🔨 Building project..."
mvn clean compile test-compile -q

# Run performance tests
echo "📊 Running Performance Benchmarks..."
echo "This may take several minutes..."
echo ""

# Create results directory
mkdir -p target/performance-results

# Run with performance profile
mvn test -Pperformance -Dtest="GraphQLConnectorPerformanceTest" \
    -Dmaven.test.skip=false \
    -DforkCount=1 \
    -DreuseForks=false

# Check if performance results were generated
if [ -f "target/performance-results.json" ]; then
    echo ""
    echo "📈 Performance Results:"
    echo "======================"
    
    # Display key metrics from JSON (requires jq, but fallback to cat if not available)
    if command -v jq &> /dev/null; then
        echo "⏱️  Test Duration: $(cat target/performance-results.json | jq -r '.duration // "N/A"')"
        echo "📊 Throughput: $(cat target/performance-results.json | jq -r '.results.throughput.throughputPerSecond // "N/A"') records/sec"
        echo "🧠 Memory Usage: $(cat target/performance-results.json | jq -r '.results.memoryUsage.memoryIncreaseMB // "N/A"') MB increase"
        echo "🔧 JMX Performance: $(cat target/performance-results.json | jq -r '.results.jmxPerformance | to_entries | .[0].value // "N/A"') ms avg"
        echo ""
        echo "📄 Full results saved to: target/performance-results.json"
    else
        echo "📄 Results saved to: target/performance-results.json"
        echo "💡 Install 'jq' for formatted result display"
    fi
else
    echo "⚠️  No performance results file found. Check test execution logs above."
fi

echo ""
echo "✅ Performance benchmarks completed!"
echo "📊 Results available in target/performance-results.json"
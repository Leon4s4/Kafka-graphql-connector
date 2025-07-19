#!/bin/bash

set -e

echo "🚀 Setting up Kafka GraphQL Connector Test Environment"
echo "=================================================="

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

# Check if Docker and Docker Compose are installed
check_requirements() {
    print_status "Checking requirements..."
    
    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed. Please install Docker first."
        exit 1
    fi
    
    if ! command -v docker-compose &> /dev/null; then
        print_error "Docker Compose is not installed. Please install Docker Compose first."
        exit 1
    fi
    
    print_success "Requirements check passed"
}

# Build the project
build_project() {
    print_status "Building the GraphQL Kafka Connector..."
    
    if [ ! -f "target/graphql-kafka-connector-0.1.0-SNAPSHOT.jar" ]; then
        print_status "JAR file not found. Building project..."
        mvn clean package -q
        if [ $? -ne 0 ]; then
            print_error "Maven build failed"
            exit 1
        fi
    else
        print_status "JAR file already exists, skipping build"
    fi
    
    print_success "Project built successfully"
}

# Start the Docker environment
start_environment() {
    print_status "Starting Docker environment..."
    
    # Stop any existing containers
    docker-compose down --remove-orphans 2>/dev/null || true
    
    # Build and start all services
    docker-compose up -d --build
    
    print_success "Docker environment started"
}

# Wait for services to be healthy
wait_for_services() {
    print_status "Waiting for services to be ready..."
    
    services=("zookeeper" "kafka" "schema-registry" "kafka-connect" "mock-graphql-api" "kafka-ui")
    
    for service in "${services[@]}"; do
        print_status "Waiting for $service to be healthy..."
        
        max_attempts=30
        attempt=1
        
        while [ $attempt -le $max_attempts ]; do
            if docker-compose ps $service | grep -q "healthy\|Up"; then
                print_success "$service is ready"
                break
            fi
            
            if [ $attempt -eq $max_attempts ]; then
                print_error "$service failed to start within timeout"
                print_status "Checking $service logs:"
                docker-compose logs --tail=20 $service
                exit 1
            fi
            
            echo -n "."
            sleep 5
            ((attempt++))
        done
    done
    
    print_success "All services are ready"
}

# Test GraphQL API
test_graphql_api() {
    print_status "Testing GraphQL API..."
    
    # Test health endpoint
    if curl -f -s "http://localhost:4000/health" > /dev/null; then
        print_success "GraphQL API health check passed"
    else
        print_error "GraphQL API health check failed"
        return 1
    fi
    
    # Test GraphQL query
    local query='{"query":"{ users(first: 5) { edges { node { id name email } cursor } pageInfo { hasNextPage endCursor } } }"}'
    local response=$(curl -s -X POST \
        -H "Content-Type: application/json" \
        -d "$query" \
        "http://localhost:4000/graphql")
    
    if echo "$response" | grep -q "users"; then
        print_success "GraphQL query test passed"
        echo "Sample response: $(echo "$response" | jq -r '.data.users.edges[0].node.name' 2>/dev/null || echo 'N/A')"
    else
        print_error "GraphQL query test failed"
        echo "Response: $response"
        return 1
    fi
}

# Deploy connectors
deploy_connectors() {
    print_status "Deploying GraphQL connectors..."
    
    # Wait a bit more for Kafka Connect to be fully ready
    sleep 10
    
    # Deploy users connector
    print_status "Deploying users connector..."
    local users_response=$(curl -s -X POST \
        -H "Content-Type: application/json" \
        -d @connectors/users-connector.json \
        "http://localhost:8083/connectors")
    
    if echo "$users_response" | grep -q "graphql-users-source"; then
        print_success "Users connector deployed successfully"
    else
        print_error "Failed to deploy users connector"
        echo "Response: $users_response"
    fi
    
    # Deploy products connector
    print_status "Deploying products connector..."
    local products_response=$(curl -s -X POST \
        -H "Content-Type: application/json" \
        -d @connectors/products-connector.json \
        "http://localhost:8083/connectors")
    
    if echo "$products_response" | grep -q "graphql-products-source"; then
        print_success "Products connector deployed successfully"
    else
        print_error "Failed to deploy products connector"
        echo "Response: $products_response"
    fi
}

# Check connector status
check_connector_status() {
    print_status "Checking connector status..."
    
    sleep 5
    
    # Check connectors list
    local connectors=$(curl -s "http://localhost:8083/connectors")
    echo "Active connectors: $connectors"
    
    # Check users connector status
    local users_status=$(curl -s "http://localhost:8083/connectors/graphql-users-source/status")
    print_status "Users connector status:"
    echo "$users_status" | jq '.' 2>/dev/null || echo "$users_status"
    
    # Check products connector status
    local products_status=$(curl -s "http://localhost:8083/connectors/graphql-products-source/status")
    print_status "Products connector status:"
    echo "$products_status" | jq '.' 2>/dev/null || echo "$products_status"
}

# Monitor topics
monitor_topics() {
    print_status "Monitoring Kafka topics..."
    
    sleep 10
    
    # List topics
    print_status "Available topics:"
    docker-compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list | grep graphql || print_warning "No GraphQL topics found yet"
    
    # Show some sample messages
    print_status "Sample messages from graphql_users topic (if available):"
    timeout 10s docker-compose exec kafka kafka-console-consumer \
        --bootstrap-server localhost:9092 \
        --topic graphql_users \
        --from-beginning \
        --max-messages 3 2>/dev/null || print_warning "No messages in graphql_users topic yet"
}

# Print access information
print_access_info() {
    print_success "🎉 Setup complete! Access your services:"
    echo ""
    echo "📊 Kafka UI (Monitor topics, connectors): http://localhost:8080"
    echo "🔌 Kafka Connect REST API: http://localhost:8083"
    echo "🌐 GraphQL API Playground: http://localhost:4000/graphql"
    echo "❤️  GraphQL API Health: http://localhost:4000/health"
    echo "📋 GraphQL API Info: http://localhost:4000/info"
    echo ""
    echo "📝 Useful commands:"
    echo "  - View connector status: curl http://localhost:8083/connectors/graphql-users-source/status"
    echo "  - List topics: docker-compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list"
    echo "  - Consume messages: docker-compose exec kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic graphql_users --from-beginning"
    echo "  - View logs: docker-compose logs -f kafka-connect"
    echo "  - Stop environment: docker-compose down"
    echo ""
    echo "🔍 The connectors will start pulling data from the GraphQL API every 10-15 seconds."
    echo "   Check the Kafka UI to see the data flowing into topics!"
}

# Main execution
main() {
    echo "Starting setup process..."
    
    check_requirements
    build_project
    start_environment
    wait_for_services
    test_graphql_api
    deploy_connectors
    check_connector_status
    monitor_topics
    print_access_info
    
    print_success "🚀 All done! Your Kafka GraphQL Connector test environment is ready!"
}

# Handle script interruption
trap 'print_error "Setup interrupted"; exit 1' INT TERM

# Run main function
main "$@"
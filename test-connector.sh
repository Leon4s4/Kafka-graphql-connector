#!/bin/bash

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

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

echo "🧪 Testing Kafka GraphQL Connector"
echo "=================================="

# Test 1: Check if connectors are running
print_status "Test 1: Checking connector status..."
connectors=$(curl -s "http://localhost:8083/connectors" | jq -r '.[]' 2>/dev/null || echo "")
if [[ $connectors == *"graphql-users-source"* ]] && [[ $connectors == *"graphql-products-source"* ]]; then
    print_success "Both connectors are deployed"
else
    print_error "Connectors not found. Available: $connectors"
    exit 1
fi

# Test 2: Check connector health
print_status "Test 2: Checking connector health..."
users_status=$(curl -s "http://localhost:8083/connectors/graphql-users-source/status" | jq -r '.connector.state' 2>/dev/null || echo "UNKNOWN")
products_status=$(curl -s "http://localhost:8083/connectors/graphql-products-source/status" | jq -r '.connector.state' 2>/dev/null || echo "UNKNOWN")

if [[ $users_status == "RUNNING" ]]; then
    print_success "Users connector is RUNNING"
else
    print_warning "Users connector status: $users_status"
fi

if [[ $products_status == "RUNNING" ]]; then
    print_success "Products connector is RUNNING"
else
    print_warning "Products connector status: $products_status"
fi

# Test 3: Check if topics exist
print_status "Test 3: Checking if topics are created..."
topics=$(docker-compose exec -T kafka kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null || echo "")
if [[ $topics == *"graphql_users"* ]]; then
    print_success "graphql_users topic exists"
else
    print_warning "graphql_users topic not found"
fi

if [[ $topics == *"graphql_products"* ]]; then
    print_success "graphql_products topic exists"
else
    print_warning "graphql_products topic not found"
fi

# Test 4: Check for messages in topics
print_status "Test 4: Checking for messages in topics..."

print_status "Checking graphql_users topic..."
users_messages=$(timeout 5s docker-compose exec -T kafka kafka-console-consumer \
    --bootstrap-server localhost:9092 \
    --topic graphql_users \
    --from-beginning \
    --max-messages 1 2>/dev/null || echo "")

if [[ -n $users_messages ]]; then
    print_success "Messages found in graphql_users topic"
    echo "Sample message: $(echo "$users_messages" | head -1 | jq -r '.payload' 2>/dev/null || echo "$users_messages" | head -1)"
else
    print_warning "No messages found in graphql_users topic yet"
fi

print_status "Checking graphql_products topic..."
products_messages=$(timeout 5s docker-compose exec -T kafka kafka-console-consumer \
    --bootstrap-server localhost:9092 \
    --topic graphql_products \
    --from-beginning \
    --max-messages 1 2>/dev/null || echo "")

if [[ -n $products_messages ]]; then
    print_success "Messages found in graphql_products topic"
    echo "Sample message: $(echo "$products_messages" | head -1 | jq -r '.payload' 2>/dev/null || echo "$products_messages" | head -1)"
else
    print_warning "No messages found in graphql_products topic yet"
fi

# Test 5: Test GraphQL API directly
print_status "Test 5: Testing GraphQL API directly..."
api_response=$(curl -s -X POST \
    -H "Content-Type: application/json" \
    -d '{"query":"{ users(first: 2) { edges { node { id name } } } }"}' \
    "http://localhost:4000/graphql")

if echo "$api_response" | grep -q "users"; then
    print_success "GraphQL API is responding correctly"
    user_count=$(echo "$api_response" | jq -r '.data.users.edges | length' 2>/dev/null || echo "unknown")
    print_status "Returned $user_count users from API"
else
    print_error "GraphQL API is not responding correctly"
    echo "Response: $api_response"
fi

# Test 6: Check connector logs for errors
print_status "Test 6: Checking for errors in connector logs..."
error_logs=$(docker-compose logs kafka-connect 2>/dev/null | grep -i "error\|exception\|failed" | tail -5 || echo "")
if [[ -n $error_logs ]]; then
    print_warning "Found some errors in logs:"
    echo "$error_logs"
else
    print_success "No recent errors found in connector logs"
fi

# Summary
echo ""
print_success "🎯 Testing Summary:"
echo "  - Connectors deployed and status checked"
echo "  - Topics existence verified"
echo "  - Message flow tested"
echo "  - GraphQL API connectivity confirmed"
echo "  - Error logs reviewed"
echo ""
print_status "💡 Tips:"
echo "  - If no messages are flowing, wait a few minutes as polling interval is 10-15 seconds"
echo "  - Check connector status: curl http://localhost:8083/connectors/graphql-users-source/status | jq"
echo "  - Monitor real-time: docker-compose logs -f kafka-connect"
echo "  - View in UI: http://localhost:8080"
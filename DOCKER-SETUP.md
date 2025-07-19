# Docker Test Environment for Kafka GraphQL Connector

This directory contains a complete Docker Compose setup to test the Kafka GraphQL Source Connector in a local environment.

## 🏗️ Architecture

The test environment includes:

- **Zookeeper**: Kafka coordination service
- **Kafka**: Message broker
- **Schema Registry**: Schema management for Kafka
- **Kafka Connect**: Runs our GraphQL connector
- **Mock GraphQL API**: Test API with sample data (users and products)
- **Kafka UI**: Web interface for monitoring topics and connectors

## 🚀 Quick Start

### Prerequisites

- Docker and Docker Compose installed
- Java 17+ and Maven (for building the connector)
- `jq` for JSON parsing (optional, for better output formatting)

### 1. Build and Start Everything

```bash
# Make the setup script executable
chmod +x setup-and-test.sh

# Run the complete setup
./setup-and-test.sh
```

This script will:
1. Build the GraphQL connector JAR
2. Start all Docker services
3. Wait for services to be healthy
4. Deploy the test connectors
5. Verify everything is working

### 2. Manual Setup (Alternative)

If you prefer manual control:

```bash
# Build the connector
mvn clean package

# Start the environment
docker-compose up -d

# Wait for services to start (check with docker-compose ps)
# Deploy connectors
curl -X POST -H "Content-Type: application/json" -d @connectors/users-connector.json http://localhost:8083/connectors
curl -X POST -H "Content-Type: application/json" -d @connectors/products-connector.json http://localhost:8083/connectors
```

## 🧪 Testing the Connector

### Run the Test Suite

```bash
./test-connector.sh
```

### Manual Testing

1. **Check connector status:**
```bash
curl http://localhost:8083/connectors/graphql-users-source/status | jq
```

2. **List Kafka topics:**
```bash
docker-compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list
```

3. **Consume messages:**
```bash
docker-compose exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic graphql_users \
  --from-beginning
```

4. **Test GraphQL API directly:**
```bash
curl -X POST -H "Content-Type: application/json" \
  -d '{"query":"{ users(first: 5) { edges { node { id name email } cursor } pageInfo { hasNextPage } } }"}' \
  http://localhost:4000/graphql
```

## 🌐 Web Interfaces

- **Kafka UI**: http://localhost:8080 - Monitor topics, connectors, and messages
- **GraphQL Playground**: http://localhost:4000/graphql - Test GraphQL queries
- **GraphQL Health**: http://localhost:4000/health - API health check
- **GraphQL Info**: http://localhost:4000/info - API information

## 📊 Test Data

The mock GraphQL API provides:
- **100 users** with fields: id, name, email, createdAt, status
- **50 products** with fields: id, name, description, price, category, inStock

Both entities support cursor-based pagination as expected by the connector.

## ⚙️ Configuration

### Connector Configurations

- **Users Connector** (`connectors/users-connector.json`):
  - Polls every 10 seconds
  - Fetches 10 records per query
  - Publishes to `graphql_users` topic

- **Products Connector** (`connectors/products-connector.json`):
  - Polls every 15 seconds
  - Fetches 5 records per query
  - Publishes to `graphql_products` topic

### Customizing the Test

You can modify the connector configurations in the `connectors/` directory and redeploy:

```bash
# Delete existing connector
curl -X DELETE http://localhost:8083/connectors/graphql-users-source

# Deploy updated configuration
curl -X POST -H "Content-Type: application/json" -d @connectors/users-connector.json http://localhost:8083/connectors
```

## 🔍 Monitoring and Troubleshooting

### View Logs

```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f kafka-connect
docker-compose logs -f mock-graphql-api
```

### Common Issues

1. **Connector fails to start:**
   - Check Kafka Connect logs: `docker-compose logs kafka-connect`
   - Verify JAR file exists: `ls -la target/graphql-kafka-connector-0.1.0-SNAPSHOT.jar`

2. **No messages in topics:**
   - Wait 1-2 minutes for initial polling
   - Check connector status for errors
   - Verify GraphQL API is accessible: `curl http://localhost:4000/health`

3. **GraphQL API not responding:**
   - Check API logs: `docker-compose logs mock-graphql-api`
   - Verify npm dependencies in the API container

### Reset Environment

```bash
# Stop and remove all containers, volumes, and networks
docker-compose down -v --remove-orphans

# Restart everything
./setup-and-test.sh
```

## 🛠️ Development

### Modifying the Connector

1. Make changes to the Java code
2. Rebuild: `mvn clean package`
3. Restart Kafka Connect: `docker-compose restart kafka-connect`
4. Redeploy connectors

### Adding New Test Scenarios

1. Modify `test-graphql-api/server.js` to add new data or endpoints
2. Create new connector configurations in `connectors/`
3. Update test scripts as needed

## 📚 Learn More

- Check the main [README.md](README.md) for connector documentation
- Review [requirements.md](requirements.md) for implementation details
- Explore the [config/](config/) directory for production configuration templates

## 🧹 Cleanup

```bash
# Stop all services
docker-compose down

# Remove all data (topics, offsets, etc.)
docker-compose down -v

# Remove images (if needed)
docker-compose down --rmi all
```
package com.example.graphqlconnector.integration;

import com.example.graphqlconnector.GraphQLSourceConnector;
import com.example.graphqlconnector.GraphQLSourceTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.storage.StringConverter;
import org.junit.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for GraphQL Source Connector.
 * 
 * These tests require Docker Compose to be running with:
 * - Kafka broker
 * - Mock GraphQL API
 * 
 * Run: docker-compose up -d before executing these tests
 */
public class GraphQLSourceConnectorIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GraphQLSourceConnectorIntegrationTest.class);
    private static final String KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String GRAPHQL_ENDPOINT = "http://localhost:4000/graphql";
    private static final String TOPIC_NAME = "integration-test-users";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private GraphQLSourceTask task;
    private KafkaConsumer<String, String> consumer;
    private HttpClient httpClient;

    @BeforeClass
    public static void setUpClass() throws Exception {
        // Wait for services to be ready
        waitForService("http://localhost:4000/health", "Mock GraphQL API");
        log.info("All services are ready for integration tests");
    }

    @Before
    public void setUp() {
        task = new GraphQLSourceTask();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        
        // Set up Kafka consumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVERS);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "integration-test-group-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        
        consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(TOPIC_NAME));
        
        log.info("Integration test setup completed");
    }

    @After
    public void tearDown() {
        if (task != null) {
            try {
                task.stop();
            } catch (Exception e) {
                log.warn("Error stopping task", e);
            }
        }
        if (consumer != null) {
            consumer.close();
        }
        if (httpClient != null) {
            // HttpClient doesn't need explicit cleanup in Java 11+
        }
        log.info("Integration test teardown completed");
    }

    @Test
    public void testBasicDataIngestion() throws Exception {
        log.info("Starting basic data ingestion test");
        
        // Configure and start the connector task
        Map<String, String> props = createBasicConnectorConfig();
        task.start(props);

        // Poll for records
        List<SourceRecord> records = task.poll();
        assertNotNull("Records should not be null", records);
        assertTrue("Should receive at least some records", records.size() > 0);
        
        // Verify record structure
        SourceRecord firstRecord = records.get(0);
        assertEquals("Topic name should match", TOPIC_NAME, firstRecord.topic());
        assertNotNull("Record value should not be null", firstRecord.value());
        assertNotNull("Record key should not be null", firstRecord.key());
        
        log.info("Received {} records from GraphQL API", records.size());
        
        // Verify record content
        Object value = firstRecord.value();
        assertTrue("Value should be a Struct", value instanceof org.apache.kafka.connect.data.Struct);
        
        org.apache.kafka.connect.data.Struct struct = (org.apache.kafka.connect.data.Struct) value;
        assertNotNull("ID field should exist", struct.get("id"));
        assertNotNull("Name field should exist", struct.get("name"));
        assertNotNull("Email field should exist", struct.get("email"));
        
        log.info("Basic data ingestion test completed successfully");
    }

    @Test
    public void testPaginationHandling() throws Exception {
        log.info("Starting pagination handling test");
        
        Map<String, String> props = createBasicConnectorConfig();
        props.put("result.size", "5"); // Small page size to test pagination
        task.start(props);

        int totalRecords = 0;
        int pollCycles = 0;
        final int maxPollCycles = 10;
        
        // Poll multiple times to test pagination
        while (pollCycles < maxPollCycles) {
            List<SourceRecord> records = task.poll();
            if (records.isEmpty()) {
                break;
            }
            
            totalRecords += records.size();
            pollCycles++;
            
            log.info("Poll cycle {}: received {} records, total so far: {}", 
                    pollCycles, records.size(), totalRecords);
            
            // Small delay between polls
            Thread.sleep(100);
        }
        
        assertTrue("Should have received multiple records across poll cycles", totalRecords > 5);
        assertTrue("Should have executed multiple poll cycles", pollCycles > 1);
        
        log.info("Pagination test completed: {} records in {} poll cycles", totalRecords, pollCycles);
    }

    @Test
    public void testErrorHandlingAndRecovery() throws Exception {
        log.info("Starting error handling and recovery test");
        
        // Configure with invalid endpoint to trigger errors
        Map<String, String> props = createBasicConnectorConfig();
        props.put("graphql.endpoint.url", "http://localhost:9999/invalid");
        props.put("max.retries", "2");
        props.put("retry.backoff.ms", "1000");
        
        task.start(props);

        // Poll should handle errors gracefully
        List<SourceRecord> records = task.poll();
        assertNotNull("Records should not be null even on error", records);
        assertTrue("Should return empty list on connection error", records.isEmpty());
        
        // Verify task is still healthy after errors
        assertNotNull("Task should still be responsive", task.version());
        
        log.info("Error handling test completed successfully");
    }

    @Test
    public void testCircuitBreakerFunctionality() throws Exception {
        log.info("Starting circuit breaker functionality test");
        
        Map<String, String> props = createBasicConnectorConfig();
        props.put("graphql.endpoint.url", "http://localhost:9999/invalid");
        props.put("max.retries", "1");
        props.put("retry.backoff.ms", "500");
        
        task.start(props);

        // Generate multiple failures to trigger circuit breaker
        int consecutiveFailures = 0;
        for (int i = 0; i < 12; i++) { // More than MAX_CONSECUTIVE_FAILURES (10)
            List<SourceRecord> records = task.poll();
            assertTrue("Should return empty records on failure", records.isEmpty());
            consecutiveFailures++;
            
            if (consecutiveFailures > 10) {
                // Circuit breaker should be open now
                break;
            }
        }
        
        log.info("Generated {} consecutive failures to test circuit breaker", consecutiveFailures);
        
        // Verify circuit breaker is working (this requires JMX or additional instrumentation)
        // For now, we verify that the task continues to return empty results quickly
        long startTime = System.currentTimeMillis();
        List<SourceRecord> records = task.poll();
        long endTime = System.currentTimeMillis();
        
        assertTrue("Should return empty records when circuit breaker is open", records.isEmpty());
        assertTrue("Circuit breaker should cause quick failure (< 2 seconds)", 
                (endTime - startTime) < 2000);
        
        log.info("Circuit breaker test completed successfully");
    }

    @Test
    public void testJMXMetricsAvailability() throws Exception {
        log.info("Starting JMX metrics availability test");
        
        Map<String, String> props = createBasicConnectorConfig();
        task.start(props);

        // Poll to generate some metrics
        List<SourceRecord> records = task.poll();
        
        // The task implements GraphQLSourceTaskMBean interface
        assertTrue("Task should implement MBean interface", 
                task instanceof com.example.graphqlconnector.GraphQLSourceTaskMBean);
        
        com.example.graphqlconnector.GraphQLSourceTaskMBean mbean = 
                (com.example.graphqlconnector.GraphQLSourceTaskMBean) task;
        
        // Test basic metrics
        assertNotNull("Connector status should be available", mbean.getConnectorStatus());
        assertTrue("Total poll cycles should be >= 1", mbean.getTotalPollCycles() >= 1);
        assertNotNull("Entity name should be available", mbean.getEntityName());
        assertNotNull("GraphQL endpoint should be available", mbean.getGraphQLEndpoint());
        
        // Test health summary
        String healthSummary = mbean.getHealthSummary();
        assertNotNull("Health summary should be available", healthSummary);
        assertTrue("Health summary should contain status info", 
                healthSummary.contains("Status:"));
        
        log.info("JMX metrics test completed successfully");
        log.info("Health summary: {}", healthSummary);
    }

    @Test
    public void testAuthenticationHeaders() throws Exception {
        log.info("Starting authentication headers test");
        
        Map<String, String> props = createBasicConnectorConfig();
        props.put("graphql.headers", "Authorization:Bearer test-token,X-Custom-Header:test-value");
        
        task.start(props);
        
        // This test verifies that headers are properly configured
        // The actual authentication test would require a GraphQL endpoint that validates headers
        // For now, we verify the task starts successfully with headers configured
        List<SourceRecord> records = task.poll();
        assertNotNull("Records should not be null with headers configured", records);
        
        log.info("Authentication headers test completed successfully");
    }

    @Test 
    public void testCustomGraphQLVariables() throws Exception {
        log.info("Starting custom GraphQL variables test");
        
        Map<String, String> props = createBasicConnectorConfig();
        props.put("graphql.variables", "{\"status\": \"ACTIVE\"}");
        
        task.start(props);
        
        List<SourceRecord> records = task.poll();
        assertNotNull("Records should not be null with custom variables", records);
        
        log.info("Custom GraphQL variables test completed successfully");
    }

    @Test
    public void testOffsetManagementAndResumption() throws Exception {
        log.info("Starting offset management and resumption test");
        
        Map<String, String> props = createBasicConnectorConfig();
        props.put("result.size", "3"); // Small batches for testing
        
        // First run - get some records
        task.start(props);
        List<SourceRecord> firstBatch = task.poll();
        assertTrue("First batch should have records", !firstBatch.isEmpty());
        
        // Simulate offset commit
        if (!firstBatch.isEmpty()) {
            task.commitRecord(firstBatch.get(firstBatch.size() - 1));
        }
        
        // Stop and restart task
        task.stop();
        task = new GraphQLSourceTask();
        task.start(props);
        
        // Second run should continue from where we left off
        List<SourceRecord> secondBatch = task.poll();
        assertNotNull("Second batch should not be null", secondBatch);
        
        // We can't easily verify exact offset continuation without mocking offset storage,
        // but we can verify the task handles resumption gracefully
        log.info("Offset management test completed: First batch: {}, Second batch: {}", 
                firstBatch.size(), secondBatch.size());
    }

    // Helper methods

    private Map<String, String> createBasicConnectorConfig() {
        Map<String, String> props = new HashMap<>();
        props.put("graphql.endpoint.url", GRAPHQL_ENDPOINT);
        props.put("graphql.query", 
                "query GetUsers($first: Int!, $after: String) { " +
                "users(first: $first, after: $after) { " +
                "edges { node { id name email createdAt status } cursor } " +
                "pageInfo { hasNextPage endCursor } } }");
        props.put("data.path", "users.edges[*].node");
        props.put("pagination.cursor.path", "users.pageInfo.endCursor");
        props.put("pagination.hasmore.path", "users.pageInfo.hasNextPage");
        props.put("record.key.path", "id");
        props.put("result.size", "10");
        props.put("polling.interval.ms", "1000");
        props.put("kafka.topic.name", TOPIC_NAME);
        props.put("query.timeout.ms", "5000");
        props.put("max.retries", "3");
        props.put("retry.backoff.ms", "1000");
        
        return props;
    }

    private static void waitForService(String healthEndpoint, String serviceName) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        
        int maxAttempts = 30;
        int attempt = 0;
        
        while (attempt < maxAttempts) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(healthEndpoint))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                
                HttpResponse<String> response = client.send(request, 
                        HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    log.info("{} is ready (attempt {})", serviceName, attempt + 1);
                    return;
                }
            } catch (Exception e) {
                // Service not ready yet
            }
            
            attempt++;
            log.info("Waiting for {} to be ready... (attempt {}/{})", 
                    serviceName, attempt, maxAttempts);
            Thread.sleep(2000);
        }
        
        throw new RuntimeException(serviceName + " is not ready after " + maxAttempts + " attempts");
    }

    // Utility assertion methods since we might not have JUnit assertions
    private void assertNotNull(String message, Object object) {
        if (object == null) {
            throw new AssertionError(message);
        }
    }

    private void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void assertEquals(String message, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + " but was " + actual);
        }
    }
}
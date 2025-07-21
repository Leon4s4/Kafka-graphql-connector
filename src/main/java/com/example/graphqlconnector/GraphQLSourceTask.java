package com.example.graphqlconnector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.PathNotFoundException;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GraphQLSourceTask extends SourceTask implements GraphQLSourceTaskMBean {

    private static final Logger log = LoggerFactory.getLogger(GraphQLSourceTask.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final int MAX_ITERATIONS = 1000;
    private static final int MAX_CONSECUTIVE_FAILURES = 10;
    private static final long ERROR_BACKOFF_MS = 5000; // 5 seconds
    private static final long CIRCUIT_BREAKER_TIMEOUT_MS = 300000; // 5 minutes

    private GraphQLSourceConnectorConfig config;
    private OkHttpClient client;
    private ObjectMapper mapper = new ObjectMapper();
    private String nextCursor;
    private String lastCommittedCursor;
    private long recordsProcessed = 0;
    private Instant lastQueryTime;
    private Map<String, Object> sourcePartition;
    private int consecutiveFailures = 0;
    private Instant lastFailureTime;
    private boolean isHealthy = true;
    private volatile boolean isShuttingDown = false;
    
    // JMX Metrics
    private final AtomicLong totalPollCycles = new AtomicLong(0);
    private final AtomicLong successfulPollCycles = new AtomicLong(0);
    private final AtomicLong failedPollCycles = new AtomicLong(0);
    private final AtomicLong totalGraphQLQueries = new AtomicLong(0);
    private final AtomicLong totalGraphQLErrors = new AtomicLong(0);
    private final AtomicLong totalRetryAttempts = new AtomicLong(0);
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong queryTimeAccumulator = new AtomicLong(0);
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
    private final AtomicReference<Instant> lastErrorTime = new AtomicReference<>();
    private volatile Instant metricsStartTime = Instant.now();
    private volatile ObjectName mbeanName;
    private volatile MBeanServer mbeanServer;

    @Override
    public String version() {
        return "0.1.0";
    }

    @Override
    public void start(Map<String, String> props) {
        log.info("Starting GraphQL Source Task");
        try {
            this.config = new GraphQLSourceConnectorConfig(props);
            validateConfiguration();
            initializeHttpClient();
            loadOffset();
            registerMBean();
            log.info("GraphQL Source Task started successfully for entity: {}", config.entityName());
        } catch (Exception e) {
            log.error("Failed to start GraphQL Source Task", e);
            throw new RuntimeException("Task startup failed", e);
        }
    }

    private void validateConfiguration() {
        log.debug("Validating configuration");
        
        if (config.endpoint() == null || config.endpoint().trim().isEmpty()) {
            throw new IllegalArgumentException("GraphQL endpoint URL is required");
        }
        
        if (config.entityName() == null || config.entityName().trim().isEmpty()) {
            throw new IllegalArgumentException("Entity name is required");
        }
        
        if (config.graphqlQuery() == null || config.graphqlQuery().trim().isEmpty()) {
            throw new IllegalArgumentException("GraphQL query is required");
        }
        
        if (config.dataPath() == null || config.dataPath().trim().isEmpty()) {
            throw new IllegalArgumentException("Data path is required");
        }
        
        // Validate that query contains pagination variables
        String query = config.graphqlQuery();
        if (!query.contains("$first") || !query.contains("$after")) {
            log.warn("GraphQL query should contain $first and $after variables for pagination");
        }
        
        log.debug("Configuration validation passed");
    }

    private void initializeHttpClient() {
        ConnectionPool connectionPool = new ConnectionPool(10, 5, TimeUnit.MINUTES);
        
        this.client = new OkHttpClient.Builder()
                .connectionPool(connectionPool)
                .connectTimeout(Duration.ofMillis(config.queryTimeoutMs()))
                .readTimeout(Duration.ofMillis(config.queryTimeoutMs()))
                .writeTimeout(Duration.ofMillis(config.queryTimeoutMs()))
                .retryOnConnectionFailure(true)
                .build();
                
        log.debug("HTTP client initialized with timeout: {}ms", config.queryTimeoutMs());
    }

    private void loadOffset() {
        this.sourcePartition = Collections.singletonMap("entity", config.entityName());
        Map<String, Object> offset = context.offsetStorageReader().offset(sourcePartition);
        
        if (offset != null) {
            this.nextCursor = (String) offset.get("last_cursor");
            this.lastCommittedCursor = this.nextCursor;
            log.info("Loaded offset - cursor: {}, last_id: {}, timestamp: {}", 
                    nextCursor, offset.get("last_id"), offset.get("timestamp"));
        } else {
            this.nextCursor = null;
            this.lastCommittedCursor = null;
            log.info("No existing offset found, starting from beginning");
        }
    }

    private void registerMBean() {
        try {
            this.mbeanServer = ManagementFactory.getPlatformMBeanServer();
            this.mbeanName = new ObjectName(
                "com.example.graphqlconnector:type=GraphQLSourceTask,entity=" + config.entityName());
            
            if (!mbeanServer.isRegistered(mbeanName)) {
                mbeanServer.registerMBean(this, mbeanName);
                log.info("Registered JMX MBean: {}", mbeanName);
            } else {
                log.warn("MBean already registered: {}", mbeanName);
            }
        } catch (Exception e) {
            log.error("Failed to register JMX MBean", e);
        }
    }

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        String correlationId = generateCorrelationId();
        log.debug("Starting poll cycle with correlation ID: {}", correlationId);
        
        totalPollCycles.incrementAndGet();
        
        // Circuit breaker check
        if (!isHealthy && shouldStayInCircuitBreaker()) {
            log.warn("Circuit breaker open, skipping poll cycle. Consecutive failures: {}", consecutiveFailures);
            Thread.sleep(ERROR_BACKOFF_MS);
            return Collections.emptyList();
        }
        
        try {
            List<SourceRecord> records = pollRecords(correlationId);
            
            // Reset failure tracking on success
            if (!records.isEmpty() || consecutiveFailures > 0) {
                resetFailureTracking();
            }
            
            successfulPollCycles.incrementAndGet();
            return records;
            
        } catch (InterruptedException e) {
            log.info("Poll interrupted, shutting down gracefully");
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            return handlePollFailure(e, correlationId);
        } finally {
            Thread.sleep(config.pollingIntervalMs());
        }
    }
    
    private List<SourceRecord> pollRecords(String correlationId) throws Exception {
        List<SourceRecord> records = new ArrayList<>();
        boolean hasNext = true;
        String after = nextCursor;
        int iterationCount = 0;
        
        while (hasNext && iterationCount < MAX_ITERATIONS && !isShuttingDown) {
            iterationCount++;
            log.debug("Poll iteration {} with cursor: {}", iterationCount, after);
            
            GraphQLQueryResult result = executeQueryWithRetry(after, correlationId);
            
            // Process nodes in this batch
            for (JsonNode node : result.nodes) {
                SourceRecord record = createSourceRecord(node, result.endCursor, correlationId);
                records.add(record);
                recordsProcessed++;
            }
            
            hasNext = result.hasNextPage;
            after = result.endCursor;
            
            log.debug("Processed {} nodes in iteration {}", result.nodes.size(), iterationCount);
        }
        
        if (iterationCount >= MAX_ITERATIONS) {
            log.warn("Reached maximum iterations ({}), stopping poll cycle", MAX_ITERATIONS);
        }
        
        // Only update nextCursor if we successfully created records
        if (!records.isEmpty()) {
            nextCursor = after;
            log.info("Poll cycle completed. Records: {}, Total processed: {}, Next cursor: {}", 
                    records.size(), recordsProcessed, nextCursor);
        } else {
            log.info("Poll cycle completed with no records. Cursor unchanged: {}", nextCursor);
        }
        
        return records;
    }
    
    private List<SourceRecord> handlePollFailure(Exception e, String correlationId) throws InterruptedException {
        incrementFailureCount();
        failedPollCycles.incrementAndGet();
        lastErrorMessage.set(e.getMessage());
        lastErrorTime.set(Instant.now());
        
        // Log appropriate level based on failure type and count
        if (isRetriableError(e)) {
            log.warn("Retriable error during poll cycle (attempt {}/{}): {}", 
                    consecutiveFailures, MAX_CONSECUTIVE_FAILURES, e.getMessage());
        } else {
            log.error("Non-retriable error during poll cycle: {}", e.getMessage(), e);
        }
        
        // Reset cursor to last committed position to avoid data loss
        nextCursor = lastCommittedCursor;
        log.debug("Reset cursor to last committed position: {}", lastCommittedCursor);
        
        // Check if we should open circuit breaker
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            openCircuitBreaker();
        }
        
        // For retriable errors, return empty list and continue
        // For non-retriable errors, still return empty list but log as error
        if (isRetriableError(e)) {
            // Apply exponential backoff for retriable errors
            long backoffMs = Math.min(ERROR_BACKOFF_MS * consecutiveFailures, 60000);
            log.debug("Applying backoff of {}ms before next poll", backoffMs);
            Thread.sleep(backoffMs);
            return Collections.emptyList();
        } else {
            // For non-retriable errors, we still return empty list but mark as unhealthy
            isHealthy = false;
            return Collections.emptyList();
        }
    }

    private GraphQLQueryResult executeQueryWithRetry(String after, String correlationId) throws IOException {
        int attempt = 0;
        long backoffMs = config.retryBackoffMs();
        
        while (attempt < config.maxRetries()) {
            try {
                Instant queryStart = Instant.now();
                GraphQLQueryResult result = executeQuery(after, correlationId);
                lastQueryTime = Instant.now();
                
                long queryDuration = Duration.between(queryStart, lastQueryTime).toMillis();
                log.debug("Query executed successfully in {}ms (attempt {})", queryDuration, attempt + 1);
                
                // Update metrics
                totalGraphQLQueries.incrementAndGet();
                queryTimeAccumulator.addAndGet(queryDuration);
                
                return result;
                
            } catch (IOException e) {
                attempt++;
                totalRetryAttempts.incrementAndGet();
                totalGraphQLErrors.incrementAndGet();
                log.warn("Query attempt {} failed for correlation ID: {} - {}", 
                        attempt, correlationId, e.getMessage());
                
                if (attempt >= config.maxRetries()) {
                    log.error("All {} retry attempts exhausted for correlation ID: {}", 
                            config.maxRetries(), correlationId, e);
                    throw e;
                }
                
                try {
                    log.debug("Backing off for {}ms before retry", backoffMs);
                    Thread.sleep(backoffMs);
                    backoffMs *= 2; // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry backoff", ie);
                }
            }
        }
        
        throw new IOException("Should not reach here");
    }

    private GraphQLQueryResult executeQuery(String after, String correlationId) throws IOException {
        String query = config.graphqlQuery();
        Map<String, Object> variables = new HashMap<>(config.graphqlVariables());
        variables.put("first", config.resultSize());
        if (after != null) {
            variables.put("after", after);
        }

        String requestBody = mapper.writeValueAsString(Map.of(
                "query", query,
                "variables", variables
        ));

        Request.Builder requestBuilder = new Request.Builder()
                .url(config.endpoint())
                .post(RequestBody.create(requestBody, MediaType.parse("application/json; charset=utf-8")))
                .addHeader(CORRELATION_ID_HEADER, correlationId);

        for (Map.Entry<String, String> header : config.headers().entrySet()) {
            requestBuilder.addHeader(header.getKey(), header.getValue());
        }

        Request request = requestBuilder.build();
        log.debug("Executing GraphQL query: {}", query);

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorMsg = String.format("HTTP %d: %s", response.code(), response.message());
                log.error("GraphQL request failed: {}", errorMsg);
                throw new IOException(errorMsg);
            }

            String responseBody = response.body().string();
            totalBytesReceived.addAndGet(responseBody.length());
            JsonNode responseNode = mapper.readTree(responseBody);
            
            if (responseNode.has("errors")) {
                JsonNode errors = responseNode.get("errors");
                log.error("GraphQL errors in response: {}", errors);
                throw new IOException("GraphQL errors: " + errors.toString());
            }

            return parseGraphQLResponse(responseNode);
        }
    }

    private GraphQLQueryResult parseGraphQLResponse(JsonNode responseNode) {
        try {
            DocumentContext document = JsonPath.parse(responseNode.toString());
            
            // Extract data records using configured path
            List<Object> dataObjects = document.read(config.dataPath());
            List<JsonNode> nodes = new ArrayList<>();
            
            for (Object dataObj : dataObjects) {
                JsonNode node = mapper.convertValue(dataObj, JsonNode.class);
                nodes.add(node);
            }
            
            // Extract pagination cursor
            String endCursor = null;
            try {
                endCursor = document.read(config.paginationCursorPath());
            } catch (PathNotFoundException e) {
                log.debug("No cursor found at path: {}", config.paginationCursorPath());
            }
            
            // Extract hasMore flag
            boolean hasNext = false;
            try {
                hasNext = document.read(config.paginationHasMorePath());
            } catch (PathNotFoundException e) {
                log.debug("No hasMore flag found at path: {}", config.paginationHasMorePath());
                // If no hasMore flag, assume no more pages if no records or cursor
                hasNext = !nodes.isEmpty() && endCursor != null;
            }
            
            log.debug("Parsed {} records, cursor: {}, hasNext: {}", nodes.size(), endCursor, hasNext);
            return new GraphQLQueryResult(nodes, endCursor, hasNext);
            
        } catch (Exception e) {
            log.error("Failed to parse GraphQL response with JSONPath", e);
            throw new RuntimeException("Response parsing failed", e);
        }
    }

    private SourceRecord createSourceRecord(JsonNode node, String endCursor, String correlationId) {
        // Create offset information that will be committed atomically with the record
        Map<String, Object> sourceOffset = new HashMap<>();
        sourceOffset.put("last_cursor", endCursor);
        sourceOffset.put("timestamp", Instant.now().toString());
        
        String recordKey = null;
        try {
            // Use JSONPath to extract record key
            DocumentContext nodeContext = JsonPath.parse(node.toString());
            recordKey = nodeContext.read(config.recordKeyPath());
            sourceOffset.put("last_id", recordKey);
        } catch (PathNotFoundException e) {
            log.debug("No record key found at path: {}", config.recordKeyPath());
        }
        
        // Add additional metadata for offset verification
        sourceOffset.put("entity", config.entityName());
        sourceOffset.put("correlation_id", correlationId);
        
        String topic = buildTopicName();
        Struct value = buildStruct(node);
        Schema valueSchema = value.schema();
        
        ConnectHeaders headers = new ConnectHeaders();
        headers.addString("entity", config.entityName());
        headers.addString("timestamp", Instant.now().toString());
        headers.addString("correlation_id", correlationId);
        if (recordKey != null) {
            headers.addString("record_id", recordKey);
        }
        
        return new SourceRecord(
                sourcePartition,
                sourceOffset,
                topic,
                null,
                Schema.OPTIONAL_STRING_SCHEMA,
                recordKey,
                valueSchema,
                value,
                null,
                headers
        );
    }

    private Struct buildStruct(JsonNode node) {
        SchemaBuilder schemaBuilder = SchemaBuilder.struct()
                .name(config.entityName() + "_record");
        
        Map<String, Object> fieldValues = new HashMap<>();
        
        // Dynamically process all fields in the JSON node
        node.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode fieldNode = entry.getValue();
            
            // Add field to schema
            schemaBuilder.field(fieldName, Schema.OPTIONAL_STRING_SCHEMA);
            
            // Convert field value
            String fieldValue = null;
            if (!fieldNode.isNull()) {
                if (fieldNode.isValueNode()) {
                    fieldValue = fieldNode.asText();
                } else {
                    // For complex objects, serialize as JSON string
                    fieldValue = fieldNode.toString();
                }
            }
            fieldValues.put(fieldName, fieldValue);
        });
        
        Schema schema = schemaBuilder.build();
        Struct struct = new Struct(schema);
        
        for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
            struct.put(entry.getKey(), entry.getValue());
        }
        
        return struct;
    }

    private String buildTopicName() {
        String topicName = config.kafkaTopicName();
        if (topicName == null || topicName.trim().isEmpty()) {
            throw new IllegalArgumentException("kafka.topic.name is required and cannot be empty");
        }
        return topicName.trim();
    }


    private boolean shouldStayInCircuitBreaker() {
        if (lastFailureTime == null) {
            return false;
        }
        return Instant.now().isBefore(lastFailureTime.plusMillis(CIRCUIT_BREAKER_TIMEOUT_MS));
    }
    
    private void resetFailureTracking() {
        if (consecutiveFailures > 0) {
            log.info("Resetting failure tracking after successful poll. Previous failures: {}", consecutiveFailures);
            consecutiveFailures = 0;
            lastFailureTime = null;
            isHealthy = true;
        }
    }
    
    private void incrementFailureCount() {
        consecutiveFailures++;
        lastFailureTime = Instant.now();
        log.debug("Incremented failure count to: {}", consecutiveFailures);
    }
    
    private void openCircuitBreaker() {
        isHealthy = false;
        log.error("Circuit breaker opened after {} consecutive failures. Will retry after {}ms", 
                consecutiveFailures, CIRCUIT_BREAKER_TIMEOUT_MS);
    }
    
    private boolean isRetriableError(Exception e) {
        // Network/IO errors are typically retriable
        if (e instanceof IOException || 
            e.getCause() instanceof IOException) {
            return true;
        }
        
        // Timeout errors are retriable
        if (e instanceof java.net.SocketTimeoutException ||
            e.getCause() instanceof java.net.SocketTimeoutException) {
            return true;
        }
        
        // Some HTTP errors are retriable (5xx server errors)
        String message = e.getMessage();
        if (message != null) {
            // HTTP 5xx errors are typically retriable
            if (message.contains("HTTP 50") || message.contains("HTTP 52") || message.contains("HTTP 53")) {
                return true;
            }
            // Rate limiting is retriable
            if (message.contains("HTTP 429") || message.toLowerCase().contains("rate limit")) {
                return true;
            }
        }
        
        // Everything else is non-retriable (4xx client errors, configuration issues, etc.)
        return false;
    }
    
    private String generateCorrelationId() {
        return config.entityName() + "-" + System.currentTimeMillis() + "-" + 
               Thread.currentThread().getId();
    }

    @Override
    public void commit() throws InterruptedException {
        // This method is called by Kafka Connect when offsets have been successfully committed
        // Update our tracking cursor to the current position
        if (nextCursor != null) {
            lastCommittedCursor = nextCursor;
            log.debug("Offset committed successfully. Last committed cursor: {}", lastCommittedCursor);
        }
    }

    @Override
    public void commitRecord(SourceRecord record) throws InterruptedException {
        // This method is called for each successfully committed record
        // Update our committed cursor based on the record's offset
        if (record != null && record.sourceOffset() != null) {
            String recordCursor = (String) record.sourceOffset().get("last_cursor");
            if (recordCursor != null) {
                lastCommittedCursor = recordCursor;
                log.trace("Record committed with cursor: {}", recordCursor);
            }
        }
    }

    @Override
    public void stop() {
        log.info("Stopping GraphQL Source Task. Total records processed: {}", recordsProcessed);
        isShuttingDown = true;
        
        unregisterMBean();
        cleanupResources();
        
        log.info("GraphQL Source Task stopped successfully");
    }
    
    private void cleanupResources() {
        if (client != null) {
            try {
                log.debug("Starting HTTP client cleanup");
                
                // 1. Cancel all pending calls
                client.dispatcher().cancelAll();
                log.debug("Cancelled all pending HTTP calls");
                
                // 2. Shutdown dispatcher executor with timeout
                client.dispatcher().executorService().shutdown();
                boolean dispatcherShutdown = client.dispatcher().executorService()
                    .awaitTermination(10, TimeUnit.SECONDS);
                
                if (!dispatcherShutdown) {
                    log.warn("Dispatcher executor did not shutdown gracefully, forcing shutdown");
                    client.dispatcher().executorService().shutdownNow();
                    // Wait again for forced shutdown
                    boolean forcedShutdown = client.dispatcher().executorService()
                        .awaitTermination(5, TimeUnit.SECONDS);
                    if (!forcedShutdown) {
                        log.error("Failed to force shutdown dispatcher executor");
                    }
                } else {
                    log.debug("Dispatcher executor shutdown successfully");
                }
                
                // 3. Close connection pool
                client.connectionPool().evictAll();
                log.debug("Evicted all connections from pool");
                
                // 4. Close cache if present
                if (client.cache() != null) {
                    try {
                        client.cache().close();
                        log.debug("HTTP cache closed successfully");
                    } catch (IOException e) {
                        log.warn("Error closing HTTP cache", e);
                    }
                }
                
                log.info("HTTP client cleanup completed successfully");
                
            } catch (InterruptedException e) {
                log.warn("HTTP client cleanup interrupted", e);
                Thread.currentThread().interrupt();
                // Force immediate shutdown on interruption
                client.dispatcher().executorService().shutdownNow();
            } catch (Exception e) {
                log.error("Unexpected error during HTTP client cleanup", e);
            } finally {
                // Ensure client reference is cleared
                client = null;
                log.debug("HTTP client reference cleared");
            }
        } else {
            log.debug("HTTP client was null, no cleanup needed");
        }
        
        // Clear other resources
        sourcePartition = null;
        nextCursor = null;
        lastCommittedCursor = null;
        
        log.debug("All resources cleaned up");
    }

    private void unregisterMBean() {
        if (mbeanServer != null && mbeanName != null) {
            try {
                if (mbeanServer.isRegistered(mbeanName)) {
                    mbeanServer.unregisterMBean(mbeanName);
                    log.info("Unregistered JMX MBean: {}", mbeanName);
                }
            } catch (Exception e) {
                log.error("Failed to unregister JMX MBean", e);
            }
        }
    }

    // JMX MBean Interface Implementation
    @Override
    public String getConnectorStatus() {
        if (isShuttingDown) return "STOPPING";
        if (!isHealthy) return "UNHEALTHY";
        if (consecutiveFailures > 0) return "DEGRADED";
        return "HEALTHY";
    }

    @Override
    public boolean isHealthy() {
        return isHealthy && !isShuttingDown;
    }

    @Override
    public boolean isCircuitBreakerOpen() {
        return !isHealthy && shouldStayInCircuitBreaker();
    }

    @Override
    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    @Override
    public String getLastFailureTime() {
        return lastFailureTime != null ? lastFailureTime.toString() : "None";
    }

    @Override
    public long getTotalRecordsProcessed() {
        return recordsProcessed;
    }

    @Override
    public long getTotalPollCycles() {
        return totalPollCycles.get();
    }

    @Override
    public long getTotalSuccessfulPollCycles() {
        return successfulPollCycles.get();
    }

    @Override
    public long getTotalFailedPollCycles() {
        return failedPollCycles.get();
    }

    @Override
    public double getRecordsPerSecond() {
        long elapsedSeconds = Duration.between(metricsStartTime, Instant.now()).toSeconds();
        return elapsedSeconds > 0 ? (double) recordsProcessed / elapsedSeconds : 0.0;
    }

    @Override
    public long getAverageQueryTimeMs() {
        long totalQueries = totalGraphQLQueries.get();
        return totalQueries > 0 ? queryTimeAccumulator.get() / totalQueries : 0;
    }

    @Override
    public long getLastQueryTimeMs() {
        return lastQueryTime != null ? Duration.between(lastQueryTime.minusMillis(1000), lastQueryTime).toMillis() : 0;
    }

    @Override
    public String getEntityName() {
        return config != null ? config.entityName() : "Unknown";
    }

    @Override
    public String getGraphQLEndpoint() {
        return config != null ? config.endpoint() : "Unknown";
    }

    @Override
    public String getCurrentCursor() {
        return nextCursor != null ? nextCursor : "None";
    }

    @Override
    public String getLastCommittedCursor() {
        return lastCommittedCursor != null ? lastCommittedCursor : "None";
    }

    @Override
    public long getTotalGraphQLQueries() {
        return totalGraphQLQueries.get();
    }

    @Override
    public long getTotalGraphQLErrors() {
        return totalGraphQLErrors.get();
    }

    @Override
    public long getTotalRetryAttempts() {
        return totalRetryAttempts.get();
    }

    @Override
    public int getActiveConnections() {
        return client != null ? client.connectionPool().connectionCount() : 0;
    }

    @Override
    public int getIdleConnections() {
        return client != null ? client.connectionPool().idleConnectionCount() : 0;
    }

    @Override
    public long getTotalBytesReceived() {
        return totalBytesReceived.get();
    }

    @Override
    public String getLastErrorMessage() {
        return lastErrorMessage.get();
    }

    @Override
    public String getLastErrorTime() {
        Instant errorTime = lastErrorTime.get();
        return errorTime != null ? errorTime.toString() : "None";
    }

    @Override
    public double getErrorRate() {
        long totalPolls = totalPollCycles.get();
        return totalPolls > 0 ? (double) failedPollCycles.get() / totalPolls : 0.0;
    }

    @Override
    public long getPollingIntervalMs() {
        return config != null ? config.pollingIntervalMs() : 0;
    }

    @Override
    public int getResultSize() {
        return config != null ? config.resultSize() : 0;
    }

    @Override
    public String getSelectedColumns() {
        return config != null ? config.dataPath() : "Unknown";
    }

    @Override
    public void resetMetrics() {
        totalPollCycles.set(0);
        successfulPollCycles.set(0);
        failedPollCycles.set(0);
        totalGraphQLQueries.set(0);
        totalGraphQLErrors.set(0);
        totalRetryAttempts.set(0);
        totalBytesReceived.set(0);
        queryTimeAccumulator.set(0);
        recordsProcessed = 0;
        metricsStartTime = Instant.now();
        log.info("JMX metrics reset");
    }

    @Override
    public void resetErrorTracking() {
        consecutiveFailures = 0;
        lastFailureTime = null;
        lastErrorMessage.set(null);
        lastErrorTime.set(null);
        isHealthy = true;
        log.info("Error tracking reset");
    }

    @Override
    public String getHealthSummary() {
        return String.format(
            "Status: %s | Records: %d | Polls: %d (Success: %d, Failed: %d) | Error Rate: %.2f%% | Queries: %d (Errors: %d) | Avg Query Time: %dms",
            getConnectorStatus(),
            recordsProcessed,
            totalPollCycles.get(),
            successfulPollCycles.get(),
            failedPollCycles.get(),
            getErrorRate() * 100,
            totalGraphQLQueries.get(),
            totalGraphQLErrors.get(),
            getAverageQueryTimeMs()
        );
    }

    private record GraphQLQueryResult(List<JsonNode> nodes, String endCursor, boolean hasNextPage) {}
}
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

public class GraphQLSourceTask extends SourceTask {

    private static final Logger log = LoggerFactory.getLogger(GraphQLSourceTask.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final int MAX_ITERATIONS = 1000;

    private GraphQLSourceConnectorConfig config;
    private OkHttpClient client;
    private ObjectMapper mapper = new ObjectMapper();
    private String nextCursor;
    private String lastCommittedCursor;
    private long recordsProcessed = 0;
    private Instant lastQueryTime;
    private Map<String, Object> sourcePartition;

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
        
        if (config.selectedColumns() == null || config.selectedColumns().isEmpty()) {
            throw new IllegalArgumentException("Selected columns are required");
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

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        String correlationId = generateCorrelationId();
        log.debug("Starting poll cycle with correlation ID: {}", correlationId);
        
        try {
            List<SourceRecord> records = new ArrayList<>();
            boolean hasNext = true;
            String after = nextCursor;
            int iterationCount = 0;
            
            while (hasNext && iterationCount < MAX_ITERATIONS) {
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
            // Offset will be committed by Kafka Connect framework when records are successfully produced
            if (!records.isEmpty()) {
                nextCursor = after;
                log.info("Poll cycle completed. Records: {}, Total processed: {}, Next cursor: {}", 
                        records.size(), recordsProcessed, nextCursor);
            } else {
                log.info("Poll cycle completed with no records. Cursor unchanged: {}", nextCursor);
            }
            
            return records;
            
        } catch (Exception e) {
            // On failure, reset cursor to last committed position to avoid data loss
            log.error("Error during poll cycle with correlation ID: {}, resetting cursor to last committed: {}", 
                    correlationId, lastCommittedCursor, e);
            nextCursor = lastCommittedCursor;
            throw new RuntimeException("Poll failed", e);
        } finally {
            Thread.sleep(config.pollingIntervalMs());
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
                
                return result;
                
            } catch (IOException e) {
                attempt++;
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
        String query = buildQuery(after);
        Map<String, Object> variables = new HashMap<>();
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
        JsonNode dataNode = responseNode.path("data");
        JsonNode entityNode = dataNode.path(config.entityName());
        JsonNode edges = entityNode.path("edges");
        
        List<JsonNode> nodes = new ArrayList<>();
        String endCursor = null;
        
        for (JsonNode edge : edges) {
            nodes.add(edge.path("node"));
            endCursor = edge.path("cursor").asText();
        }
        
        JsonNode pageInfo = entityNode.path("pageInfo");
        boolean hasNext = pageInfo.path("hasNextPage").asBoolean(false);
        if (!pageInfo.path("endCursor").isMissingNode()) {
            endCursor = pageInfo.path("endCursor").asText();
        }
        
        return new GraphQLQueryResult(nodes, endCursor, hasNext);
    }

    private SourceRecord createSourceRecord(JsonNode node, String endCursor, String correlationId) {
        // Create offset information that will be committed atomically with the record
        Map<String, Object> sourceOffset = new HashMap<>();
        sourceOffset.put("last_cursor", endCursor);
        sourceOffset.put("timestamp", Instant.now().toString());
        
        String recordKey = null;
        if (node.has(config.offsetField())) {
            recordKey = node.get(config.offsetField()).asText();
            sourceOffset.put("last_id", recordKey);
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
        
        for (String fieldName : config.selectedColumns()) {
            schemaBuilder.field(fieldName, Schema.OPTIONAL_STRING_SCHEMA);
            
            String fieldValue = null;
            if (node.has(fieldName)) {
                JsonNode fieldNode = node.get(fieldName);
                if (!fieldNode.isNull()) {
                    fieldValue = fieldNode.asText();
                }
            }
            fieldValues.put(fieldName, fieldValue);
        }
        
        Schema schema = schemaBuilder.build();
        Struct struct = new Struct(schema);
        
        for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
            struct.put(entry.getKey(), entry.getValue());
        }
        
        return struct;
    }

    private String buildTopicName() {
        String prefix = config.topicPrefix();
        if (prefix != null && !prefix.trim().isEmpty()) {
            return prefix + config.entityName();
        }
        return config.entityName();
    }

    private String buildQuery(String after) {
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("query GetEntity(");
        queryBuilder.append(buildParameterDeclaration(after));
        queryBuilder.append(") {");
        queryBuilder.append(config.entityName());
        queryBuilder.append("(first: $first");
        
        if (after != null) {
            queryBuilder.append(", after: $after");
        }
        
        queryBuilder.append(") {");
        queryBuilder.append("edges { node {");
        
        for (String field : config.selectedColumns()) {
            queryBuilder.append(field).append(" ");
        }
        
        queryBuilder.append("} cursor }");
        queryBuilder.append("pageInfo { hasNextPage endCursor }");
        queryBuilder.append("} }");
        
        return queryBuilder.toString();
    }

    private String buildParameterDeclaration(String after) {
        if (after != null) {
            return "$first: Int!, $after: String";
        }
        return "$first: Int!";
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
        
        if (client != null) {
            client.connectionPool().evictAll();
            try {
                client.dispatcher().executorService().shutdown();
            } catch (Exception e) {
                log.warn("Error shutting down HTTP client", e);
            }
        }
        
        log.info("GraphQL Source Task stopped");
    }

    private record GraphQLQueryResult(List<JsonNode> nodes, String endCursor, boolean hasNextPage) {}
}
package com.example.graphqlconnector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;

public class GraphQLSourceConnectorConfig extends AbstractConfig {

    // Core GraphQL Configuration
    public static final String GRAPHQL_ENDPOINT = "graphql.endpoint.url";
    public static final String GRAPHQL_QUERY = "graphql.query";
    public static final String GRAPHQL_VARIABLES = "graphql.variables";
    public static final String GRAPHQL_HEADERS = "graphql.headers";
    
    // Response Parsing Configuration
    public static final String DATA_PATH = "data.path";
    public static final String PAGINATION_CURSOR_PATH = "pagination.cursor.path";
    public static final String PAGINATION_HASMORE_PATH = "pagination.hasmore.path";
    public static final String RECORD_KEY_PATH = "record.key.path";
    
    // Operational Configuration
    public static final String RESULT_SIZE = "result.size";
    public static final String POLLING_INTERVAL_MS = "polling.interval.ms";
    public static final String KAFKA_TOPIC_NAME = "kafka.topic.name";
    public static final String QUERY_TIMEOUT_MS = "query.timeout.ms";
    public static final String MAX_RETRIES = "max.retries";
    public static final String RETRY_BACKOFF_MS = "retry.backoff.ms";

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            // Core GraphQL Configuration
            .define(GRAPHQL_ENDPOINT, Type.STRING, Importance.HIGH, 
                    "GraphQL endpoint URL")
            .define(GRAPHQL_QUERY, Type.STRING, Importance.HIGH, 
                    "Full GraphQL query string with pagination variables ($first, $after)")
            .define(GRAPHQL_VARIABLES, Type.STRING, "{}", Importance.MEDIUM, 
                    "Additional GraphQL query variables as JSON object")
            .define(GRAPHQL_HEADERS, Type.PASSWORD, "", Importance.MEDIUM, 
                    "HTTP headers for GraphQL requests (format: key1:value1,key2:value2)")
            
            // Response Parsing Configuration
            .define(DATA_PATH, Type.STRING, "*.edges[*].node", Importance.HIGH, 
                    "JSONPath expression to extract data records from GraphQL response")
            .define(PAGINATION_CURSOR_PATH, Type.STRING, "*.pageInfo.endCursor", Importance.MEDIUM, 
                    "JSONPath expression to extract next page cursor")
            .define(PAGINATION_HASMORE_PATH, Type.STRING, "*.pageInfo.hasNextPage", Importance.MEDIUM, 
                    "JSONPath expression to extract hasMore pagination flag")
            .define(RECORD_KEY_PATH, Type.STRING, "id", Importance.MEDIUM, 
                    "JSONPath expression to extract record key from each data record")
            
            // Operational Configuration
            .define(RESULT_SIZE, Type.INT, 100, Importance.MEDIUM, 
                    "Number of records to request per GraphQL query")
            .define(POLLING_INTERVAL_MS, Type.LONG, 30000L, Importance.MEDIUM, 
                    "Interval between poll cycles in milliseconds")
            .define(KAFKA_TOPIC_NAME, Type.STRING, Importance.HIGH, 
                    "Kafka topic name where records will be published")
            .define(QUERY_TIMEOUT_MS, Type.LONG, 30000L, Importance.MEDIUM, 
                    "Timeout for GraphQL queries in milliseconds")
            .define(MAX_RETRIES, Type.INT, 3, Importance.MEDIUM, 
                    "Maximum number of retry attempts for failed queries")
            .define(RETRY_BACKOFF_MS, Type.LONG, 1000L, Importance.MEDIUM, 
                    "Backoff time between retry attempts in milliseconds");

    private final ObjectMapper mapper = new ObjectMapper();

    public GraphQLSourceConnectorConfig(Map<String, ?> originals) {
        super(CONFIG_DEF, originals);
    }

    // Core GraphQL Configuration
    public String endpoint() {
        return getString(GRAPHQL_ENDPOINT);
    }

    public String graphqlQuery() {
        return getString(GRAPHQL_QUERY);
    }

    public Map<String, Object> graphqlVariables() {
        String variablesJson = getString(GRAPHQL_VARIABLES);
        try {
            JsonNode node = mapper.readTree(variablesJson);
            return mapper.convertValue(node, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON in graphql.variables: " + variablesJson, e);
        }
    }

    public Map<String, String> headers() {
        String headerString = getPassword(GRAPHQL_HEADERS).value();
        Map<String, String> headers = new HashMap<>();
        if (headerString != null && !headerString.trim().isEmpty()) {
            String[] pairs = headerString.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":", 2); // Limit to 2 parts max
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String value = kv[1].trim();
                    
                    if (!key.isEmpty()) { // Basic validation
                        headers.put(key, value);
                    }
                }
            }
        }
        return headers;
    }

    // Response Parsing Configuration
    public String dataPath() {
        return getString(DATA_PATH);
    }

    public String paginationCursorPath() {
        return getString(PAGINATION_CURSOR_PATH);
    }

    public String paginationHasMorePath() {
        return getString(PAGINATION_HASMORE_PATH);
    }

    public String recordKeyPath() {
        return getString(RECORD_KEY_PATH);
    }

    // Operational Configuration
    public int resultSize() {
        return getInt(RESULT_SIZE);
    }

    public long pollingIntervalMs() {
        return getLong(POLLING_INTERVAL_MS);
    }

    public String kafkaTopicName() {
        return getString(KAFKA_TOPIC_NAME);
    }

    public long queryTimeoutMs() {
        return getLong(QUERY_TIMEOUT_MS);
    }

    public int maxRetries() {
        return getInt(MAX_RETRIES);
    }

    public long retryBackoffMs() {
        return getLong(RETRY_BACKOFF_MS);
    }

    // Derived methods for backward compatibility with existing code
    public String entityName() {
        // Extract entity name from query for logging/metrics
        String query = graphqlQuery();
        // Simple regex to extract first entity name from query
        // This is a best-effort approach for logging purposes
        try {
            String[] parts = query.split("\\{")[1].split("\\(")[0].trim().split(":");
            return parts[parts.length - 1].trim();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public String offsetField() {
        // Use record key path as offset field
        return recordKeyPath();
    }
}
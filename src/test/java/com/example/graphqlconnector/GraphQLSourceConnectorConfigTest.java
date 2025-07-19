package com.example.graphqlconnector;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GraphQLSourceConnectorConfigTest {

    @Test
    public void testValidConfiguration() {
        Map<String, String> props = createValidProps();
        GraphQLSourceConnectorConfig config = new GraphQLSourceConnectorConfig(props);
        
        assertEquals("https://api.example.com/graphql", config.endpoint());
        assertEquals("users", config.entityName());
        assertEquals(50, config.resultSize());
        assertEquals(List.of("id", "name", "email"), config.selectedColumns());
        assertEquals(60000L, config.pollingIntervalMs());
        assertEquals("test_", config.topicPrefix());
        assertEquals("id", config.offsetField());
        assertEquals(30000L, config.queryTimeoutMs());
        assertEquals(3, config.maxRetries());
        assertEquals(1000L, config.retryBackoffMs());
    }

    @Test
    public void testHeadersParsing() {
        Map<String, String> props = createValidProps();
        props.put(GraphQLSourceConnectorConfig.GRAPHQL_HEADERS, "Authorization:Bearer token123,Content-Type:application/json");
        
        GraphQLSourceConnectorConfig config = new GraphQLSourceConnectorConfig(props);
        Map<String, String> headers = config.headers();
        
        assertEquals("Bearer token123", headers.get("Authorization"));
        assertEquals("application/json", headers.get("Content-Type"));
    }

    @Test
    public void testEmptyHeaders() {
        Map<String, String> props = createValidProps();
        props.put(GraphQLSourceConnectorConfig.GRAPHQL_HEADERS, "");
        
        GraphQLSourceConnectorConfig config = new GraphQLSourceConnectorConfig(props);
        Map<String, String> headers = config.headers();
        
        assertTrue(headers.isEmpty());
    }

    @Test
    public void testDefaultValues() {
        Map<String, String> props = new HashMap<>();
        props.put(GraphQLSourceConnectorConfig.GRAPHQL_ENDPOINT, "https://api.example.com/graphql");
        props.put(GraphQLSourceConnectorConfig.ENTITY_NAME, "users");
        props.put(GraphQLSourceConnectorConfig.SELECTED_COLUMNS, "id,name");
        
        GraphQLSourceConnectorConfig config = new GraphQLSourceConnectorConfig(props);
        
        assertEquals(100, config.resultSize());
        assertEquals(30000L, config.pollingIntervalMs());
        assertEquals("", config.topicPrefix());
        assertEquals("id", config.offsetField());
        assertEquals(30000L, config.queryTimeoutMs());
        assertEquals(3, config.maxRetries());
        assertEquals(1000L, config.retryBackoffMs());
    }

    private Map<String, String> createValidProps() {
        Map<String, String> props = new HashMap<>();
        props.put(GraphQLSourceConnectorConfig.GRAPHQL_ENDPOINT, "https://api.example.com/graphql");
        props.put(GraphQLSourceConnectorConfig.ENTITY_NAME, "users");
        props.put(GraphQLSourceConnectorConfig.RESULT_SIZE, "50");
        props.put(GraphQLSourceConnectorConfig.SELECTED_COLUMNS, "id,name,email");
        props.put(GraphQLSourceConnectorConfig.POLLING_INTERVAL_MS, "60000");
        props.put(GraphQLSourceConnectorConfig.TOPIC_PREFIX, "test_");
        props.put(GraphQLSourceConnectorConfig.OFFSET_FIELD, "id");
        props.put(GraphQLSourceConnectorConfig.QUERY_TIMEOUT_MS, "30000");
        props.put(GraphQLSourceConnectorConfig.MAX_RETRIES, "3");
        props.put(GraphQLSourceConnectorConfig.RETRY_BACKOFF_MS, "1000");
        return props;
    }
}
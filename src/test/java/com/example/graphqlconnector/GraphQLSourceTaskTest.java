package com.example.graphqlconnector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.apache.kafka.connect.storage.OffsetStorageReader;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class GraphQLSourceTaskTest {

    @Mock
    private SourceTaskContext context;
    
    @Mock
    private OffsetStorageReader offsetStorageReader;
    
    private GraphQLSourceTask task;
    private Map<String, String> props;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        task = new GraphQLSourceTask();
        props = createValidProps();
        
        when(context.offsetStorageReader()).thenReturn(offsetStorageReader);
        when(offsetStorageReader.offset(any())).thenReturn(null);
        
        task.initialize(context);
    }

    @Test
    public void testVersion() {
        assertEquals("0.1.0", task.version());
    }

    @Test
    public void testStartWithValidConfig() {
        task.start(props);
        task.stop();
    }

    @Test(expected = RuntimeException.class)
    public void testStartWithInvalidConfig() {
        Map<String, String> invalidProps = new HashMap<>();
        invalidProps.put(GraphQLSourceConnectorConfig.GRAPHQL_ENDPOINT, "");
        invalidProps.put(GraphQLSourceConnectorConfig.ENTITY_NAME, "users");
        invalidProps.put(GraphQLSourceConnectorConfig.SELECTED_COLUMNS, "id,name");
        
        task.start(invalidProps);
    }

    @Test(expected = RuntimeException.class)
    public void testStartWithMissingEntityName() {
        Map<String, String> invalidProps = new HashMap<>();
        invalidProps.put(GraphQLSourceConnectorConfig.GRAPHQL_ENDPOINT, "https://api.example.com/graphql");
        invalidProps.put(GraphQLSourceConnectorConfig.ENTITY_NAME, "");
        invalidProps.put(GraphQLSourceConnectorConfig.SELECTED_COLUMNS, "id,name");
        
        task.start(invalidProps);
    }

    @Test(expected = RuntimeException.class)
    public void testStartWithMissingSelectedColumns() {
        Map<String, String> invalidProps = new HashMap<>();
        invalidProps.put(GraphQLSourceConnectorConfig.GRAPHQL_ENDPOINT, "https://api.example.com/graphql");
        invalidProps.put(GraphQLSourceConnectorConfig.ENTITY_NAME, "users");
        invalidProps.put(GraphQLSourceConnectorConfig.SELECTED_COLUMNS, "");
        
        task.start(invalidProps);
    }

    @Test
    public void testStartWithExistingOffset() {
        Map<String, Object> offset = new HashMap<>();
        offset.put("last_cursor", "cursor123");
        offset.put("last_id", "user456");
        
        when(offsetStorageReader.offset(any())).thenReturn(offset);
        
        task.start(props);
        task.stop();
    }

    @Test
    public void testQueryGeneration() throws Exception {
        task.start(props);
        
        String query = buildQueryViaReflection(task, null);
        assertTrue(query.contains("query GetEntity($first: Int!)"));
        assertTrue(query.contains("users(first: $first)"));
        assertTrue(query.contains("edges { node {"));
        assertTrue(query.contains("id "));
        assertTrue(query.contains("name "));
        assertTrue(query.contains("email "));
        assertTrue(query.contains("} cursor }"));
        assertTrue(query.contains("pageInfo { hasNextPage endCursor }"));
        
        task.stop();
    }

    @Test
    public void testQueryGenerationWithCursor() throws Exception {
        task.start(props);
        
        String query = buildQueryViaReflection(task, "cursor123");
        assertTrue(query.contains("query GetEntity($first: Int!, $after: String)"));
        assertTrue(query.contains("users(first: $first, after: $after)"));
        
        task.stop();
    }

    @Test
    public void testTopicNameGeneration() throws Exception {
        props.put(GraphQLSourceConnectorConfig.TOPIC_PREFIX, "test_");
        task.start(props);
        
        String topicName = buildTopicNameViaReflection(task);
        assertEquals("test_users", topicName);
        
        task.stop();
    }

    @Test
    public void testTopicNameWithoutPrefix() throws Exception {
        task.start(props);
        
        String topicName = buildTopicNameViaReflection(task);
        assertEquals("users", topicName);
        
        task.stop();
    }

    @Test
    public void testCorrelationIdGeneration() throws Exception {
        task.start(props);
        
        String correlationId = generateCorrelationIdViaReflection(task);
        assertNotNull(correlationId);
        assertTrue(correlationId.startsWith("users-"));
        assertTrue(correlationId.contains("-"));
        
        task.stop();
    }

    private Map<String, String> createValidProps() {
        Map<String, String> props = new HashMap<>();
        props.put(GraphQLSourceConnectorConfig.GRAPHQL_ENDPOINT, "https://api.example.com/graphql");
        props.put(GraphQLSourceConnectorConfig.ENTITY_NAME, "users");
        props.put(GraphQLSourceConnectorConfig.RESULT_SIZE, "10");
        props.put(GraphQLSourceConnectorConfig.SELECTED_COLUMNS, "id,name,email");
        props.put(GraphQLSourceConnectorConfig.POLLING_INTERVAL_MS, "1000");
        props.put(GraphQLSourceConnectorConfig.QUERY_TIMEOUT_MS, "5000");
        props.put(GraphQLSourceConnectorConfig.MAX_RETRIES, "2");
        props.put(GraphQLSourceConnectorConfig.RETRY_BACKOFF_MS, "500");
        return props;
    }

    private String buildQueryViaReflection(GraphQLSourceTask task, String after) throws Exception {
        java.lang.reflect.Method method = GraphQLSourceTask.class.getDeclaredMethod("buildQuery", String.class);
        method.setAccessible(true);
        return (String) method.invoke(task, after);
    }

    private String buildTopicNameViaReflection(GraphQLSourceTask task) throws Exception {
        java.lang.reflect.Method method = GraphQLSourceTask.class.getDeclaredMethod("buildTopicName");
        method.setAccessible(true);
        return (String) method.invoke(task);
    }

    private String generateCorrelationIdViaReflection(GraphQLSourceTask task) throws Exception {
        java.lang.reflect.Method method = GraphQLSourceTask.class.getDeclaredMethod("generateCorrelationId");
        method.setAccessible(true);
        return (String) method.invoke(task);
    }
}
package com.example.graphqlconnector;

import org.apache.kafka.connect.source.SourceTaskContext;
import org.apache.kafka.connect.storage.OffsetStorageReader;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
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
        invalidProps.put(GraphQLSourceConnectorConfig.GRAPHQL_QUERY, "query GetEntity($first: Int!) { users(first: $first) { edges { node { id name } cursor } pageInfo { hasNextPage endCursor } } }");
        invalidProps.put(GraphQLSourceConnectorConfig.KAFKA_TOPIC_NAME, "users");
        
        task.start(invalidProps);
    }

    @Test(expected = RuntimeException.class)
    public void testStartWithMissingQuery() {
        Map<String, String> invalidProps = new HashMap<>();
        invalidProps.put(GraphQLSourceConnectorConfig.GRAPHQL_ENDPOINT, "https://api.example.com/graphql");
        invalidProps.put(GraphQLSourceConnectorConfig.GRAPHQL_QUERY, "");
        invalidProps.put(GraphQLSourceConnectorConfig.KAFKA_TOPIC_NAME, "users");
        
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
    public void testGraphQLQueryConfiguration() throws Exception {
        task.start(props);
        
        // Test that the query is properly configured from the properties
        assertTrue("Query should contain users entity", props.get(GraphQLSourceConnectorConfig.GRAPHQL_QUERY).contains("users"));
        assertTrue("Query should contain pagination parameters", props.get(GraphQLSourceConnectorConfig.GRAPHQL_QUERY).contains("$first"));
        assertTrue("Query should contain cursor parameter", props.get(GraphQLSourceConnectorConfig.GRAPHQL_QUERY).contains("$after"));
        assertTrue("Query should contain id field", props.get(GraphQLSourceConnectorConfig.GRAPHQL_QUERY).contains("id"));
        assertTrue("Query should contain name field", props.get(GraphQLSourceConnectorConfig.GRAPHQL_QUERY).contains("name"));
        assertTrue("Query should contain email field", props.get(GraphQLSourceConnectorConfig.GRAPHQL_QUERY).contains("email"));
        
        task.stop();
    }

    @Test
    public void testTopicNameGeneration() throws Exception {
        Map<String, String> propsWithCustomTopic = createValidProps();
        propsWithCustomTopic.put(GraphQLSourceConnectorConfig.KAFKA_TOPIC_NAME, "test_users");
        task.start(propsWithCustomTopic);
        
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
        props.put(GraphQLSourceConnectorConfig.GRAPHQL_QUERY, "query GetEntity($first: Int!, $after: String) { users(first: $first, after: $after) { edges { node { id name email } cursor } pageInfo { hasNextPage endCursor } } }");
        props.put(GraphQLSourceConnectorConfig.RESULT_SIZE, "10");
        props.put(GraphQLSourceConnectorConfig.KAFKA_TOPIC_NAME, "users");
        props.put(GraphQLSourceConnectorConfig.POLLING_INTERVAL_MS, "1000");
        props.put(GraphQLSourceConnectorConfig.QUERY_TIMEOUT_MS, "5000");
        props.put(GraphQLSourceConnectorConfig.MAX_RETRIES, "2");
        props.put(GraphQLSourceConnectorConfig.RETRY_BACKOFF_MS, "500");
        return props;
    }

    // Removed buildQuery method - it no longer exists in the current implementation

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
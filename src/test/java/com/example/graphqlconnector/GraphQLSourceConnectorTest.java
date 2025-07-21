package com.example.graphqlconnector;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GraphQLSourceConnectorTest {

    @Test
    public void testVersion() {
        GraphQLSourceConnector connector = new GraphQLSourceConnector();
        assertEquals("0.1.0", connector.version());
    }

    @Test
    public void testTaskClass() {
        GraphQLSourceConnector connector = new GraphQLSourceConnector();
        assertEquals(GraphQLSourceTask.class, connector.taskClass());
    }

    @Test
    public void testTaskConfigs() {
        GraphQLSourceConnector connector = new GraphQLSourceConnector();
        Map<String, String> props = createValidProps();
        
        connector.start(props);
        
        List<Map<String, String>> taskConfigs = connector.taskConfigs(3);
        assertEquals(3, taskConfigs.size());
        
        for (Map<String, String> taskConfig : taskConfigs) {
            assertEquals(props, taskConfig);
        }
    }

    @Test
    public void testConfigDef() {
        GraphQLSourceConnector connector = new GraphQLSourceConnector();
        assertNotNull(connector.config());
        assertEquals(GraphQLSourceConnectorConfig.CONFIG_DEF, connector.config());
    }

    @Test
    public void testStartStop() {
        GraphQLSourceConnector connector = new GraphQLSourceConnector();
        Map<String, String> props = createValidProps();
        
        connector.start(props);
        connector.stop();
    }

    private Map<String, String> createValidProps() {
        Map<String, String> props = new HashMap<>();
        props.put(GraphQLSourceConnectorConfig.GRAPHQL_ENDPOINT, "https://api.example.com/graphql");
        props.put(GraphQLSourceConnectorConfig.GRAPHQL_QUERY, "query GetEntity($first: Int!, $after: String) { users(first: $first, after: $after) { edges { node { id name email } cursor } pageInfo { hasNextPage endCursor } } }");
        props.put(GraphQLSourceConnectorConfig.KAFKA_TOPIC_NAME, "users");
        return props;
    }
}
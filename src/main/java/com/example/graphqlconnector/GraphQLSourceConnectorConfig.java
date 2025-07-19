package com.example.graphqlconnector;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;

public class GraphQLSourceConnectorConfig extends AbstractConfig {

    public static final String GRAPHQL_ENDPOINT = "graphql.endpoint.url";
    public static final String ENTITY_NAME = "entity.name";
    public static final String RESULT_SIZE = "result.size";
    public static final String SELECTED_COLUMNS = "selected.columns";
    public static final String GRAPHQL_HEADERS = "graphql.headers";
    public static final String POLLING_INTERVAL_MS = "polling.interval.ms";
    public static final String TOPIC_PREFIX = "topic.prefix";
    public static final String OFFSET_FIELD = "offset.field";
    public static final String QUERY_TIMEOUT_MS = "query.timeout.ms";
    public static final String MAX_RETRIES = "max.retries";
    public static final String RETRY_BACKOFF_MS = "retry.backoff.ms";

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            .define(GRAPHQL_ENDPOINT, Type.STRING, Importance.HIGH, "GraphQL endpoint")
            .define(ENTITY_NAME, Type.STRING, Importance.HIGH, "GraphQL entity name")
            .define(RESULT_SIZE, Type.INT, 100, Importance.HIGH, "Result size per query")
            .define(SELECTED_COLUMNS, Type.LIST, Importance.HIGH, "Selected columns")
            .define(GRAPHQL_HEADERS, Type.STRING, "", Importance.MEDIUM, "GraphQL headers")
            .define(POLLING_INTERVAL_MS, Type.LONG, 30000L, Importance.MEDIUM, "Polling interval ms")
            .define(TOPIC_PREFIX, Type.STRING, "", Importance.MEDIUM, "Topic prefix")
            .define(OFFSET_FIELD, Type.STRING, "id", Importance.MEDIUM, "Offset field")
            .define(QUERY_TIMEOUT_MS, Type.LONG, 30000L, Importance.MEDIUM, "Query timeout ms")
            .define(MAX_RETRIES, Type.INT, 3, Importance.MEDIUM, "Max retries")
            .define(RETRY_BACKOFF_MS, Type.LONG, 1000L, Importance.MEDIUM, "Retry backoff ms");

    public GraphQLSourceConnectorConfig(Map<String, ?> originals) {
        super(CONFIG_DEF, originals);
    }

    public String endpoint() {
        return getString(GRAPHQL_ENDPOINT);
    }

    public String entityName() {
        return getString(ENTITY_NAME);
    }

    public int resultSize() {
        return getInt(RESULT_SIZE);
    }

    public List<String> selectedColumns() {
        return getList(SELECTED_COLUMNS);
    }

    public Map<String, String> headers() {
        String headerString = getString(GRAPHQL_HEADERS);
        Map<String, String> headers = new HashMap<>();
        if (headerString != null && !headerString.trim().isEmpty()) {
            String[] pairs = headerString.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":");
                if (kv.length == 2) {
                    headers.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        return headers;
    }

    public long pollingIntervalMs() {
        return getLong(POLLING_INTERVAL_MS);
    }

    public String topicPrefix() {
        return getString(TOPIC_PREFIX);
    }

    public String offsetField() {
        return getString(OFFSET_FIELD);
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
}


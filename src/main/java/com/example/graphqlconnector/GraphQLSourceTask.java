package com.example.graphqlconnector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;

public class GraphQLSourceTask extends SourceTask {

    private GraphQLSourceConnectorConfig config;
    private OkHttpClient client;
    private ObjectMapper mapper = new ObjectMapper();
    private String nextCursor;

    @Override
    public String version() {
        return "0.1.0";
    }

    @Override
    public void start(Map<String, String> props) {
        this.config = new GraphQLSourceConnectorConfig(props);
        this.client = new OkHttpClient();
        Map<String, Object> offset = context.offsetStorageReader().offset(Collections.singletonMap("entity", config.entityName()));
        if (offset != null) {
            this.nextCursor = (String) offset.getOrDefault("last_cursor", null);
        }
    }

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        try {
            Thread.sleep(config.pollingIntervalMs());
            List<SourceRecord> records = new ArrayList<>();
            boolean hasNext = true;
            String after = nextCursor;
            while (hasNext) {
                GraphQLQueryResult result = executeQuery(after);
                for (JsonNode node : result.nodes) {
                    Map<String, Object> sourcePartition = Collections.singletonMap("entity", config.entityName());
                    Map<String, Object> sourceOffset = new HashMap<>();
                    sourceOffset.put("last_cursor", result.endCursor);
                    if (node.has(config.offsetField())) {
                        sourceOffset.put("last_id", node.get(config.offsetField()).asText());
                    }
                    String topic = topic();
                    Struct struct = buildStruct(node);
                    Schema schema = struct.schema();
                    SourceRecord record = new SourceRecord(sourcePartition, sourceOffset, topic, null, null, null, schema, struct);
                    records.add(record);
                }
                hasNext = result.hasNextPage;
                after = result.endCursor;
                nextCursor = after;
            }
            return records;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Struct buildStruct(JsonNode node) {
        SchemaBuilder builder = SchemaBuilder.struct();
        for (String field : config.selectedColumns()) {
            builder.field(field, Schema.OPTIONAL_STRING_SCHEMA);
        }
        Schema schema = builder.build();
        Struct struct = new Struct(schema);
        for (String field : config.selectedColumns()) {
            struct.put(field, node.has(field) ? node.get(field).asText() : null);
        }
        return struct;
    }

    private String topic() {
        if (config.topicPrefix().isEmpty()) {
            return config.entityName();
        }
        return config.topicPrefix() + config.entityName();
    }

    private GraphQLQueryResult executeQuery(String after) throws IOException {
        String query = buildQuery(after);
        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        String body = mapper.writeValueAsString(Map.of("query", query, "variables", Map.of("first", config.resultSize(), "after", after)));
        Request.Builder builder = new Request.Builder().url(config.endpoint()).post(RequestBody.create(body, JSON));
        for (Map.Entry<String, String> h : config.headers().entrySet()) {
            builder.addHeader(h.getKey(), h.getValue());
        }
        Request request = builder.build();
        try (Response response = client.newCall(request).execute()) {
            JsonNode node = mapper.readTree(response.body().string());
            JsonNode edges = node.path("data").path(config.entityName()).path("edges");
            List<JsonNode> nodes = new ArrayList<>();
            String endCursor = null;
            boolean hasNext = false;
            for (JsonNode edge : edges) {
                nodes.add(edge.path("node"));
                endCursor = edge.path("cursor").asText();
            }
            JsonNode pageInfo = node.path("data").path(config.entityName()).path("pageInfo");
            if (!pageInfo.isMissingNode()) {
                hasNext = pageInfo.path("hasNextPage").asBoolean();
                endCursor = pageInfo.path("endCursor").asText();
            }
            return new GraphQLQueryResult(nodes, endCursor, hasNext);
        }
    }

    private String buildQuery(String after) {
        StringBuilder sb = new StringBuilder();
        sb.append("query GetEntity($first: Int!, $after: String) {");
        sb.append(config.entityName());
        sb.append("(first: $first, after: $after) {");
        sb.append("edges { node {");
        for (String field : config.selectedColumns()) {
            sb.append(field).append(' ');
        }
        sb.append("} cursor }");
        sb.append("pageInfo { hasNextPage endCursor }");
        sb.append("} }");
        return sb.toString();
    }

    @Override
    public void stop() {
        // nothing
    }

    private record GraphQLQueryResult(List<JsonNode> nodes, String endCursor, boolean hasNextPage) {}
}

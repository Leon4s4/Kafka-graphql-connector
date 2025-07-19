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
    private Schema schema;

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
            int iterationCount = 0;
            final int MAX_ITERATIONS = 1000; // Safeguard to prevent infinite loop
            while (hasNext && iterationCount < MAX_ITERATIONS) {
                iterationCount++;
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
        if (schema == null) {
            SchemaBuilder builder = SchemaBuilder.struct();
            for (String field : config.selectedColumns()) {
                JsonNode value = node.get(field);
                if (value != null && !value.isNull()) {
                    builder.field(field, schemaForValue(value));
                } else {
                    builder.field(field, Schema.OPTIONAL_STRING_SCHEMA);
                }
            }
            schema = builder.build();
        }

        Struct struct = new Struct(schema);
        for (String field : config.selectedColumns()) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                struct.put(field, null);
            } else {
                Schema fieldSchema = schema.field(field).schema();
                struct.put(field, convertValue(value, fieldSchema));
            }
        }
        return struct;
    }

    private Schema schemaForValue(JsonNode value) {
        if (value.isInt()) {
            return Schema.OPTIONAL_INT32_SCHEMA;
        } else if (value.isLong()) {
            return Schema.OPTIONAL_INT64_SCHEMA;
        } else if (value.isFloatingPointNumber()) {
            return Schema.OPTIONAL_FLOAT64_SCHEMA;
        } else if (value.isBoolean()) {
            return Schema.OPTIONAL_BOOLEAN_SCHEMA;
        }
        return Schema.OPTIONAL_STRING_SCHEMA;
    }

    private Object convertValue(JsonNode value, Schema fieldSchema) {
        return switch (fieldSchema.type()) {
            case INT32 -> value.asInt();
            case INT64 -> value.asLong();
            case FLOAT32, FLOAT64 -> value.asDouble();
            case BOOLEAN -> value.asBoolean();
            default -> value.asText();
        };
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
        Map<String, Object> variables = new HashMap<>(2);
        variables.put("first", config.resultSize());
        if (after != null) {
            variables.put("after", after);
        }
        String body = mapper.writeValueAsString(Map.of("query", query, "variables", variables));
        Request.Builder builder = new Request.Builder().url(config.endpoint()).post(RequestBody.create(body, JSON));
        for (Map.Entry<String, String> h : config.headers().entrySet()) {
            builder.addHeader(h.getKey(), h.getValue());
        }
        Request request = builder.build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected HTTP response: " + response.code() + " - " + response.message());
            }
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
        sb.append("query GetEntity(");
        sb.append(buildParameterDeclaration(after));
        sb.append(") {");
        sb.append(config.entityName());
        sb.append("(first: $first");
        if (after != null) {
            sb.append(", after: $after");
        }
        sb.append(") {");
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

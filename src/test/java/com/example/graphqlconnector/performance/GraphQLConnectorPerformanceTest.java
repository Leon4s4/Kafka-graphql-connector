package com.example.graphqlconnector.performance;

import com.example.graphqlconnector.GraphQLSourceTask;
import com.example.graphqlconnector.GraphQLSourceTaskMBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive performance benchmarking for GraphQL Source Connector
 * 
 * This test suite measures:
 * - Throughput (records/second)
 * - Latency (response times)
 * - Memory usage
 * - Connection efficiency
 * - Error rates under load
 * - Circuit breaker behavior
 * 
 * Run with: mvn test -Dtest=GraphQLConnectorPerformanceTest
 */
public class GraphQLConnectorPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(GraphQLConnectorPerformanceTest.class);
    private static final String GRAPHQL_ENDPOINT = "http://localhost:4000/graphql";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private GraphQLSourceTask task;
    private PerformanceMetrics metrics;
    private HttpClient httpClient;

    @BeforeClass
    public static void setUpClass() throws Exception {
        // Ensure GraphQL API is running
        waitForService("http://localhost:4000/health", "Mock GraphQL API");
        log.info("Performance testing environment ready");
    }

    @Before
    public void setUp() {
        task = new GraphQLSourceTask();
        metrics = new PerformanceMetrics();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @After
    public void tearDown() {
        if (task != null) {
            try {
                task.stop();
            } catch (Exception e) {
                log.warn("Error stopping task", e);
            }
        }
        metrics.recordTestEnd();
    }

    @Test
    public void testThroughputBenchmark() throws Exception {
        log.info("Starting throughput benchmark test");
        
        Map<String, String> config = createPerformanceConfig();
        config.put("result.size", "100"); // Large page size
        config.put("polling.interval.ms", "100"); // Fast polling
        
        task.start(config);
        
        long testDurationMs = 60000; // 1 minute test
        long startTime = System.currentTimeMillis();
        long endTime = startTime + testDurationMs;
        
        int totalRecords = 0;
        List<Long> responseTimes = new ArrayList<>();
        
        while (System.currentTimeMillis() < endTime) {
            long pollStart = System.currentTimeMillis();
            List<SourceRecord> records = task.poll();
            long pollEnd = System.currentTimeMillis();
            
            totalRecords += records.size();
            responseTimes.add(pollEnd - pollStart);
            
            // Small delay to prevent overwhelming
            Thread.sleep(50);
        }
        
        long actualDuration = System.currentTimeMillis() - startTime;
        double throughputPerSecond = (totalRecords * 1000.0) / actualDuration;
        
        // Calculate statistics
        OptionalDouble avgResponseTime = responseTimes.stream().mapToLong(Long::longValue).average();
        long maxResponseTime = responseTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        long minResponseTime = responseTimes.stream().mapToLong(Long::longValue).min().orElse(0);
        
        // Record metrics
        ThroughputResult result = new ThroughputResult(
                totalRecords,
                actualDuration,
                throughputPerSecond,
                avgResponseTime.orElse(0),
                minResponseTime,
                maxResponseTime
        );
        
        metrics.recordThroughputTest(result);
        
        log.info("Throughput Benchmark Results:");
        log.info("Total Records: {}", totalRecords);
        log.info("Duration: {}ms", actualDuration);
        log.info("Throughput: {:.2f} records/second", throughputPerSecond);
        log.info("Avg Response Time: {:.2f}ms", avgResponseTime.orElse(0));
        log.info("Min Response Time: {}ms", minResponseTime);
        log.info("Max Response Time: {}ms", maxResponseTime);
        
        // Assertions for minimum performance
        assertTrue("Throughput should be at least 5 records/second", throughputPerSecond >= 5.0);
        assertTrue("Average response time should be under 2 seconds", avgResponseTime.orElse(0) < 2000);
        assertTrue("Should process at least 100 records in 1 minute", totalRecords >= 100);
    }

    @Test
    public void testMemoryUsageBenchmark() throws Exception {
        log.info("Starting memory usage benchmark test");
        
        // Force garbage collection before test
        System.gc();
        Thread.sleep(1000);
        
        Runtime runtime = Runtime.getRuntime();
        long memoryBefore = runtime.totalMemory() - runtime.freeMemory();
        
        Map<String, String> config = createPerformanceConfig();
        config.put("result.size", "500"); // Large batches to test memory
        
        task.start(config);
        
        // Run for 30 seconds with memory monitoring
        long testDuration = 30000;
        long startTime = System.currentTimeMillis();
        List<Long> memorySnapshots = new ArrayList<>();
        
        while (System.currentTimeMillis() - startTime < testDuration) {
            task.poll();
            
            long currentMemory = runtime.totalMemory() - runtime.freeMemory();
            memorySnapshots.add(currentMemory);
            
            Thread.sleep(1000); // Sample every second
        }
        
        long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = memoryAfter - memoryBefore;
        
        // Calculate memory statistics
        OptionalDouble avgMemoryUsage = memorySnapshots.stream().mapToLong(Long::longValue).average();
        long maxMemoryUsage = memorySnapshots.stream().mapToLong(Long::longValue).max().orElse(0);
        long minMemoryUsage = memorySnapshots.stream().mapToLong(Long::longValue).min().orElse(0);
        
        MemoryUsageResult result = new MemoryUsageResult(
                memoryBefore,
                memoryAfter,
                memoryIncrease,
                avgMemoryUsage.orElse(0),
                minMemoryUsage,
                maxMemoryUsage
        );
        
        metrics.recordMemoryTest(result);
        
        log.info("Memory Usage Benchmark Results:");
        log.info("Memory Before: {:.2f} MB", memoryBefore / 1024.0 / 1024.0);
        log.info("Memory After: {:.2f} MB", memoryAfter / 1024.0 / 1024.0);
        log.info("Memory Increase: {:.2f} MB", memoryIncrease / 1024.0 / 1024.0);
        log.info("Average Memory: {:.2f} MB", avgMemoryUsage.orElse(0) / 1024.0 / 1024.0);
        log.info("Max Memory: {:.2f} MB", maxMemoryUsage / 1024.0 / 1024.0);
        
        // Memory usage assertions (should not exceed 100MB increase)
        assertTrue("Memory increase should be reasonable", memoryIncrease < 100 * 1024 * 1024);
        assertTrue("Average memory should be manageable", avgMemoryUsage.orElse(0) < 500 * 1024 * 1024);
    }

    @Test
    public void testConcurrentLoadBenchmark() throws Exception {
        log.info("Starting concurrent load benchmark test");
        
        int numberOfTasks = 3; // Simulate multiple connector tasks
        ExecutorService executor = Executors.newFixedThreadPool(numberOfTasks);
        Map<String, Object> results = new ConcurrentHashMap<>();
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < numberOfTasks; i++) {
            final int taskId = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    GraphQLSourceTask concurrentTask = new GraphQLSourceTask();
                    Map<String, String> config = createPerformanceConfig();
                    config.put("kafka.topic.name", "concurrent_test_" + taskId);
                    config.put("polling.interval.ms", "200");
                    
                    concurrentTask.start(config);
                    
                    int recordsProcessed = 0;
                    long startTime = System.currentTimeMillis();
                    long testDuration = 30000; // 30 seconds per task
                    
                    while (System.currentTimeMillis() - startTime < testDuration) {
                        List<SourceRecord> records = concurrentTask.poll();
                        recordsProcessed += records.size();
                        Thread.sleep(50);
                    }
                    
                    concurrentTask.stop();
                    results.put("task_" + taskId + "_records", recordsProcessed);
                    results.put("task_" + taskId + "_duration", System.currentTimeMillis() - startTime);
                    
                } catch (Exception e) {
                    log.error("Error in concurrent task {}", taskId, e);
                    results.put("task_" + taskId + "_error", e.getMessage());
                }
            }, executor);
            
            futures.add(future);
        }
        
        // Wait for all tasks to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(2, TimeUnit.MINUTES);
        
        executor.shutdown();
        
        // Analyze concurrent performance
        int totalRecords = 0;
        int successfulTasks = 0;
        
        for (int i = 0; i < numberOfTasks; i++) {
            Object records = results.get("task_" + i + "_records");
            Object error = results.get("task_" + i + "_error");
            
            if (error == null && records != null) {
                totalRecords += (Integer) records;
                successfulTasks++;
            }
        }
        
        ConcurrentLoadResult result = new ConcurrentLoadResult(
                numberOfTasks,
                successfulTasks,
                totalRecords,
                30000 // test duration
        );
        
        metrics.recordConcurrentTest(result);
        
        log.info("Concurrent Load Benchmark Results:");
        log.info("Tasks Started: {}", numberOfTasks);
        log.info("Tasks Successful: {}", successfulTasks);
        log.info("Total Records: {}", totalRecords);
        log.info("Concurrent Throughput: {:.2f} records/second", totalRecords / 30.0);
        
        // Assertions for concurrent performance
        assertTrue("At least 2/3 of tasks should succeed", successfulTasks >= (numberOfTasks * 2 / 3));
        assertTrue("Should process records concurrently", totalRecords > 0);
    }

    @Test
    public void testErrorRateUnderLoad() throws Exception {
        log.info("Starting error rate under load benchmark");
        
        Map<String, String> config = createPerformanceConfig();
        // Configure for higher error potential
        config.put("query.timeout.ms", "1000"); // Short timeout
        config.put("max.retries", "1"); // Fewer retries
        config.put("polling.interval.ms", "10"); // Aggressive polling
        
        task.start(config);
        
        long testDuration = 30000; // 30 seconds
        long startTime = System.currentTimeMillis();
        
        int totalPolls = 0;
        int successfulPolls = 0;
        int emptyResults = 0;
        List<Long> pollTimes = new ArrayList<>();
        
        while (System.currentTimeMillis() - startTime < testDuration) {
            long pollStart = System.currentTimeMillis();
            
            try {
                List<SourceRecord> records = task.poll();
                totalPolls++;
                
                if (records != null && !records.isEmpty()) {
                    successfulPolls++;
                } else {
                    emptyResults++;
                }
                
                long pollEnd = System.currentTimeMillis();
                pollTimes.add(pollEnd - pollStart);
                
            } catch (Exception e) {
                totalPolls++;
                log.debug("Poll error (expected in stress test): {}", e.getMessage());
            }
            
            // No sleep - stress test
        }
        
        double errorRate = ((totalPolls - successfulPolls) * 100.0) / totalPolls;
        double emptyRate = (emptyResults * 100.0) / totalPolls;
        OptionalDouble avgPollTime = pollTimes.stream().mapToLong(Long::longValue).average();
        
        ErrorRateResult result = new ErrorRateResult(
                totalPolls,
                successfulPolls,
                emptyResults,
                errorRate,
                emptyRate,
                avgPollTime.orElse(0)
        );
        
        metrics.recordErrorRateTest(result);
        
        log.info("Error Rate Under Load Results:");
        log.info("Total Polls: {}", totalPolls);
        log.info("Successful Polls: {}", successfulPolls);
        log.info("Empty Results: {}", emptyResults);
        log.info("Error Rate: {:.2f}%", errorRate);
        log.info("Empty Rate: {:.2f}%", emptyRate);
        log.info("Average Poll Time: {:.2f}ms", avgPollTime.orElse(0));
        
        // Assertions for error handling
        assertTrue("Error rate should be reasonable under load", errorRate < 50.0);
        assertTrue("Should complete at least 100 poll attempts", totalPolls >= 100);
        assertTrue("Some polls should succeed even under stress", successfulPolls > 0);
    }

    @Test
    public void testJMXMetricsPerformance() throws Exception {
        log.info("Starting JMX metrics performance benchmark");
        
        Map<String, String> config = createPerformanceConfig();
        task.start(config);
        
        // Let it run to generate some metrics
        for (int i = 0; i < 10; i++) {
            task.poll();
            Thread.sleep(100);
        }
        
        GraphQLSourceTaskMBean mbean = (GraphQLSourceTaskMBean) task;
        
        // Benchmark JMX method call performance
        int iterations = 1000;
        Map<String, Long> methodTimings = new HashMap<>();
        
        // Test different JMX methods
        methodTimings.put("getConnectorStatus", benchmarkJMXMethod(() -> mbean.getConnectorStatus(), iterations));
        methodTimings.put("getTotalRecordsProcessed", benchmarkJMXMethod(() -> mbean.getTotalRecordsProcessed(), iterations));
        methodTimings.put("getRecordsPerSecond", benchmarkJMXMethod(() -> mbean.getRecordsPerSecond(), iterations));
        methodTimings.put("getHealthSummary", benchmarkJMXMethod(() -> mbean.getHealthSummary(), iterations));
        methodTimings.put("getAverageQueryTimeMs", benchmarkJMXMethod(() -> mbean.getAverageQueryTimeMs(), iterations));
        methodTimings.put("getTotalGraphQLQueries", benchmarkJMXMethod(() -> mbean.getTotalGraphQLQueries(), iterations));
        
        JMXPerformanceResult result = new JMXPerformanceResult(methodTimings);
        metrics.recordJMXPerformanceTest(result);
        
        log.info("JMX Metrics Performance Results (avg over {} calls):", iterations);
        methodTimings.forEach((method, avgTime) -> 
            log.info("{}: {:.3f}ms", method, avgTime / 1_000_000.0)); // Convert to ms
        
        // Assert JMX performance
        methodTimings.forEach((method, avgTime) -> {
            double avgTimeMs = avgTime / 1_000_000.0;
            assertTrue("JMX method " + method + " should be fast (< 10ms)", avgTimeMs < 10.0);
        });
    }

    // Helper methods and classes

    private Map<String, String> createPerformanceConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("graphql.endpoint.url", GRAPHQL_ENDPOINT);
        config.put("graphql.query", 
                "query GetUsers($first: Int!, $after: String) { " +
                "users(first: $first, after: $after) { " +
                "edges { node { id name email createdAt status } cursor } " +
                "pageInfo { hasNextPage endCursor } } }");
        config.put("data.path", "users.edges[*].node");
        config.put("pagination.cursor.path", "users.pageInfo.endCursor");
        config.put("pagination.hasmore.path", "users.pageInfo.hasNextPage");
        config.put("record.key.path", "id");
        config.put("result.size", "50");
        config.put("polling.interval.ms", "500");
        config.put("kafka.topic.name", "performance_test");
        config.put("query.timeout.ms", "10000");
        config.put("max.retries", "3");
        config.put("retry.backoff.ms", "1000");
        
        return config;
    }

    private long benchmarkJMXMethod(Runnable method, int iterations) {
        // Warmup
        for (int i = 0; i < 10; i++) {
            method.run();
        }
        
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            method.run();
        }
        long endTime = System.nanoTime();
        
        return (endTime - startTime) / iterations; // Average time per call
    }

    private static void waitForService(String healthEndpoint, String serviceName) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        
        int maxAttempts = 30;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(healthEndpoint))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                
                HttpResponse<String> response = client.send(request, 
                        HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception e) {
                // Service not ready
            }
            Thread.sleep(1000);
        }
        
        throw new RuntimeException(serviceName + " is not ready");
    }

    // Performance result classes

    private static class ThroughputResult {
        final int totalRecords;
        final long durationMs;
        final double throughputPerSecond;
        final double avgResponseTimeMs;
        final long minResponseTimeMs;
        final long maxResponseTimeMs;

        ThroughputResult(int totalRecords, long durationMs, double throughputPerSecond,
                        double avgResponseTimeMs, long minResponseTimeMs, long maxResponseTimeMs) {
            this.totalRecords = totalRecords;
            this.durationMs = durationMs;
            this.throughputPerSecond = throughputPerSecond;
            this.avgResponseTimeMs = avgResponseTimeMs;
            this.minResponseTimeMs = minResponseTimeMs;
            this.maxResponseTimeMs = maxResponseTimeMs;
        }
    }

    private static class MemoryUsageResult {
        final long memoryBeforeBytes;
        final long memoryAfterBytes;
        final long memoryIncreaseBytes;
        final double avgMemoryUsageBytes;
        final long minMemoryUsageBytes;
        final long maxMemoryUsageBytes;

        MemoryUsageResult(long memoryBeforeBytes, long memoryAfterBytes, long memoryIncreaseBytes,
                         double avgMemoryUsageBytes, long minMemoryUsageBytes, long maxMemoryUsageBytes) {
            this.memoryBeforeBytes = memoryBeforeBytes;
            this.memoryAfterBytes = memoryAfterBytes;
            this.memoryIncreaseBytes = memoryIncreaseBytes;
            this.avgMemoryUsageBytes = avgMemoryUsageBytes;
            this.minMemoryUsageBytes = minMemoryUsageBytes;
            this.maxMemoryUsageBytes = maxMemoryUsageBytes;
        }
    }

    private static class ConcurrentLoadResult {
        final int tasksStarted;
        final int tasksSuccessful;
        final int totalRecords;
        final long testDurationMs;

        ConcurrentLoadResult(int tasksStarted, int tasksSuccessful, int totalRecords, long testDurationMs) {
            this.tasksStarted = tasksStarted;
            this.tasksSuccessful = tasksSuccessful;
            this.totalRecords = totalRecords;
            this.testDurationMs = testDurationMs;
        }
    }

    private static class ErrorRateResult {
        final int totalPolls;
        final int successfulPolls;
        final int emptyResults;
        final double errorRate;
        final double emptyRate;
        final double avgPollTimeMs;

        ErrorRateResult(int totalPolls, int successfulPolls, int emptyResults,
                       double errorRate, double emptyRate, double avgPollTimeMs) {
            this.totalPolls = totalPolls;
            this.successfulPolls = successfulPolls;
            this.emptyResults = emptyResults;
            this.errorRate = errorRate;
            this.emptyRate = emptyRate;
            this.avgPollTimeMs = avgPollTimeMs;
        }
    }

    private static class JMXPerformanceResult {
        final Map<String, Long> methodTimings;

        JMXPerformanceResult(Map<String, Long> methodTimings) {
            this.methodTimings = new HashMap<>(methodTimings);
        }
    }

    // Performance metrics collector
    private static class PerformanceMetrics {
        private final Instant testStartTime;
        private Instant testEndTime;
        private final Map<String, Object> results = new HashMap<>();
        
        public PerformanceMetrics() {
            this.testStartTime = Instant.now();
        }
        
        public void recordTestEnd() {
            this.testEndTime = Instant.now();
            saveResults();
        }
        
        public void recordThroughputTest(ThroughputResult result) {
            results.put("throughput", Map.of(
                "totalRecords", result.totalRecords,
                "durationMs", result.durationMs,
                "throughputPerSecond", result.throughputPerSecond,
                "avgResponseTimeMs", result.avgResponseTimeMs,
                "minResponseTimeMs", result.minResponseTimeMs,
                "maxResponseTimeMs", result.maxResponseTimeMs
            ));
        }
        
        public void recordMemoryTest(MemoryUsageResult result) {
            results.put("memoryUsage", Map.of(
                "memoryBeforeMB", result.memoryBeforeBytes / 1024.0 / 1024.0,
                "memoryAfterMB", result.memoryAfterBytes / 1024.0 / 1024.0,
                "memoryIncreaseMB", result.memoryIncreaseBytes / 1024.0 / 1024.0,
                "avgMemoryUsageMB", result.avgMemoryUsageBytes / 1024.0 / 1024.0,
                "maxMemoryUsageMB", result.maxMemoryUsageBytes / 1024.0 / 1024.0
            ));
        }
        
        public void recordConcurrentTest(ConcurrentLoadResult result) {
            results.put("concurrentLoad", Map.of(
                "tasksStarted", result.tasksStarted,
                "tasksSuccessful", result.tasksSuccessful,
                "totalRecords", result.totalRecords,
                "concurrentThroughputPerSecond", result.totalRecords / (result.testDurationMs / 1000.0)
            ));
        }
        
        public void recordErrorRateTest(ErrorRateResult result) {
            results.put("errorRate", Map.of(
                "totalPolls", result.totalPolls,
                "successfulPolls", result.successfulPolls,
                "errorRate", result.errorRate,
                "emptyRate", result.emptyRate,
                "avgPollTimeMs", result.avgPollTimeMs
            ));
        }
        
        public void recordJMXPerformanceTest(JMXPerformanceResult result) {
            Map<String, Double> timingsMs = new HashMap<>();
            result.methodTimings.forEach((method, nanos) -> 
                timingsMs.put(method, nanos / 1_000_000.0));
            results.put("jmxPerformance", timingsMs);
        }
        
        private void saveResults() {
            try {
                Map<String, Object> report = new HashMap<>();
                report.put("timestamp", testStartTime.toString());
                report.put("duration", Duration.between(testStartTime, testEndTime).toString());
                report.put("results", results);
                
                // Save to JSON file
                File resultsFile = new File("target/performance-results.json");
                resultsFile.getParentFile().mkdirs();
                
                try (FileWriter writer = new FileWriter(resultsFile)) {
                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(writer, report);
                }
                
                log.info("Performance results saved to: {}", resultsFile.getAbsolutePath());
                
            } catch (IOException e) {
                log.error("Failed to save performance results", e);
            }
        }
    }

    // Utility assertion methods
    private void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
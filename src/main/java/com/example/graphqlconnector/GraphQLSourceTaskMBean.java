package com.example.graphqlconnector;

/**
 * JMX MBean interface for GraphQL Source Task monitoring
 * Provides operational metrics and health information
 */
public interface GraphQLSourceTaskMBean {

    // Operational Status
    String getConnectorStatus();
    boolean isHealthy();
    boolean isCircuitBreakerOpen();
    int getConsecutiveFailures();
    String getLastFailureTime();
    
    // Performance Metrics  
    long getTotalRecordsProcessed();
    long getTotalPollCycles();
    long getTotalSuccessfulPollCycles();
    long getTotalFailedPollCycles();
    double getRecordsPerSecond();
    long getAverageQueryTimeMs();
    long getLastQueryTimeMs();
    
    // GraphQL Specific Metrics
    String getEntityName();
    String getGraphQLEndpoint();
    String getCurrentCursor();
    String getLastCommittedCursor();
    long getTotalGraphQLQueries();
    long getTotalGraphQLErrors();
    long getTotalRetryAttempts();
    
    // Resource Metrics
    int getActiveConnections();
    int getIdleConnections(); 
    long getTotalBytesReceived();
    
    // Error Tracking
    String getLastErrorMessage();
    String getLastErrorTime();
    double getErrorRate();
    
    // Configuration Info
    long getPollingIntervalMs();
    int getResultSize();
    String getSelectedColumns();
    
    // Operational Commands
    void resetMetrics();
    void resetErrorTracking();
    String getHealthSummary();
}
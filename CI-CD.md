# CI/CD Pipeline Documentation

This project uses GitHub Actions for continuous integration and deployment with a comprehensive testing strategy.

## Pipeline Overview

### 🚀 Main CI/CD Pipeline (`.github/workflows/ci.yml`)

Triggers on:
- Push to `main` or `develop` branches
- Pull requests to `main`

**Jobs:**
1. **Unit Tests** - Fast feedback with JUnit tests
2. **Integration Tests** - Full Docker environment testing
3. **Build Package** - Create deployable JAR artifacts
4. **Code Quality** - Coverage analysis and quality checks
5. **Security Scan** - Vulnerability scanning with Trivy
6. **Release** - Automatic releases on main branch

### 🔍 PR Checks (`.github/workflows/pr-check.yml`)

Optimized for fast PR feedback:
- **Quick Validation** - Compilation and unit tests
- **Smoke Tests** - Essential integration tests only
- **Code Coverage** - Coverage reports on PR comments

### 🌙 Nightly Builds (`.github/workflows/nightly.yml`)

Comprehensive testing every night:
- **Multi-Java Testing** - Java 17 and 21
- **Load Testing** - 30-minute performance validation
- **Extended Integration** - Full test matrix
- **Performance Monitoring** - Throughput and memory analysis

## Workflows Details

### Unit Tests
```yaml
- Fast execution (< 2 minutes)
- JUnit test reports uploaded
- JaCoCo coverage generation
- Maven test caching
```

### Integration Tests
```yaml
Services Started:
- Kafka (with Zookeeper)
- Mock GraphQL API
- Schema Registry
- Kafka Connect

Tests Cover:
- End-to-end data flow
- Error handling scenarios
- Circuit breaker functionality
- JMX monitoring validation
```

### Build & Package
```yaml
Artifacts:
- graphql-kafka-connector-*.jar
- Dependency tree
- Coverage reports
- Test results
```

### Security Scanning
```yaml
Tools:
- Trivy vulnerability scanner
- SARIF upload to GitHub Security
- Dependency vulnerability analysis
```

## Environment Requirements

### Required Secrets
No secrets required for basic functionality.

### Optional Secrets for Enhanced Features
```bash
# For Codecov integration
CODECOV_TOKEN=<your-codecov-token>

# For Slack notifications (if enabled)
SLACK_WEBHOOK_URL=<your-slack-webhook>
```

### Service Limits
```yaml
Resource Usage:
- Memory: 2GB per job
- CPU: 2 cores
- Disk: 14GB SSD
- Network: Unlimited
```

## Test Strategy

### 1. Unit Tests
- **Scope**: Individual class testing
- **Speed**: < 30 seconds
- **Coverage Target**: 80%
- **Mocking**: Mockito for external dependencies

### 2. Integration Tests  
- **Scope**: Full system testing
- **Speed**: 2-5 minutes
- **Environment**: Docker Compose
- **Services**: Kafka + GraphQL API

### 3. Load Tests (Nightly)
- **Duration**: 30 minutes
- **Volume**: 1000+ messages
- **Monitoring**: JMX metrics collection
- **Performance Baseline**: 10+ msg/sec

## Artifact Management

### Build Artifacts
```
target/graphql-kafka-connector-*.jar  # Main connector JAR
target/site/jacoco/                   # Coverage reports
target/surefire-reports/              # Unit test results
target/failsafe-reports/              # Integration test results
```

### Release Process
1. **Automatic Versioning**: `v{YYYY.MM.DD}-{commit}`
2. **GitHub Release**: Created automatically on main branch
3. **JAR Upload**: Connector JAR attached to release
4. **Release Notes**: Auto-generated with features and testing status

## Branch Protection

Recommended GitHub branch protection rules:
```yaml
main:
  required_status_checks:
    - "Unit Tests"
    - "Integration Tests" 
    - "Code Quality"
  required_reviews: 1
  dismiss_stale_reviews: true
  require_code_owner_reviews: true
```

## Monitoring & Notifications

### Build Status
- ✅ **Success**: All tests pass, artifacts created
- ⚠️ **Partial**: Some non-critical checks fail
- ❌ **Failure**: Critical tests fail, no artifacts

### Failure Notifications
```yaml
On Failure:
- GitHub status checks fail
- PR blocked from merging
- Service logs collected and uploaded
- Detailed error reports in artifacts
```

## Performance Benchmarks

### Nightly Load Test Results
```json
{
  "duration_seconds": 1800,
  "total_messages": 18000,
  "throughput_msg_per_sec": 10,
  "memory_usage_mb": 512,
  "cpu_usage_percent": 25
}
```

### Historical Metrics
- **Throughput Trend**: Tracked in nightly builds
- **Memory Usage**: Monitored for memory leaks
- **Error Rates**: Circuit breaker effectiveness

## Troubleshooting CI/CD

### Common Issues

**1. Integration Tests Timeout**
```bash
# Check service startup logs
docker-compose logs kafka-connect
docker-compose logs mock-graphql-api
```

**2. Maven Cache Issues**
```bash
# Force clean build
mvn clean install -U
```

**3. Docker Resource Limits**
```bash
# Check GitHub Actions runner resources
docker system df
docker stats --no-stream
```

### Manual Workflow Triggers

```bash
# Trigger nightly build manually
gh workflow run nightly.yml

# Re-run failed CI pipeline
gh run rerun <run-id>

# View workflow status
gh run list
```

## Local Development

### Run CI Locally
```bash
# Simulate unit tests
mvn clean test

# Simulate integration tests
./run-integration-tests.sh

# Simulate build process
mvn clean package -DskipTests
```

### Pre-Commit Validation
```bash
# Quick validation before pushing
mvn validate compile test-compile

# Full local validation
./run-integration-tests.sh --coverage
```

## Metrics & Analytics

The CI/CD pipeline tracks:
- **Build Success Rate**: Target > 95%
- **Test Duration**: Unit < 2min, Integration < 5min
- **Coverage Trend**: Maintain > 80%
- **Performance Regression**: Alert on 20% degradation

This comprehensive CI/CD setup ensures the GraphQL Kafka Connector maintains high quality and reliability through automated testing and monitoring.
# Enterprise Jenkins Shared Library (`enterprise-cicd`)

Reusable Jenkins Global Shared Library for Spring Boot + Gradle microservices deploying to GKE with Blue/Green slots, staged approvals, and Snyk security scanning.

## Structure
- `vars/springGkePipeline.groovy` — Build-Once, Promote-Many CI/CD pipeline (QG1-QG4 quality gates, single Artifact Registry build, sequential promotion across environments with Blue/Green deploy, executor-free manual approval gates that pause indefinitely until approved, traffic switch, smoke check, and old-slot deletion)
- `vars/snykScanPipeline.groovy` — Independent Snyk CLI vulnerability scan pipeline
- `resources/environments.json` — Central enterprise environment catalog mapping `artifactRegistry` and each environment (`dev1`, `dev2`, `test`, `perf`, `stage`, `prod`) to its own `gcpProjectId`, `gcpRegion`, `gkeCluster`, and namespace suffix
- `resources/k8s/deployment.yaml` & `resources/k8s/service.yaml` — Default parameterized Kubernetes manifests used automatically if a project repo does not provide its own `k8s/` folder

## Quality Gate Mapping (`QG`)
Each project repo controls which quality gates run via the `QG` option (or the `QG` parameter in Jenkins):
- **`QG1`** — Unit Tests (`./gradlew clean test`)
- **`QG2`** — Integration Tests (`./gradlew integrationTest`)
- **`QG3`** — Coverage Gate (`./gradlew jacocoTestReport jacocoTestCoverageVerification`)
- **`QG4`** — Security Scan (`<appName>-snyk-scan` job after first environment switch-live)

If a gate is included in `QG`, Jenkins executes it; if omitted, Jenkins skips that stage.

## Usage in Any Project Repo (Zero GCP Project IDs in App Repo)

### `Jenkinsfile`
```groovy
@Library('enterprise-cicd@main') _

springGkePipeline(
    appName:     'order-api',
    QG:          ['QG1', 'QG2', 'QG3', 'QG4'],
    promoteTo:   ['dev1', 'dev2'], // or ['dev1', 'test', 'perf', 'stage', 'prod']
    healthPath:  '/actuator/health',
    smokePath:   '/api/orders/1001',
    smokeExpect: '"orderId":1001'
)
```

### `jenkins/Jenkinsfile.snyk`
```groovy
@Library('enterprise-cicd@main') _

snykScanPipeline(
    severity: 'high'
)
```

# Enterprise Jenkins Shared Library (`enterprise-cicd`)

Reusable Jenkins Global Shared Library for Spring Boot + Gradle microservices deploying to GKE with Blue/Green slots, staged approvals, and Snyk security scanning.

## Structure
- `vars/springGkePipeline.groovy` — Full CI/CD pipeline (Unit, Integration, JaCoCo Coverage Gate, Cloud Build, GKE Blue/Green deploy + approval + switch + smoke + old-slot deletion for `dev1` & `dev2`, and non-blocking Snyk scan trigger)
- `vars/snykScanPipeline.groovy` — Independent Snyk CLI vulnerability scan pipeline
- `resources/k8s/deployment.yaml` & `resources/k8s/service.yaml` — Default parameterized Kubernetes manifests used automatically if a project repo does not provide its own `k8s/` folder

## Quality Gate Mapping (`QG`)
Each project repo controls which quality gates run via the `QG` option (or the `QG` parameter in Jenkins):
- **`QG1`** — Unit Tests (`./gradlew clean test`)
- **`QG2`** — Integration Tests (`./gradlew integrationTest`)
- **`QG3`** — Coverage Gate (`./gradlew jacocoTestReport jacocoTestCoverageVerification`)
- **`QG4`** — Security Scan (`order-api-snyk-scan` job after `dev1` switch-live)

If a gate is included in `QG`, Jenkins executes it; if omitted, Jenkins skips that stage.

## Usage in Any Project Repo

### `Jenkinsfile`
```groovy
@Library('enterprise-cicd@main') _

springGkePipeline(
    appName:     'order-api',
    QG:          ['QG1', 'QG2', 'QG3', 'QG4'], // Remove any gate to skip it
    dev1Ns:      'order-api-dev1',
    dev2Ns:      'order-api-dev2',
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

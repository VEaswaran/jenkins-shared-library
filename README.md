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

---

## Approval Gates & Executor-Free Pause Architecture (Option B)

`springGkePipeline` separates **worker execution (`node` / `agent any`)** from **human approval waits (`input` outside `node`)** within a single build run so builds never expire while waiting for approval and never block Jenkins executors.

### Execution Lifecycle
```text
[Stage: CI & Build Image]  --> Allocates 1 Executor (agent any)
  ├── Checkout
  ├── QG1 - Unit Tests
  ├── QG2 - Integration Tests
  ├── QG3 - Coverage Gate
  └── Build & Push Image (Once) -> Publishes immutable IMAGE_URI
                           --> Releases Executor

[Stage: Promote Across Environments (agent none)]
  ├── [dev1] Deploy & Verify Slot     --> Acquires Executor (node), deploys & checks health, then Releases Executor
  ├── [dev1] Approve Switch Live      --> 0 Executors held | Pauses indefinitely until "Switch Live (dev1)" clicked
  ├── [dev1] Switch Live              --> Acquires Executor (node), switches traffic & deletes old slot, Releases Executor
  ├── [dev1] QG4 - Security Scan      --> Triggers independent Snyk job
  │
  ├── [dev2] Approve Promote -> dev2  --> 0 Executors held | Pauses indefinitely until "Deploy to dev2" clicked
  ├── [dev2] Deploy & Verify Slot     --> Acquires Executor (node), deploys & checks health, then Releases Executor
  ├── [dev2] Approve Switch Live      --> 0 Executors held | Pauses indefinitely until "Switch Live (dev2)" clicked
  └── [dev2] Switch Live              --> Acquires Executor (node), switches traffic & deletes old slot, Releases Executor
```

### Key Characteristics
1. **No Expiration by Default (`approvalTimeoutMinutes: 0`)**:
   - Approval stages (`Approve Promote -> <env>` and `Approve Switch Live - <env>`) pause indefinitely until an approver clicks the **Approve** (`Deploy to <env>` / `Switch Live (<env>)`) or **Abort** button in Jenkins UI.
   - To optionally enforce an expiration window in a specific repo, pass `approvalTimeoutMinutes` (e.g., `approvalTimeoutMinutes: 10080` for 7 days).
2. **Zero Executor Consumption While Paused**:
   - Root pipeline uses `agent none`.
   - `input` steps execute via lightweight flyweight threads outside `node { ... }` blocks, immediately freeing Jenkins worker executors (`Idle`) for other builds or Snyk security scans.
3. **Seamless State Persistence Across `node` Blocks**:
   - Build-scoped environment variables (`env.IMAGE_URI`, `env.<ENV>_LIVE_SLOT`, `env.<ENV>_TARGET_SLOT`, `env.<ENV>_LIVE_IMG`, `env.<ENV>_LB_IP`) persist across executor releases.
   - Kubernetes templates (`k8s/deployment.yaml`, `k8s/service.yaml`) are materialized on demand via `ensureManifestsOnDisk()` from `resources/k8s/` whenever a `node` is acquired.


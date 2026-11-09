import groovy.json.JsonSlurperClassic

def call(Map cfg = [:]) {
    String appName     = cfg.appName     ?: 'order-api'
    String arRepo      = cfg.arRepo      ?: appName
    String imageName   = cfg.imageName   ?: appName
    String snykScanJob = cfg.snykScanJob ?: "${appName}-snyk-scan"
    String healthPath  = cfg.healthPath  ?: '/actuator/health'
    String smokePath   = cfg.smokePath   ?: '/api/orders/1001'
    String smokeExpect = cfg.smokeExpect ?: '"orderId":1001'
    int approvalTimeoutMinutes = (cfg.approvalTimeoutMinutes != null) ? (cfg.approvalTimeoutMinutes as int) : 0

    // Promotion chain (Build Once -> Promote across environments, each with its own GCP Project & GKE Cluster in resources/environments.json)
    def rawEnvs = cfg.promoteTo != null ? cfg.promoteTo : (cfg.environments != null ? cfg.environments : ['dev1', 'dev2'])
    List<String> repoEnvList = (rawEnvs instanceof Collection)
            ? rawEnvs.collect { it.toString().trim().toLowerCase() }
            : rawEnvs.toString().split(',').collect { it.trim().toLowerCase() }.findAll { it }
    String defaultEnvsCsv = repoEnvList.join(',')

    // Quality Gate mapping:
    //   QG1 = Unit Tests
    //   QG2 = Integration Tests
    //   QG3 = Coverage Gate (JaCoCo)
    //   QG4 = Security Scan (Snyk)
    def rawQg = cfg.QG != null ? cfg.QG : (cfg.qualityGates != null ? cfg.qualityGates : ['QG1', 'QG2', 'QG3', 'QG4'])
    List<String> repoQgList = (rawQg instanceof Collection)
            ? rawQg.collect { it.toString().trim().toUpperCase() }
            : rawQg.toString().split(',').collect { it.trim().toUpperCase() }.findAll { it }
    String defaultQgCsv = repoQgList.join(',')

    pipeline {
        agent none

        parameters {
            string(name: 'QG', defaultValue: defaultQgCsv,
                   description: 'Enabled Quality Gates (comma-separated): QG1=Unit Tests, QG2=Integration Tests, QG3=Coverage Gate, QG4=Snyk Security Scan')
            string(name: 'PROMOTE_ENVS', defaultValue: defaultEnvsCsv,
                   description: 'Ordered promotion environments (from resources/environments.json): e.g. dev1,dev2 or dev1,test,perf,stage,prod')
            string(name: 'AR_REPO', defaultValue: arRepo, description: 'Central Artifact Registry Repository')
            string(name: 'IMAGE_NAME', defaultValue: imageName, description: 'Container Image Name')
        }

        environment {
            APP_NAME = "${appName}"
            ENABLED_QG = "${params.QG != null ? params.QG : defaultQgCsv}"
            PROMOTE_CHAIN = "${params.PROMOTE_ENVS != null ? params.PROMOTE_ENVS : defaultEnvsCsv}"
            USE_GKE_GCLOUD_AUTH_PLUGIN = 'True'
            SNYK_SCAN_JOB = "${snykScanJob}"
            HEALTH_PATH = "${healthPath}"
            SMOKE_PATH = "${smokePath}"
            SMOKE_EXPECT = "${smokeExpect}"
        }

        stages {
            stage('CI & Build Image') {
                agent any
                stages {
                    stage('Checkout') {
                        steps {
                            checkout scm
                            script {
                                Map catalog = loadCatalog()
                                Map regCfg  = (catalog.artifactRegistry ?: [:]) as Map
                                String regProject = cfg.registryProjectId ?: (regCfg.gcpProjectId ?: 'project-d7b18774-cce1-4b87-a4a')
                                String regRegion  = cfg.registryRegion    ?: (regCfg.gcpRegion    ?: 'us-central1')
                                String gitShort   = env.GIT_COMMIT ? env.GIT_COMMIT.take(7) : (isUnix()
                                        ? sh(returnStdout: true, script: 'git rev-parse --short=7 HEAD 2>/dev/null || echo latest').trim()
                                        : 'latest')
                                env.IMAGE_TAG           = "${env.BUILD_NUMBER}-${gitShort ?: 'latest'}"
                                env.REGISTRY_PROJECT_ID = regProject
                                env.REGISTRY_REGION     = regRegion
                                env.IMAGE_URI           = "${regRegion}-docker.pkg.dev/${regProject}/${params.AR_REPO}/${params.IMAGE_NAME}:${env.IMAGE_TAG}"
                                env.IS_UNIX_AGENT       = isUnix().toString()
                                echo "App: ${env.APP_NAME} | QG: ${env.ENABLED_QG} | Promotion Chain: ${env.PROMOTE_CHAIN}"
                                echo "Single immutable image for all environments: ${env.IMAGE_URI}"
                            }
                        }
                    }

                    stage('QG1 - Unit Tests') {
                        when { expression { return isGateEnabled('QG1') } }
                        steps {
                            script {
                                if (isUnix()) {
                                    sh 'chmod +x gradlew && ./gradlew clean test --no-daemon'
                                } else {
                                    bat 'gradlew.bat clean test --no-daemon'
                                }
                            }
                        }
                        post {
                            always {
                                junit allowEmptyResults: true, testResults: 'build/test-results/test/*.xml'
                            }
                        }
                    }

                    stage('QG2 - Integration Tests') {
                        when { expression { return isGateEnabled('QG2') } }
                        steps {
                            script {
                                if (isUnix()) {
                                    sh 'chmod +x gradlew && ./gradlew integrationTest --no-daemon'
                                } else {
                                    bat 'gradlew.bat integrationTest --no-daemon'
                                }
                            }
                        }
                        post {
                            always {
                                junit allowEmptyResults: true, testResults: 'build/test-results/integrationTest/*.xml'
                            }
                        }
                    }

                    stage('QG3 - Coverage Gate') {
                        when { expression { return isGateEnabled('QG3') } }
                        steps {
                            script {
                                if (isUnix()) {
                                    sh 'chmod +x gradlew && ./gradlew jacocoTestReport jacocoTestCoverageVerification --no-daemon'
                                } else {
                                    bat 'gradlew.bat jacocoTestReport jacocoTestCoverageVerification --no-daemon'
                                }
                            }
                        }
                        post {
                            always {
                                archiveArtifacts artifacts: 'build/reports/jacoco/test/html/**', allowEmptyArchive: true
                            }
                        }
                    }

                    stage('Build & Push Image (Once)') {
                        steps {
                            script {
                                Map catalog = loadCatalog()
                                Map envsMap = (catalog.environments ?: [:]) as Map
                                if (isUnix()) {
                                    sh """
                                        gcloud config set project ${env.REGISTRY_PROJECT_ID}
                                        gcloud builds submit --tag ${env.IMAGE_URI} .
                                    """
                                } else {
                                    Map firstEnv = resolveEnvConfig(envsMap, 'dev1', appName, cfg)
                                    powershell """
                                        \\$ErrorActionPreference = 'Stop'
                                        .\\deploy-gke.ps1 `
                                            -ProjectId '${firstEnv.gcpProjectId}' `
                                            -Region '${firstEnv.gcpRegion}' `
                                            -ClusterName '${firstEnv.gkeCluster}' `
                                            -Namespace '${firstEnv.namespace}' `
                                            -Repository '${params.AR_REPO}' `
                                            -ImageName '${params.IMAGE_NAME}' `
                                            -Tag '${env.IMAGE_TAG}'
                                    """
                                }
                            }
                        }
                    }
                }
            }

            stage('Promote Across Environments') {
                when { expression { return env.IS_UNIX_AGENT == 'true' } }
                steps {
                    script {
                        Map catalog = loadCatalog()
                        Map envsMap = (catalog.environments ?: [:]) as Map
                        List<String> chain = (env.PROMOTE_CHAIN ?: '')
                                .split(',')
                                .collect { it.trim().toLowerCase() }
                                .findAll { it }
                        String prevEnvInfo = ''

                        for (int idx = 0; idx < chain.size(); idx++) {
                            String envKey = chain[idx]
                            String label  = envKey.toUpperCase()
                            Map targetCfg = resolveEnvConfig(envsMap, envKey, appName, cfg)

                            if (idx > 0) {
                                // Runs outside node {} -> holds 0 executors while paused waiting for approval
                                stage("Approve Promote -> ${envKey}") {
                                    String promoteMsg = "${prevEnvInfo}. Promote immutable image ${env.IMAGE_URI} to '${envKey}' (project: ${targetCfg.gcpProjectId}, cluster: ${targetCfg.gkeCluster}, ns: ${targetCfg.namespace})? Deploys to INACTIVE slot first."
                                    awaitApproval(promoteMsg, "Deploy to ${envKey}", approvalTimeoutMinutes)
                                }
                            }

                            node {
                                stage("Deploy to ${envKey}") {
                                    deployEnv(targetCfg, label)
                                }

                                stage("Verify Liveness & Readiness - ${envKey}") {
                                    verifySlotLivenessAndReadiness(targetCfg, label)
                                }
                            }

                            // Runs outside node {} -> holds 0 executors while paused waiting for approval
                            stage("Approve Switch Live - ${envKey}") {
                                String liveSlot   = env."${label}_LIVE_SLOT"
                                String targetSlot = env."${label}_TARGET_SLOT"
                                String liveImg    = env."${label}_LIVE_IMG"
                                String lbIp       = env."${label}_LB_IP"
                                String switchMsg  = "[${envKey} | project=${targetCfg.gcpProjectId} | ns=${targetCfg.namespace}] Liveness=UP & Readiness=UP verified on slot '${targetSlot}' (${env.IMAGE_URI}). Current live: slot '${liveSlot}' (${liveImg} @ http://${lbIp}). Approve = traffic -> '${targetSlot}', then '${liveSlot}' removed."
                                awaitApproval(switchMsg, "Switch Live (${envKey})", approvalTimeoutMinutes)
                            }

                            node {
                                stage("Switch Live - ${envKey}") {
                                    switchLiveAndSmoke(targetCfg, label)
                                    prevEnvInfo = "${envKey} is live on slot '${env."${label}_TARGET_SLOT"}' @ http://${env."${label}_LB_IP"}"
                                }
                            }

                            if (idx == 0 && isGateEnabled('QG4')) {
                                stage("QG4 - Security Scan - ${envKey}") {
                                    echo "Triggering independent scan job '${env.SNYK_SCAN_JOB}' for ${env.IMAGE_URI} (non-blocking - findings reported, build continues)"
                                    try {
                                        def scan = build job: env.SNYK_SCAN_JOB, wait: true, propagate: false,
                                                  parameters: [string(name: 'IMAGE_URI', value: env.IMAGE_URI)]
                                        if (scan.result != 'SUCCESS') {
                                            echo "SECURITY SCAN result: ${scan.result} - proceeding anyway (advisory only)"
                                            currentBuild.result = 'UNSTABLE'
                                        } else {
                                            echo "SECURITY SCAN passed"
                                        }
                                    } catch (Exception e) {
                                        echo "Security scan could not run: ${e.message} - proceeding anyway"
                                        currentBuild.result = 'UNSTABLE'
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                node {
                    script {
                        if (isUnix()) {
                            sh '''
                                ps -eo pid,ppid,etimes,comm \
                                  | awk '$2 == 1 && $3 > 1800 && ($4 == "sh" || $4 == "bash") { print $1 }' \
                                  | xargs -r kill -9 2>/dev/null || true
                            '''
                        }
                    }
                }
            }
        }
    }
}

def awaitApproval(String msg, String okLabel, int timeoutMinutes = 0) {
    if (timeoutMinutes > 0) {
        timeout(time: timeoutMinutes, unit: 'MINUTES') {
            input message: msg, ok: okLabel
        }
    } else {
        input message: msg, ok: okLabel
    }
}

def loadCatalog() {
    return new JsonSlurperClassic().parseText(libraryResource('environments.json')) as Map
}

def resolveEnvConfig(Map envsMap, String envKey, String appName, Map cfg) {
    Map raw = (envsMap[envKey] ?: [:]) as Map
    String suffix = raw.namespaceSuffix ?: envKey
    String customNs = cfg."${envKey}Ns" ?: "${appName}-${suffix}"
    return [
        envKey      : envKey,
        gcpProjectId: (raw.gcpProjectId ?: 'project-d7b18774-cce1-4b87-a4a') as String,
        gcpRegion   : (raw.gcpRegion    ?: 'us-central1') as String,
        gkeCluster  : (raw.gkeCluster   ?: 'order-api-cluster') as String,
        namespace   : customNs as String
    ]
}

def isGateEnabled(String gate) {
    String raw = env.ENABLED_QG ?: ''
    List<String> gates = raw.split(',').collect { it.trim().toUpperCase() }.findAll { it }
    return gates.contains(gate.toUpperCase()) || gates.contains('ALL')
}

def activeSlot(String ns) {
    String live = sh(returnStdout: true,
            script: "kubectl get service ${env.APP_NAME} -n ${ns} -o jsonpath='{.spec.selector.slot}' 2>/dev/null || true").trim()
    return live ?: ''
}

def ensureManifestsOnDisk() {
    if (!fileExists('k8s/deployment.yaml')) {
        sh 'mkdir -p k8s'
        writeFile file: 'k8s/deployment.yaml', text: libraryResource('k8s/deployment.yaml')
    }
    if (!fileExists('k8s/service.yaml')) {
        sh 'mkdir -p k8s'
        writeFile file: 'k8s/service.yaml', text: libraryResource('k8s/service.yaml')
    }
}

def deployEnv(Map targetCfg, String label) {
    ensureManifestsOnDisk()
    String ns = targetCfg.namespace
    sh "gcloud container clusters get-credentials ${targetCfg.gkeCluster} --region=${targetCfg.gcpRegion} --project=${targetCfg.gcpProjectId} -q"
    sh "kubectl get namespace ${ns} >/dev/null 2>&1 || kubectl create namespace ${ns}"
    String live = activeSlot(ns)
    String target = live ? (live == 'blue' ? 'green' : 'blue') : 'blue'
    String liveImg = live ? sh(returnStdout: true,
            script: "kubectl get deployment ${env.APP_NAME}-${live} -n ${ns} -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true").trim() : ''
    String lbIp = sh(returnStdout: true,
            script: "kubectl get service ${env.APP_NAME} -n ${ns} -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true").trim()
    env."${label}_LIVE_SLOT" = live ?: 'none'
    env."${label}_TARGET_SLOT" = target
    env."${label}_LIVE_IMG" = liveImg ?: 'none'
    env."${label}_LB_IP" = lbIp ?: 'pending'
    echo "[${label}] project=${targetCfg.gcpProjectId} cluster=${targetCfg.gkeCluster} ns=${ns}: LIVE slot='${live ?: 'none'}' (${liveImg ?: 'none'}) | NEW build ${env.IMAGE_URI} -> slot '${target}' | app URL http://${lbIp ?: 'pending'} (traffic NOT switched yet)"
    sh """
        sed -e "s|APP_PLACEHOLDER|${env.APP_NAME}|g" -e "s|IMAGE_PLACEHOLDER|${env.IMAGE_URI}|g" -e "s|SLOT_PLACEHOLDER|${target}|g" k8s/deployment.yaml | kubectl apply -n ${ns} -f -
        kubectl rollout status deployment/${env.APP_NAME}-${target} -n ${ns} --timeout=10m
        kubectl wait --for=condition=ready pod -l app=${env.APP_NAME},slot=${target} -n ${ns} --timeout=5m
    """
}

def verifySlotLivenessAndReadiness(Map targetCfg, String label) {
    String ns = targetCfg.namespace
    String target = env."${label}_TARGET_SLOT"
    echo "[${label}] Validating Liveness, Readiness & Smoke on inactive slot '${target}' in ${ns} BEFORE allowing Switch Live approval..."
    sh """
        set -e
        POD=\$(kubectl get pod -n ${ns} -l app=${env.APP_NAME},slot=${target} -o jsonpath='{.items[0].metadata.name}')
        [ -n "\$POD" ] || { echo "PRE-SWITCH CHECK FAILED: no pod found for slot '${target}' in ${ns}"; exit 1; }
        echo "[${label}] Checking pod \$POD (slot='${target}')..."

        LIVENESS=\$(kubectl get --raw "/api/v1/namespaces/${ns}/pods/\${POD}:8080/proxy/actuator/health/liveness")
        echo "[${label}] /actuator/health/liveness -> \$LIVENESS"
        echo "\$LIVENESS" | grep -q '"status":"UP"' || { echo "PRE-SWITCH LIVENESS FAILED on \$POD"; exit 1; }

        READINESS=\$(kubectl get --raw "/api/v1/namespaces/${ns}/pods/\${POD}:8080/proxy/actuator/health/readiness")
        echo "[${label}] /actuator/health/readiness -> \$READINESS"
        echo "\$READINESS" | grep -q '"status":"UP"' || { echo "PRE-SWITCH READINESS FAILED on \$POD"; exit 1; }

        SMOKE=\$(kubectl get --raw "/api/v1/namespaces/${ns}/pods/\${POD}:8080/proxy${env.SMOKE_PATH}")
        echo "\$SMOKE" | grep -q '${env.SMOKE_EXPECT}' || { echo "PRE-SWITCH SMOKE FAILED on \$POD (${env.SMOKE_PATH})"; exit 1; }
        echo "[${label}] PRE-SWITCH VALIDATION PASSED on slot '${target}' (pod=\$POD): Liveness=UP, Readiness=UP, Smoke=${env.SMOKE_PATH} OK"
    """
}

def switchLiveAndSmoke(Map targetCfg, String label) {
    ensureManifestsOnDisk()
    String ns = targetCfg.namespace
    String target = env."${label}_TARGET_SLOT"
    String oldSlot = env."${label}_LIVE_SLOT"
    echo "[${label}] Switching service selector in ${ns} (project=${targetCfg.gcpProjectId}) -> slot '${target}'"
    sh "sed -e 's|APP_PLACEHOLDER|${env.APP_NAME}|g' -e 's|SLOT_PLACEHOLDER|${target}|g' k8s/service.yaml | kubectl apply -n ${ns} -f -"
    String ip = ''
    for (int i = 0; i < 30 && !ip; i++) {
        ip = sh(returnStdout: true,
                script: "kubectl get service ${env.APP_NAME} -n ${ns} -o jsonpath='{.status.loadBalancer.ingress[0].ip}'").trim()
        if (!ip) { sleep time: 10, unit: 'SECONDS' }
    }
    if (!ip) { error "LoadBalancer IP not assigned for ${ns}" }
    env."${label}_LB_IP" = ip
    sh "kubectl get endpoints ${env.APP_NAME} -n ${ns}"
    echo "SWITCHED LIVE: ${ns} (project=${targetCfg.gcpProjectId}) -> slot '${target}' (${env.IMAGE_URI}) @ http://${ip}"
    sh """
        set -e
        healthy=""
        for i in \$(seq 1 30); do
            if curl -fs "http://${ip}${env.HEALTH_PATH}" 2>/dev/null | grep -q UP; then healthy=1; break; fi
            sleep 10
        done
        [ -n "\$healthy" ] || { echo "SMOKE FAILED: ${ns} ${env.HEALTH_PATH}"; exit 1; }
        curl -fs "http://${ip}${env.SMOKE_PATH}" | grep -q '${env.SMOKE_EXPECT}' || { echo "SMOKE FAILED: ${ns} ${env.SMOKE_PATH}"; exit 1; }
        echo "SMOKE PASSED (${ns}): http://${ip}${env.SMOKE_PATH}"
    """
    if (oldSlot && oldSlot != 'none') {
        sh "kubectl delete deployment ${env.APP_NAME}-${oldSlot} -n ${ns} --ignore-not-found"
        echo "[${label}] Old slot '${oldSlot}' deployment removed - only '${target}' remains running"
    }
    currentBuild.description = (currentBuild.description ? currentBuild.description + ' | ' : '') + "${ns} slot=${target}: http://${ip}"
}

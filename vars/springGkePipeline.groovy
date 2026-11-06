def call(Map cfg = [:]) {
    String appName      = cfg.appName      ?: 'order-api'
    String gcpProjectId = cfg.gcpProjectId ?: 'project-d7b18774-cce1-4b87-a4a'
    String gcpRegion    = cfg.gcpRegion    ?: 'us-central1'
    String gkeCluster   = cfg.gkeCluster   ?: 'order-api-cluster'
    String arRepo       = cfg.arRepo       ?: appName
    String imageName    = cfg.imageName    ?: appName
    String dev1Ns       = cfg.dev1Ns       ?: "${appName}-dev1"
    String dev2Ns       = cfg.dev2Ns       ?: "${appName}-dev2"
    String snykScanJob  = cfg.snykScanJob  ?: "${appName}-snyk-scan"
    String healthPath   = cfg.healthPath   ?: '/actuator/health'
    String smokePath    = cfg.smokePath    ?: '/api/orders/1001'
    String smokeExpect  = cfg.smokeExpect  ?: '"orderId":1001'

    pipeline {
        agent any

        parameters {
            string(name: 'GCP_PROJECT_ID', defaultValue: gcpProjectId, description: 'GCP Project ID')
            string(name: 'GCP_REGION', defaultValue: gcpRegion, description: 'GCP Region')
            string(name: 'GKE_CLUSTER', defaultValue: gkeCluster, description: 'GKE Cluster Name')
            string(name: 'AR_REPO', defaultValue: arRepo, description: 'Artifact Registry Repository')
            string(name: 'IMAGE_NAME', defaultValue: imageName, description: 'Container Image Name')
        }

        environment {
            APP_NAME = "${appName}"
            IMAGE_TAG = "${env.BUILD_NUMBER}-${env.GIT_COMMIT ? env.GIT_COMMIT.take(7) : 'latest'}"
            IMAGE_URI = "${params.GCP_REGION}-docker.pkg.dev/${params.GCP_PROJECT_ID}/${params.AR_REPO}/${params.IMAGE_NAME}:${IMAGE_TAG}"
            USE_GKE_GCLOUD_AUTH_PLUGIN = 'True'
            DEV1_NS = "${dev1Ns}"
            DEV2_NS = "${dev2Ns}"
            SNYK_SCAN_JOB = "${snykScanJob}"
            HEALTH_PATH = "${healthPath}"
            SMOKE_PATH = "${smokePath}"
            SMOKE_EXPECT = "${smokeExpect}"
        }

        stages {
            stage('Checkout') {
                steps {
                    checkout scm
                }
            }

            stage('Unit Tests') {
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

            stage('Integration Tests') {
                steps {
                    script {
                        if (isUnix()) {
                            sh './gradlew integrationTest --no-daemon'
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

            stage('Coverage Gate') {
                steps {
                    script {
                        if (isUnix()) {
                            sh './gradlew jacocoTestReport jacocoTestCoverageVerification --no-daemon'
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

            stage('Build & Push Image') {
                steps {
                    script {
                        if (isUnix()) {
                            sh """
                                gcloud config set project ${params.GCP_PROJECT_ID}
                                gcloud builds submit --tag ${env.IMAGE_URI} .
                            """
                        } else {
                            powershell """
                                \\$ErrorActionPreference = 'Stop'
                                .\\deploy-gke.ps1 `
                                    -ProjectId '${params.GCP_PROJECT_ID}' `
                                    -Region '${params.GCP_REGION}' `
                                    -ClusterName '${params.GKE_CLUSTER}' `
                                    -Namespace '${env.DEV1_NS}' `
                                    -Repository '${params.AR_REPO}' `
                                    -ImageName '${params.IMAGE_NAME}' `
                                    -Tag '${env.IMAGE_TAG}'
                            """
                        }
                    }
                }
            }

            stage('Deploy to dev1') {
                when { expression { return isUnix() } }
                steps {
                    script { deployEnv(env.DEV1_NS, 'DEV1') }
                }
            }

            stage('Approve Switch Live - dev1') {
                when { expression { return isUnix() } }
                steps {
                    script {
                        String msg = "[dev1 / ${env.DEV1_NS}] NEW build ${env.IMAGE_URI} is Ready on slot '${env.DEV1_TARGET_SLOT}' (live: slot '${env.DEV1_LIVE_SLOT}' = ${env.DEV1_LIVE_IMG} @ http://${env.DEV1_LB_IP}). Approve = traffic -> '${env.DEV1_TARGET_SLOT}', then '${env.DEV1_LIVE_SLOT}' removed."
                        timeout(time: 15, unit: 'MINUTES') {
                            input message: msg, ok: 'Switch Live'
                        }
                    }
                }
            }

            stage('Switch Live - dev1') {
                when { expression { return isUnix() } }
                steps {
                    script { switchLiveAndSmoke(env.DEV1_NS, 'DEV1') }
                }
            }

            stage('Security Scan - dev1') {
                when { expression { return isUnix() } }
                steps {
                    script {
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

            stage('Approve Deploy - dev2') {
                when { expression { return isUnix() } }
                steps {
                    script {
                        String msg = "dev1 is live on slot '${env.DEV1_TARGET_SLOT}' @ http://${env.DEV1_LB_IP}. Deploy ${env.IMAGE_URI} to dev2 / ${env.DEV2_NS}? It goes to dev2's INACTIVE slot first - not live until you approve."
                        timeout(time: 15, unit: 'MINUTES') {
                            input message: msg, ok: 'Deploy to dev2'
                        }
                    }
                }
            }

            stage('Deploy to dev2') {
                when { expression { return isUnix() } }
                steps {
                    script { deployEnv(env.DEV2_NS, 'DEV2') }
                }
            }

            stage('Approve Switch Live - dev2') {
                when { expression { return isUnix() } }
                steps {
                    script {
                        String msg = "[dev2 / ${env.DEV2_NS}] NEW build ${env.IMAGE_URI} is Ready on slot '${env.DEV2_TARGET_SLOT}' (live: slot '${env.DEV2_LIVE_SLOT}' = ${env.DEV2_LIVE_IMG} @ http://${env.DEV2_LB_IP}). Approve = traffic -> '${env.DEV2_TARGET_SLOT}', then '${env.DEV2_LIVE_SLOT}' removed."
                        timeout(time: 15, unit: 'MINUTES') {
                            input message: msg, ok: 'Switch Live'
                        }
                    }
                }
            }

            stage('Switch Live - dev2') {
                when { expression { return isUnix() } }
                steps {
                    script { switchLiveAndSmoke(env.DEV2_NS, 'DEV2') }
                }
            }
        }

        post {
            always {
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

def deployEnv(String ns, String label) {
    ensureManifestsOnDisk()
    sh "gcloud container clusters get-credentials ${params.GKE_CLUSTER} --region=${params.GCP_REGION} --project=${params.GCP_PROJECT_ID} -q"
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
    echo "[${label}] ${ns}: LIVE slot='${live ?: 'none'}' (${liveImg ?: 'none'}) | NEW build ${env.IMAGE_URI} -> slot '${target}' | app URL http://${lbIp ?: 'pending'} (traffic NOT switched yet)"
    sh """
        sed -e "s|APP_PLACEHOLDER|${env.APP_NAME}|g" -e "s|IMAGE_PLACEHOLDER|${env.IMAGE_URI}|g" -e "s|SLOT_PLACEHOLDER|${target}|g" k8s/deployment.yaml | kubectl apply -n ${ns} -f -
        kubectl rollout status deployment/${env.APP_NAME}-${target} -n ${ns} --timeout=10m
        kubectl wait --for=condition=ready pod -l app=${env.APP_NAME},slot=${target} -n ${ns} --timeout=5m
    """
}

def switchLiveAndSmoke(String ns, String label) {
    ensureManifestsOnDisk()
    String target = env."${label}_TARGET_SLOT"
    String oldSlot = env."${label}_LIVE_SLOT"
    echo "[${label}] Switching service selector -> slot '${target}'"
    sh "sed -e 's|APP_PLACEHOLDER|${env.APP_NAME}|g' -e 's|SLOT_PLACEHOLDER|${target}|g' k8s/service.yaml | kubectl apply -n ${ns} -f -"
    String ip = ''
    for (int i = 0; i < 30 && !ip; i++) {
        ip = sh(returnStdout: true,
                script: "kubectl get service ${env.APP_NAME} -n ${ns} -o jsonpath='{.status.loadBalancer.ingress[0].ip}'").trim()
        if (!ip) { sleep time: 10, unit: 'SECONDS' }
    }
    if (!ip) { error "LoadBalancer IP not assigned for ${ns}" }
    sh "kubectl get endpoints ${env.APP_NAME} -n ${ns}"
    echo "SWITCHED LIVE: ${ns} -> slot '${target}' (${env.IMAGE_URI}) @ http://${ip}"
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

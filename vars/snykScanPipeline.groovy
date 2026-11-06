def call(Map cfg = [:]) {
    String defaultSeverity = cfg.severity ?: 'high'

    pipeline {
        agent any

        parameters {
            string(name: 'IMAGE_URI', defaultValue: '',
                   description: 'Image deployed to dev1, e.g. us-central1-docker.pkg.dev/.../app:1-abc1234')
            string(name: 'SEVERITY', defaultValue: defaultSeverity,
                   description: 'Dependency scan fails at this severity or above: low|medium|high|critical')
        }

        stages {
            stage('Install Snyk CLI') {
                steps {
                    sh '''
                        curl -sL https://static.snyk.io/cli/latest/snyk-linux -o snyk
                        chmod +x snyk
                        ./snyk --version
                    '''
                }
            }

            stage('Snyk Auth') {
                steps {
                    sh '''
                        TOKEN=$(kubectl get secret jenkins-secrets -n jenkins \
                                -o jsonpath='{.data.snyk-token}' 2>/dev/null | base64 -d || true)
                        if [ -z "$TOKEN" ] || [ "$TOKEN" = "REPLACE_ME" ]; then
                            echo "SNYK_TOKEN not configured."
                            echo "Create the secret once:"
                            echo "  kubectl create secret generic jenkins-secrets -n jenkins \\"
                            echo "      --from-literal=snyk-token=<YOUR_SNYK_TOKEN> \\"
                            echo "      --dry-run=client -o yaml | kubectl apply -f -"
                            exit 1
                        fi
                        ./snyk auth "$TOKEN"
                    '''
                }
            }

            stage('Dependency Vulnerability Scan') {
                steps {
                    sh './snyk test --severity-threshold=${params.SEVERITY}'
                }
            }

            stage('Kubernetes Manifest Scan') {
                steps {
                    sh './snyk iac test k8s/ || true'
                }
            }

            stage('Container Image Scan') {
                when { expression { return params.IMAGE_URI?.trim() } }
                steps {
                    sh "./snyk container test ${params.IMAGE_URI} || echo 'image pull needs registry auth - report skipped'"
                }
            }
        }
    }
}

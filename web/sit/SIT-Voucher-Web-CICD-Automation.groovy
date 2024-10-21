pipeline {
    agent any

    environment {
        SONAR_TOKEN = credentials('SONAR_TOKEN_VOUCHER_WEB')
        S3_BUCKET = "voucher-app-sit/web"
        BUILD_DIR = "/var/lib/jenkins"
        PROJECT_KEY = "ralphlui-voucher-app-web"
        ORGANIZATION_KEY = "ralphlui-voucher-management-web-frontend"
        IMAGE_NAME = "voucher-app-web"
        VERSION_FILE_PATH = "/var/lib/jenkins/"
        SKIP_TESTS = 'true' // Set to 'true' to skip tests
    }
    parameters {
        string(name: 'BACKEND_URL', description: 'Backend URL')
    }
    stages {
        stage('Validation') {
            steps {
                script {
                    // Check if BACKEND_URL is provided
                    if (!params.BACKEND_URL?.trim()) {
                        error "Error: BACKEND_URL parameter is required."
                    }

                    echo "All required parameters are provided."
                }
            }
        }
        stage('Checkout & Install dependencies') {
            steps {
                checkout scmGit(branches: [[name: '*/develop']], extensions: [], userRemoteConfigs: [[credentialsId: 'a2b5c5aa-618f-44f3-80e3-2ee3c6775e46', url: 'git@github.com:ralphlui/voucher-app-web.git']])
                // Install project dependencies
                sh "rm -rf node_modules"
                sh "npm install"
                sh "sed -i 's/DOMAIN-URL/${params.BACKEND_URL}/g' .env"
            }
        }
        stage('Run unit tests') {
            when {
                expression { env.SKIP_TESTS != 'true' }
            }
            steps {
                sh "CI=true npm test -a"
            }
        }
        stage ('SonarQube code scanning') {
            when {
                expression { env.SKIP_TESTS != 'true' }
            }
            steps {
                sh """
                export SONAR_TOKEN=${SONAR_TOKEN}
                ${SONAR_SCANNER} \
                  -Dsonar.organization=${ORGANIZATION_KEY} \
                  -Dsonar.projectKey=${PROJECT_KEY} \
                  -Dsonar.sources=. \
                  -Dsonar.host.url=https://sonarcloud.io
                """
            }
        }
        stage ('Dependency-check scanning') {
            steps {
                script {
                    def pipelineName = env.JOB_NAME
                    echo "Current running pipeline: ${pipelineName}"

                    // Get current date and time in a format like "yyyyMMdd-HHmmss"
                    def currentDateTime = sh(script: 'date +%Y%m%d-%H%M%S', returnStdout: true).trim()

                    // Create a unique report file name with timestamp
                    def reportUniqueFileName = "dependency-check-report-${currentDateTime}.html"
                    def reportOriginalFileName = "dependency-check-report.html"

                    dir("${BUILD_DIR}/workspace/${pipelineName}/target") {
                        sh """
                            ${SCA_SCAN} --project 'Voucher Management 2.0 (${IMAGE_NAME})' --scan .
                            aws s3 cp "${reportOriginalFileName}" s3://${S3_BUCKET}/owasp-reports/${reportUniqueFileName}
                        """
                    }
                }
            }
        }
        stage ('Build Expo project') {
            when {
                expression { env.SKIP_TESTS != 'true' }
            }
            steps {
                // Build the React app
                sh "npx expo export"
            }
        }
        stage ('Build Docker image') {
            steps {
                script {
                    def pipelineName = env.JOB_NAME
                    def versionFilePath = env.VERSION_FILE_PATH
                    def filename = 'docker_version_web.txt'
                    def concatenatedPath = versionFilePath + filename
                    def currentVersion

                    // Check if the file exists in the specified path
                    if (fileExists(concatenatedPath)) {
                        currentVersion = readFile(concatenatedPath).trim().toInteger()
                    } else {
                        currentVersion = readFile(filename).trim().toInteger()
                    }

                    echo "Current docker image version: ${currentVersion}"

                    def nextVersion = currentVersion + 1
                    echo "Next docker image version: ${nextVersion}"

                    // Update the version in the file
                    if (fileExists(concatenatedPath)) {
                        writeFile(file: concatenatedPath, text: nextVersion.toString())
                    } else {
                        writeFile(file: filename, text: nextVersion.toString())
                        sh "cp ./docker_version_web.txt ${VERSION_FILE_PATH}"
                    }

                    sh """
                    # Remove existing image if it exists
                    docker images -q ${IMAGE_NAME} | xargs -r docker rmi -f || true
                    docker build -t ${IMAGE_NAME}:v${nextVersion} .
                    """
                }
            }
        }
        stage ('Push Docker image to AWS ECR') {
            steps {
                script {
                    def pipelineName = env.JOB_NAME
                    def versionFilePath = env.VERSION_FILE_PATH
                    def filename = 'docker_version_web.txt'
                    def concatenatedPath = versionFilePath + filename
                    def currentVersion

                    // Check if the file exists in the specified path
                    if (!fileExists(concatenatedPath)) {
                        System.exit(1)
                    }

                    currentVersion = readFile(concatenatedPath).trim().toInteger()
                    echo "Current docker image version: ${currentVersion}"

                    // Docker login
                    sh "${ECR_DOCKER_LOGIN}"

                    sh """
                    # Tag image
                    docker tag ${IMAGE_NAME}:v${currentVersion} ${ECR_REPO_URI}/${IMAGE_NAME}:v${currentVersion}
                    # Push to AWS ECR
                    docker push ${ECR_REPO_URI}/${IMAGE_NAME}:v${currentVersion}
                    """
                }
            }
        }
        stage ('Deploy to k8s cluster') {
            steps {
                script {
                    def k8sFilename = 'deployment.yaml'
                    def pipelineName = env.JOB_NAME
                    def versionFilePath = env.VERSION_FILE_PATH
                    def filename = 'docker_version_web.txt'
                    def concatenatedPath = versionFilePath + filename
                    def currentVersion

                    // Check if the file exists in the specified path
                    if (!fileExists(concatenatedPath)) {
                        System.exit(1)
                    }

                    currentVersion = readFile(concatenatedPath).trim().toInteger()
                    echo "Current docker image version: ${currentVersion}"

                    sh """
                    # Configure kubectl current context to minikube
                    kubectl config use-context minikube
                    sed 's/VERSION/v${currentVersion}/g' ${k8sFilename} > ${k8sFilename}.mod
                    mv ${k8sFilename}.mod ${k8sFilename}
                    kubectl apply -f deployment.yaml
                    sleep 5
                    echo 'Deployment finished!'
                    """
                }
            }
        }
    }
    post {
        always {
            sh """
            rm -rf node_modules
            rm -rf .dist
            """
        }
    }
}

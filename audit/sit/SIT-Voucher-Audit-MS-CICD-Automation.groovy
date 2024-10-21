pipeline {
    agent any

    environment {
        SONAR_TOKEN = credentials('SONAR_TOKEN_VOUCHER_AUDIT')
        S3_BUCKET = "voucher-app-sit/audit"
        BUILD_DIR = "/var/lib/jenkins"
        PROJECT_KEY = "ralphlui_voucher-management-app-backend"
        IMAGE_NAME = "voucher-app-audit"
        VERSION_FILE_PATH = "/var/lib/jenkins/"
    }

    tools {
        maven 'Maven v3.8.4'
    }

    stages {
        stage('Run unit tests') {
            steps {
                checkout scmGit(branches: [[name: '*/development']], extensions: [], userRemoteConfigs: [[credentialsId: 'a2b5c5aa-618f-44f3-80e3-2ee3c6775e46', url: 'git@github.com:ralphlui/voucher-app-audit.git']])
                sh 'mvn -B clean test'
            }
        }
        stage ('SonarQube code scanning') {
            steps {
                sh """
                export SONAR_TOKEN=${SONAR_TOKEN}
                mvn verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar -Dsonar.projectKey=${PROJECT_KEY} -DskipTests
                """
            }
        }
        stage ('Build maven project') {
            steps {
                sh 'mvn install -DskipTests'
            }
        }
        stage ('Upload Jacoco reports to AWS S3') {
            steps {
                script {
                    def pipelineName = env.JOB_NAME
                    echo "Current running pipeline: ${pipelineName}"

                    // Get current date and time in a format like "yyyyMMdd-HHmmss"
                    def currentDateTime = sh(script: 'date +%Y%m%d-%H%M%S', returnStdout: true).trim()

                    // Create a unique zip file name with timestamp
                    def zipFileName = "jacoco-reports-${currentDateTime}.zip"

                    dir("${BUILD_DIR}/workspace/${pipelineName}/target/site/jacoco") {
                        // Export environment variables for the AWS CLI command
                        sh """
                            zip -r "${zipFileName}" .
                            aws s3 cp "${zipFileName}" s3://${S3_BUCKET}/jacoco-reports/${zipFileName}
                        """
                    }
                }
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
        stage ('Build Docker image') {
            steps {
                script {
                    def pipelineName = env.JOB_NAME
                    def versionFilePath = env.VERSION_FILE_PATH
                    def filename = 'docker_version_audit.txt'
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
                        sh "cp ./docker_version_audit.txt ${VERSION_FILE_PATH}"
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
                    def filename = 'docker_version_audit.txt'
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
                    def filename = 'docker_version_audit.txt'
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
}

pipeline {
    agent any
 
    parameters {
        // Define parameters here
        string(name: 'REL_VERSION', description: 'Artifact version for release')
    }
    environment {
        BUILD_DIR = "/var/lib/jenkins"
        IMAGE_NAME = "voucher-app-feed"
        CLUSTER = "app-cluster-1"
    }

    stages {
        stage('Run deployment to production') {
            steps {
                script {
                    def k8sFilename = 'deployment-app-feed.yaml'
                    def pipelineName = env.JOB_NAME
                    def relVersion = params.REL_VERSION
                    // Define the YAML content
                    def yamlContent = """apiVersion: apps/v1
kind: Deployment
metadata:
  name: voucher-app-feed
  namespace: voucher-management-app
spec:
  replicas: 2
  selector:
    matchLabels:
      app: voucher-app-feed
  template:
    metadata:
      labels:
        app: voucher-app-feed
    spec:
      containers:
        - name: voucher-app-feed
          image: public.ecr.aws/s6y5a7e8/voucher-app-feed:v${relVersion}
          imagePullPolicy: Always
          ports:
            - containerPort: 8082
          env:
            - name: AWS_ACCESS_KEY
              valueFrom:
                secretKeyRef:
                  name: my-secret
                  key: aws_s3_access_key_id
            - name: AWS_SECRET_KEY
              valueFrom:
                secretKeyRef:
                  name: my-secret
                  key: aws_s3_secret_key_id
            - name: SPRING_PROFILES_ACTIVE
              valueFrom:
                configMapKeyRef:
                  name: my-configmap
                  key: SPRING_PROFILES_ACTIVE
            - name: FRONTEND_URL
              valueFrom:
                configMapKeyRef:
                  name: my-configmap
                  key: FRONTEND_URL
            - name: AUTH_URL
              valueFrom:
                configMapKeyRef:
                  name: my-configmap
                  key: AUTH_URL
            - name: AUDIT_SQS_URL
              valueFrom:
                configMapKeyRef:
                  name: my-configmap
                  key: AUDIT_SQS_URL
            - name: POD_NAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: FEED_SQS_QUEUE_NAME
              value: "\$(POD_NAME)"
          resources:
           requests:
             cpu: "50m"
             memory: "200Mi"
           limits:
             cpu: "300m"
             memory: "400Mi" """
             
                    // Write the YAML content to /tmp/deployment.yaml
                    writeFile file: "/tmp/${k8sFilename}", text: yamlContent

                    sh """
                    # Update kubeconfig to change to our cluster
                    echo "aws eks update-kubeconfig --name ${CLUSTER} --region ap-southeast-1"
                    aws eks update-kubeconfig --name ${CLUSTER} --region ap-southeast-1
                    kubectl apply -f /tmp/${k8sFilename}
                    sleep 5
                    echo 'Deployment finished!'
                    """
                }
            }
        }
    }
}


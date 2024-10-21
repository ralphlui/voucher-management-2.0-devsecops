pipeline {
    agent any
 
    parameters {
        // Define parameters here
        string(name: 'REL_VERSION', description: 'Artifact version for release')
    }
    environment {
        BUILD_DIR = "/var/lib/jenkins"
        IMAGE_NAME = "voucher-app-auth"
        CLUSTER = "app-cluster-1"
    }

    stages {
        stage('Run deployment to production') {
            steps {
                script {
                    def k8sFilename = 'deployment-app-auth.yaml'
                    def pipelineName = env.JOB_NAME
                    def relVersion = params.REL_VERSION
                    // Define the YAML content
                    def yamlContent = """apiVersion: apps/v1
apiVersion: apps/v1
kind: Deployment
metadata:
  name: voucher-app-auth
  namespace: voucher-management-app
spec:
  replicas: 2
  selector:
    matchLabels:
      app: voucher-app-auth
  template:
    metadata:
      labels:
        app: voucher-app-auth
    spec:
      containers:
        - name: voucher-app-auth
          image: public.ecr.aws/s6y5a7e8/voucher-app-auth:v${relVersion}
          imagePullPolicy: Always
          ports:
            - containerPort: 8083
          env:
            - name: DB_USERNAME
              valueFrom:
                secretKeyRef:
                  name: my-secret
                  key: db_username
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: my-secret
                  key: db_password
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
            - name: AES_SECRET_KEY
              valueFrom:
                secretKeyRef:
                  name: my-secret
                  key: aes_secret
            - name: FRONTEND_URL
              valueFrom:
                configMapKeyRef:
                  name: my-configmap
                  key: FRONTEND_URL
            - name: AWS_SQS_URL
              valueFrom:
                configMapKeyRef:
                  name: my-configmap
                  key: AUDIT_SQS_URL
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

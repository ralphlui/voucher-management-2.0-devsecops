pipeline {
    agent any
 
    parameters {
        // Define parameters here
        string(name: 'REL_VERSION', description: 'Artifact version for release')
    }
    environment {
        BUILD_DIR = "/var/lib/jenkins"
        IMAGE_NAME = "voucher-app-web"
        CLUSTER = "web-cluster-1"
    }

    stages {
        stage('Run deployment to production') {
            steps {
                script {
                    def k8sFilename = 'deployment-web.yaml'
                    def pipelineName = env.JOB_NAME
                    def relVersion = params.REL_VERSION
                    // Define the YAML content
                    def yamlContent = """apiVersion: apps/v1
kind: Deployment
metadata:
  name: voucher-app-web
  namespace: voucher-management-web
spec:
  replicas: 2
  selector:
    matchLabels:
      app.kubernetes.io/name: voucher-app-web
  template:
    metadata:
      labels:
        app.kubernetes.io/name: voucher-app-web
    spec:
      containers:
        - name: voucher-app-web
          image: public.ecr.aws/s6y5a7e8/voucher-app-web:v${relVersion}-prd
          imagePullPolicy: Always
          ports:
            - containerPort: 3000
          resources:
            requests:
              cpu: "50m"
              memory: "100Mi"
            limits:
              cpu: "200m"
              memory: "350Mi" """


                    // Write the YAML content to /tmp/deployment.yaml
                    writeFile file: "/tmp/${k8sFilename}", text: yamlContent

                    sh """
                    # Update kubeconfig to change to our cluster
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

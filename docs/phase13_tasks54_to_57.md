# Phase 13 — DevOps & Deployment (Tasks 54–57)
**Estimated Time:** 4 hours | **Status:** ⬜ Not Started

## Task 54: Docker — Multi-Stage Build

```dockerfile
# ===== Stage 1: Build =====
FROM maven:3.9-openjdk-17-slim AS builder
WORKDIR /app

# Cache dependencies as separate layer (invalidated only when pom.xml changes)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Build the application
COPY src ./src
RUN mvn package -DskipTests -q

# ===== Stage 2: Runtime =====
FROM eclipse-temurin:17-jre-alpine   # ~180MB vs ~600MB JDK

# Security: non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy only the jar from builder stage (not Maven, JDK, source code)
COPY --from=builder /app/target/*.jar app.jar

# Change ownership
RUN chown appuser:appgroup app.jar

USER appuser

# Expose port
EXPOSE 8080

# JVM tuning + app startup
ENTRYPOINT ["java",
  "-Xmx512m",
  "-Xms256m",
  "-XX:+UseG1GC",
  "-XX:MaxGCPauseMillis=200",
  "-Djava.security.egd=file:/dev/./urandom",
  "-jar",
  "app.jar"]
```

```bash
# Build
docker build -t order-service:1.0.0 .
docker build -t order-service:1.0.0 --no-cache .

# Run with env vars
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/orderdb \
  -e DATABASE_PASSWORD=secret \
  -e KAFKA_BROKERS=kafka:9092 \
  order-service:1.0.0

# Inspect image layers
docker history order-service:1.0.0

# Push to registry
docker tag order-service:1.0.0 yourregistry.azurecr.io/order-service:1.0.0
docker push yourregistry.azurecr.io/order-service:1.0.0
```

---

## Task 55: Docker Compose — Multi-Container Setup

```yaml
# docker-compose.yml
version: '3.8'

services:
  # ===== Application =====
  order-service:
    build:
      context: .
      dockerfile: Dockerfile
    image: order-service:latest
    container_name: order-service
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/orderdb
      - SPRING_DATASOURCE_USERNAME=orderuser
      - SPRING_DATASOURCE_PASSWORD=orderpass
      - SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:29092
      - SPRING_DATA_REDIS_HOST=redis
    depends_on:
      postgres:
        condition: service_healthy
      kafka:
        condition: service_started
      redis:
        condition: service_started
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s
    restart: unless-stopped
    networks:
      - app-network

  # ===== PostgreSQL =====
  postgres:
    image: postgres:15-alpine
    container_name: postgres
    environment:
      POSTGRES_DB: orderdb
      POSTGRES_USER: orderuser
      POSTGRES_PASSWORD: orderpass
    volumes:
      - postgres_data:/var/lib/postgresql/data
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U orderuser -d orderdb"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - app-network

  # ===== Redis =====
  redis:
    image: redis:7-alpine
    container_name: redis
    command: redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    networks:
      - app-network

  # ===== Kafka =====
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    container_name: zookeeper
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    networks:
      - app-network

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    container_name: kafka
    depends_on: [zookeeper]
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    networks:
      - app-network

  # ===== Kafka UI =====
  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    ports:
      - "8090:8080"
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:29092
    depends_on: [kafka]
    networks:
      - app-network

volumes:
  postgres_data:
  redis_data:

networks:
  app-network:
    driver: bridge
```

```bash
docker-compose up -d                    # Start all
docker-compose up -d order-service      # Start specific service
docker-compose logs -f order-service    # Follow logs
docker-compose down                     # Stop all
docker-compose down -v                  # Stop + remove volumes
docker-compose ps                       # Service status
docker-compose exec postgres psql -U orderuser -d orderdb  # DB shell
```

---

## Task 56: Kubernetes Basics

```yaml
# k8s/deployment.yml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
  namespace: production
  labels:
    app: order-service
    version: "1.0.0"
spec:
  replicas: 3
  selector:
    matchLabels:
      app: order-service
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1          # Can have 1 extra pod during update
      maxUnavailable: 0    # Never have less than desired (zero downtime)
  template:
    metadata:
      labels:
        app: order-service
    spec:
      containers:
      - name: order-service
        image: yourregistry.azurecr.io/order-service:1.0.0
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: DATABASE_URL
          valueFrom:
            configMapKeyRef:
              name: order-service-config
              key: database-url
        - name: DATABASE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: order-service-secrets
              key: db-password
        - name: JWT_SECRET
          valueFrom:
            secretKeyRef:
              name: order-service-secrets
              key: jwt-secret
        resources:
          requests:
            memory: "256Mi"
            cpu: "100m"
          limits:
            memory: "1Gi"
            cpu: "500m"
        readinessProbe:         # Is this pod ready for traffic?
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
          failureThreshold: 3
        livenessProbe:          # Is this pod alive?
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
          failureThreshold: 3
      terminationGracePeriodSeconds: 60   # Match Spring graceful shutdown

---
# k8s/service.yml
apiVersion: v1
kind: Service
metadata:
  name: order-service
  namespace: production
spec:
  selector:
    app: order-service
  ports:
  - port: 80
    targetPort: 8080
  type: ClusterIP         # Internal only — Ingress handles external traffic

---
# k8s/configmap.yml
apiVersion: v1
kind: ConfigMap
metadata:
  name: order-service-config
  namespace: production
data:
  database-url: "jdbc:postgresql://postgres-service:5432/orderdb"
  kafka-brokers: "kafka-service:9092"
  spring-profiles-active: "prod"

---
# k8s/secret.yml (use external secrets in prod — not plain YAML!)
apiVersion: v1
kind: Secret
metadata:
  name: order-service-secrets
  namespace: production
type: Opaque
data:
  db-password: cGFzc3dvcmQ=    # base64 encoded
  jwt-secret: bXktc2VjcmV0LWtleQ==

---
# k8s/hpa.yml (Horizontal Pod Autoscaler)
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: order-service-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: order-service
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
```

```bash
# Apply manifests
kubectl apply -f k8s/

# Check deployment
kubectl get pods -n production
kubectl describe pod order-service-xxx -n production
kubectl logs order-service-xxx -n production --follow

# Scale manually
kubectl scale deployment order-service --replicas=5 -n production

# Rolling update
kubectl set image deployment/order-service order-service=registry/order-service:2.0.0 -n production
kubectl rollout status deployment/order-service -n production
kubectl rollout undo deployment/order-service -n production  # Rollback!
```

---

## Task 57: CI/CD — GitHub Actions

```yaml
# .github/workflows/build-deploy.yml
name: Build and Deploy

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

env:
  REGISTRY: yourregistry.azurecr.io
  IMAGE_NAME: order-service

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_DB: testdb
          POSTGRES_USER: test
          POSTGRES_PASSWORD: test
        ports: ["5432:5432"]
    steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'
        cache: maven
    - name: Run Tests
      run: ./mvnw test
    - name: Upload Coverage
      uses: codecov/codecov-action@v3

  build:
    needs: test
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    steps:
    - uses: actions/checkout@v4
    - name: Login to Registry
      uses: docker/login-action@v3
      with:
        registry: ${{ env.REGISTRY }}
        username: ${{ secrets.REGISTRY_USERNAME }}
        password: ${{ secrets.REGISTRY_PASSWORD }}
    - name: Build and Push Docker Image
      uses: docker/build-push-action@v5
      with:
        push: true
        tags: |
          ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:latest
          ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:${{ github.sha }}

  deploy:
    needs: build
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    steps:
    - uses: actions/checkout@v4
    - name: Set K8s context
      uses: azure/k8s-set-context@v3
      with:
        kubeconfig: ${{ secrets.KUBECONFIG }}
    - name: Deploy to Kubernetes
      run: |
        kubectl set image deployment/order-service \
          order-service=${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:${{ github.sha }} \
          -n production
        kubectl rollout status deployment/order-service -n production --timeout=5m
```

---

## Interview Q&A

**Q: Why multi-stage Docker builds?**
Builder stage uses full JDK (600MB+). Runtime stage uses JRE-only (~180MB). Final image is 3x smaller → faster push/pull → smaller attack surface (no compiler tools). Builder stage artifacts (source, Maven) not included in final image.

**Q: Liveness vs readiness probes in Kubernetes?**
Liveness: is the app alive? If fails repeatedly, K8s RESTARTS the pod. Readiness: is the app ready to serve traffic? If fails, K8s STOPS routing traffic (doesn't restart). Use readiness for startup warmup, dependency unavailability. Use liveness for deadlocks, infinite loops.

**Q: What is zero-downtime deployment?**
RollingUpdate strategy with `maxUnavailable: 0` (never remove old pod before new is ready) and `maxSurge: 1` (one extra pod during transition). Combined with readiness probes — traffic only routes to ready pods. Old pods removed only after new pods pass readiness.

**Q: Blue-Green vs Canary deployment?**
Blue-Green: two identical environments. Switch all traffic at once (A→B). Instant rollback by switching back. Doubles infrastructure cost during transition. Canary: route % of traffic to new version. Gradually increase. Reduces blast radius. Requires traffic splitting infrastructure (Istio, ALB weighted rules).

**Q: How do you store secrets in K8s?**
Never plain YAML (base64 ≠ encryption). Production: External Secrets Operator (pulls from AWS Secrets Manager / Azure Key Vault / HashiCorp Vault), Sealed Secrets (encrypted in Git), or mounted as volumes from secret store. K8s Secrets are only base64 encoded — use RBAC to restrict access.

**Q: What is graceful shutdown in K8s?**
K8s sends SIGTERM → Spring's `server.shutdown: graceful` intercepts → stops accepting new requests → waits for in-flight requests (up to `timeout-per-shutdown-phase`) → closes DB connections, commits Kafka offsets → exits. K8s `terminationGracePeriodSeconds` must be >= Spring's shutdown timeout.

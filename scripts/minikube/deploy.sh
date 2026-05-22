#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="raft"
# Use an immutable tag each deploy so Kubernetes definitely picks up the new image.
# Override by exporting IMAGE_TAG=... before running this script.
IMAGE_TAG="${IMAGE_TAG:-raft-grpc:local-$(date +%Y%m%d%H%M%S)}"

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

require minikube
require kubectl
require docker

echo "[deploy] Starting minikube (if not running)..."
minikube start >/dev/null

echo "[deploy] Building image inside minikube docker daemon: ${IMAGE_TAG}"
eval "$(minikube docker-env)"
docker build -t "${IMAGE_TAG}" .

echo "[deploy] Applying Kubernetes manifests..."
kubectl apply -f k8s/minikube/raft-cluster.yaml
kubectl apply -f k8s/minikube/raft-client.yaml

echo "[deploy] Updating deployments to use image: ${IMAGE_TAG}"
kubectl -n "${NAMESPACE}" set image deploy/raft-node1 node="${IMAGE_TAG}"
kubectl -n "${NAMESPACE}" set image deploy/raft-node2 node="${IMAGE_TAG}"
kubectl -n "${NAMESPACE}" set image deploy/raft-node3 node="${IMAGE_TAG}"
kubectl -n "${NAMESPACE}" set image deploy/raft-client client="${IMAGE_TAG}"

echo "[deploy] Waiting for deployments..."
kubectl -n "${NAMESPACE}" rollout status deploy/raft-node1 --timeout=120s
kubectl -n "${NAMESPACE}" rollout status deploy/raft-node2 --timeout=120s
kubectl -n "${NAMESPACE}" rollout status deploy/raft-node3 --timeout=120s
kubectl -n "${NAMESPACE}" rollout status deploy/raft-client --timeout=120s

echo "[deploy] Done. Image: ${IMAGE_TAG}"

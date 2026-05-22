# Minikube (Kubernetes) Setup

This project includes Kubernetes manifests to run a 3-node Raft cluster and a simple periodic client on minikube.

## Prerequisites

- `minikube`
- `kubectl`
- `docker`

## Deploy

Build the Docker image inside minikube and apply the manifests:

```bash
./scripts/minikube/deploy.sh
```

Resources are created in the `raft` namespace:

- `deploy/raft-node1`, `deploy/raft-node2`, `deploy/raft-node3`
- `svc/node1`, `svc/node2`, `svc/node3`
- `deploy/raft-client`

## Logs

Stream logs and write them to `./logs/`:

```bash
./scripts/minikube/logs.sh
```

## Client behavior

The client submits a new `key=value` command every 5 seconds (default). Configure via:

- `k8s/minikube/raft-client.yaml` (`PERIOD_SECONDS`, `NODES`)


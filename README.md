Raft Consensus: Java + gRPC + Docker

A from-scratch implementation of the [Raft consensus algorithm](https://raft.github.io/raft.pdf) in Java, using gRPC for inter-node RPCs and Docker Compose to run a 3-node cluster.

## Features

- Leader election with randomized timeouts
- Log replication with `AppendEntries` and `RequestVote` RPCs
- Commit index advancement with §5.4.2 safety (current-term-only commits)
- Simple key-value state machine (`key=value` commands)
- Peer readiness probing on startup (TCP + gRPC health check)

## Project Structure

```
├── RaftNode.java       # Core Raft state machine (election, replication, commit)
├── NodeServer.java     # gRPC server bootstrap and environment parsing
├── GrpcRaftPeer.java   # gRPC client stub for each peer node
├── Dockerfile
└── docker-compose.yml
```

## Running the Cluster

**Prerequisites:** Docker and Docker Compose.

```bash
docker compose up --build
```

This starts three nodes on ports `50051`, `50052`, and `50053` within a shared bridge network. Node 1 is configured to win the initial election.

## Configuration

Each node is configured via environment variables:

| Variable    | Example                          | Description                          |
|-------------|----------------------------------|--------------------------------------|
| `NODE_ID`   | `1`                              | Unique node identifier               |
| `NODE_PORT` | `50051`                          | Port this node listens on            |
| `PEERS`     | `"2:node2:50052,3:node3:50053"` | Comma-separated `id:host:port` peers |

## How It Works

1. On startup, each node waits until all peers are reachable (TCP + gRPC probe).
2. Followers start a randomized election timer (1500–3000ms).
3. The first follower to time out campaigns for leader, collecting votes via `RequestVote`.
4. The leader sends `AppendEntries` heartbeats every 100ms to maintain authority.
5. Client commands are submitted to the leader, replicated to a majority, then committed and applied to the state machine.

## Limitations

This is a demonstration implementation. It does not include:

- **Persistent state** — log and term are lost on restart
- **Log compaction / snapshots** — the log grows unbounded
- **Client linearizability** — retried commands may execute twice
- **Cluster membership changes** — node count is fixed at startup

## References

- [In Search of an Understandable Consensus Algorithm (Ongaro & Ousterhout, 2014)](https://raft.github.io/raft.pdf)
- [The Raft Consensus Algorithm — raft.github.io](https://raft.github.io)

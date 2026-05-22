package raft;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import raft.grpc.CommandRequest;
import raft.grpc.CommandResponse;
import raft.grpc.RaftServiceGrpc;

/**
 * Simple periodic client that submits a new {@code key=value} command every N seconds.
 *
 * Environment:
 * - NODES: comma-separated {@code id:host:port} entries (default: 1:node1:50051,2:node2:50052,3:node3:50053)
 * - PERIOD_SECONDS: how often to submit (default: 5)
 */
public final class ClientMain {

    private static final Logger log = Logger.getLogger(ClientMain.class.getName());

    private static final int TCP_CHECK_TIMEOUT_MS = 500;
    private static final int RPC_DEADLINE_SECONDS = 3;

    private record Target(int id, String host, int port) {}

    public static void main(String[] args) throws Exception {
        String nodesEnv = env("NODES", "1:node1:50051,2:node2:50052,3:node3:50053");
        int periodSeconds = Integer.parseInt(env("PERIOD_SECONDS", "5"));

        Map<Integer, Target> targets = parseTargets(nodesEnv);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("NODES must not be empty");
        }

        int currentId = targets.keySet().stream().min(Integer::compareTo).orElseThrow();
        int counter = 0;

        log.info("=== Raft client starting ===");
        log.info("NODES=" + nodesEnv);
        log.info("PERIOD_SECONDS=" + periodSeconds);

        while (true) {
            counter++;
            String key = "k" + counter;
            String value = Instant.now().toString();
            String command = key + "=" + value;

            Target target = targets.get(currentId);
            if (target == null) {
                currentId = targets.keySet().stream().min(Integer::compareTo).orElseThrow();
                target = targets.get(currentId);
            }

            // First verify raw TCP connectivity (helps separate K8s networking vs gRPC/HTTP2 issues)
            verifyTcp(target);

            try {
                CommandResponse resp = submitOnce(target, command);

                if (resp.getSuccess()) {
                    log.info("OK node=" + currentId + " cmd=\"" + command + "\" msg=\"" + resp.getMessage() + "\"");
                } else {
                    int leaderId = resp.getLeaderId();
                    log.info("REDIRECT from node=" + currentId + " to leader=" + leaderId + " msg=\"" + resp.getMessage() + "\"");
                    if (leaderId > 0 && targets.containsKey(leaderId)) {
                        currentId = leaderId;
                    }
                }

            } catch (StatusRuntimeException e) {
                Throwable cause = e.getCause();
                log.warning("Submit failed to node " + currentId + " (" + target.host + ":" + target.port + "): "
                        + "status=" + e.getStatus()
                        + (cause != null
                        ? " cause=" + cause.getClass().getName() + ": " + cause.getMessage()
                        : ""));
            } catch (Exception e) {
                log.warning("Submit failed to node " + currentId + " (" + target.host + ":" + target.port + "): "
                        + e.getClass().getName() + ": " + e.getMessage());
            }

            TimeUnit.SECONDS.sleep(periodSeconds);
        }
    }

    private static void verifyTcp(Target target) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host, target.port), TCP_CHECK_TIMEOUT_MS);
            log.info("TCP reachable node=" + target.id + " host=" + target.host + " port=" + target.port);
        } catch (Exception e) {
            log.warning("TCP check failed node=" + target.id + " host=" + target.host + " port=" + target.port + ": "
                    + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private static String resolveIpv4OrFirst(String hostname) throws Exception {
        InetAddress[] addresses = InetAddress.getAllByName(hostname);
        for (InetAddress addr : addresses) {
            if (addr instanceof java.net.Inet4Address) {
                return addr.getHostAddress();
            }
        }
        return addresses[0].getHostAddress();
    }

    private static CommandResponse submitOnce(Target target, String command) throws Exception {
        String resolved;
        try {
            resolved = resolveIpv4OrFirst(target.host);
        } catch (Exception ex) {
            // If DNS fails, keep the raw host. (It will fail fast and we'll see the cause.)
            resolved = target.host;
        }

        // Use a resolved InetSocketAddress (IP) so Netty doesn't attempt to connect with an
        // unresolved address (which can surface as "Invalid argument: /host:port").
        InetSocketAddress remote = new InetSocketAddress(resolved, target.port);

        ManagedChannel channel = NettyChannelBuilder
                .forAddress(remote)
                .usePlaintext()
                .build();
        try {
            RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS);
            return stub.submitCommand(CommandRequest.newBuilder().setCommand(command).build());
        } finally {
            channel.shutdownNow();
        }
    }

    private static Map<Integer, Target> parseTargets(String nodesEnv) {
        Map<Integer, Target> targets = new HashMap<>();
        for (String entry : nodesEnv.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isBlank()) continue;
            String[] parts = trimmed.split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Bad NODES entry (expected id:host:port): " + trimmed);
            }
            int id = Integer.parseInt(parts[0]);
            String host = parts[1];
            int port = Integer.parseInt(parts[2]);
            targets.put(id, new Target(id, host, port));
        }
        return targets;
    }

    private static String env(String key, String fallback) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : fallback;
    }
}

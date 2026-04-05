package raft;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import io.grpc.Server;
import io.grpc.ServerBuilder;


public class NodeServer {
    private static final Logger log = Logger.getLogger(NodeServer.class.getName());

    /** How long to wait for in-flight RPCs to finish before forcing shutdown. */
    private static final int GRACEFUL_SHUTDOWN_SECONDS = 5;

    private final RaftNode node;
    private final Server   server;

    int nodeId;

    public NodeServer(int nodeId, int port, List<RaftPeer> peers) {
        this.node = new RaftNode(nodeId, peers);
        this.nodeId = nodeId;
        RaftServiceImpl service = new RaftServiceImpl(node);

        this.server = ServerBuilder.forPort(port)
                .addService(service)
                // Keep the server alive for long-running heartbeat streams
                .maxInboundMessageSize(4 * 1024 * 1024)
                .build();
    }

    public void start() {
        try {
            server.start();
            log.info("[NodeServer] gRPC server started on port " + server.getPort());
        } catch (IOException e) {
            log.severe("[NodeServer] FATAL: Could not start gRPC server: " + e.getMessage());
            // Force the JVM to die immediately!
            // A non-zero exit code (1) tells Docker the container crashed.
            System.exit(1);
        }
        int startupDelayMs = 3000 + (nodeId * 1000);
        log.info("[NodeServer] Waiting " + startupDelayMs + "ms before starting Raft node...");
        try { Thread.sleep(startupDelayMs); } catch (InterruptedException ignored) {}  // ← was missing

        node.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[NodeServer] JVM shutdown hook triggered — stopping");
            try { stop(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
    }

    public void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS);
        }
        node.stop();
    }

    /** Blocks the calling thread until the server terminates. */
    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) server.awaitTermination();
    }


    /**
     * Reads {@code NODE_ID}, {@code NODE_PORT}, and {@code PEERS} from the
     * environment and constructs a fully-wired {@link NodeServer}.
     *
     * <p>Peer connections are attempted with a retry loop because containers
     * may start in any order.
     */
    public static NodeServer fromEnvironment() {

        int nodeId = Integer.parseInt(env("NODE_ID", "1"));
        int port   = Integer.parseInt(env("NODE_PORT", "50051"));

        // PEERS format: "2:node2:50052,3:node3:50053"
        String peersEnv = env("PEERS", "");
        List<RaftPeer> peers = new ArrayList<>();

        if (!peersEnv.isBlank()) {
            for (String entry : peersEnv.split(",")) {
                String[] parts = entry.trim().split(":");
                if (parts.length != 3) {
                    throw new IllegalArgumentException(
                            "PEERS must be comma-separated id:host:port triples, got: " + entry);
                }
                int    peerId   = Integer.parseInt(parts[0]);
                String peerHost = parts[1];
                int    peerPort = Integer.parseInt(parts[2]);

                log.info("[NodeServer] Registering peer " + peerId + " @ " + peerHost + ":" + peerPort);
                peers.add(new GrpcRaftPeer(peerId, peerHost, peerPort));
            }
            log.info("Parsed " + peers.size() + " peers: " + peersEnv);
        }

        return new NodeServer(nodeId, port, peers);
    }

    private static String env(String key, String fallback) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : fallback;
    }


}

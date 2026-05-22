package raft;

import java.security.Security;
import java.util.logging.Logger;

/**
 * Main entry point. Delegates immediately to {@link NodeServer#fromEnvironment()}.
 */
public class NodeMain {

    private static final Logger log = Logger.getLogger(NodeMain.class.getName());

    public static void main(String[] args) throws Exception {
        // Help Docker DNS updates take effect after container restarts.
        // Without this, the JVM may cache node1/node2/node3 IPs for a long time.
        Security.setProperty("networkaddress.cache.ttl", "1");
        Security.setProperty("networkaddress.cache.negative.ttl", "0");

        log.info("=== Raft gRPC Node starting ===");

        NodeServer server = NodeServer.fromEnvironment();
        server.start();
        server.blockUntilShutdown();
    }
}

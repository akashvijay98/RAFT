package raft;

import java.util.logging.Logger;

/**
 * Main entry point. Delegates immediately to {@link NodeServer#fromEnvironment()}.
 */
public class NodeMain {

    private static final Logger log = Logger.getLogger(NodeMain.class.getName());

    public static void main(String[] args) throws Exception {
        log.info("=== Raft gRPC Node starting ===");

        NodeServer server = NodeServer.fromEnvironment();
        server.start();
        server.blockUntilShutdown();
    }
}

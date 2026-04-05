import main.java.raft.RaftNode;
import java.util.*;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        int nodeCount = 5;
        List<RaftNode> nodes = new ArrayList<>();

        // 1. Initialize nodes
        for (int i = 0; i < nodeCount; i++) {
            nodes.add(new RaftNode(i, new ArrayList<>()));
        }

        // 2. Link peers (every node knows about every other node)
        for (RaftNode node : nodes) {
            for (RaftNode peer : nodes) {
                if (node.getNodeId() != peer.getNodeId()) {
                    node.addPeer(peer);
                }
            }
        }

        // 3. Start all nodes
        System.out.println("Starting RAFT cluster with " + nodeCount + " nodes...");
        for (RaftNode node : nodes) {
            node.start();
        }

        // 4. Wait for an election to settle
        System.out.println("Waiting for leader election...");
        Thread.sleep(2000);

        // 5. Find the leader and submit commands
        RaftNode leader = null;
        for (RaftNode node : nodes) {
            if (node.getRole() == RaftNode.Role.LEADER) {
                leader = node;
                break;
            }
        }

        if (leader != null) {
            System.out.println("Detected Leader: Node " + leader.getNodeId() + " (Term " + leader.getCurrentTerm() + ")");
            System.out.println("Submitting commands to leader...");
            leader.submitCommand("user_1=Alice");
            leader.submitCommand("user_2=Bob");
            leader.submitCommand("user_1=Charlie");
        } else {
            System.out.println("No leader elected yet. Simulation might need more time or check election timeouts.");
        }

        // 6. Wait for replication
        Thread.sleep(2000);

        // 7. Display final state of all nodes
        System.out.println("\n--- Final Cluster State ---");
        for (RaftNode node : nodes) {
            System.out.printf("Node %d | Role: %-9s | Term: %d | CommitIndex: %d | LogSize: %d | StateMachine: %s%n",
                    node.getNodeId(),
                    node.getRole(),
                    node.getCurrentTerm(),
                    node.getCommitIndex(),
                    node.getLogSize(),
                    node.getStateMachine());
        }

        // 8. Stop the cluster
        System.out.println("\nStopping cluster...");
        for (RaftNode node : nodes) {
            node.stop();
        }
    }
}

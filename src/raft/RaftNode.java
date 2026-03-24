package raft;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class RaftNode {
    private static final Logger log = Logger.getLogger(RaftNode.class.getName());

    private static final int HEARTBEAT_INTERVAL_MS   = 100;
    private static final int ELECTION_TIMEOUT_MIN_MS = 300;
    private static final int ELECTION_TIMEOUT_MAX_MS = 600;


    public enum Role { FOLLOWER, CANDIDATE, LEADER }

    private final int nodeId;
    private final List<RaftNode> peers;          // other nodes in the cluster

    // Persistent state (would be written to disk in production)
    private final AtomicInteger currentTerm = new AtomicInteger(0);
    private volatile Integer    votedFor    = null;
    private final List<LogEntry> raftLog    = new CopyOnWriteArrayList<>();

    // Volatile state
    private volatile Role    role        = Role.FOLLOWER;
    private volatile int     leaderId    = -1;
    private volatile int     commitIndex = -1;
    private volatile int     lastApplied = -1;

    // Leader-only volatile state
    private final Map<Integer, Integer> nextIndex  = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> matchIndex = new ConcurrentHashMap<>();


    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private ScheduledFuture<?> electionTimer;
    private ScheduledFuture<?> heartbeatTask;

    // State machine (simple key-value store)
    private final Map<String, String> stateMachine = new ConcurrentHashMap<>();

    // Metrics
    private final AtomicInteger electionsStarted = new AtomicInteger(0);
    private final AtomicInteger logsCommitted    = new AtomicInteger(0);


    public RaftNode(int nodeId, List<RaftNode> peers) {
        this.nodeId = nodeId;
        this.peers = peers;
    }

    public void addPeer(RaftNode peer) { peers.add(peer);}

    public void start(){
        log.info("[Node " + nodeId + "] Starting as FOLLOWER with term: " + currentTerm.get());
    }

    public void stop(){
        scheduler.shutdown();
        log.info("[Node " + nodeId + "] Stopped");
    }


}

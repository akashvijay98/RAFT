package raft;

import java.util.List;
import java.util.Map;
import java.util.Random;
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


    private int lastLogIndex() { return raftLog.size() - 1; }
    private int lastLogTerm()  { return raftLog.isEmpty() ? -1 : raftLog.get(raftLog.size() - 1).term(); }


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

    // TimeOUT




    // Leader Election
    private synchronized void startElection(){
        role = Role.CANDIDATE;

        int term = currentTerm.incrementAndGet();
        votedFor = nodeId;
        electionsStarted.incrementAndGet();

        log.info("[Node " + nodeId + "] Started election for term " + term);

        AtomicInteger votes = new AtomicInteger(1); // selft vote
        int majority = (peers.size()+1)/2+1;

        for(RaftNode peer: peers){
            scheduler.submit(()->
                    {
                        VoteResponse resp = peer.requestVote( term, nodeId, lastLogIndex(), lastLogTerm());
                    }

            );
        }

    }

    // Election TimeOut Reset
    private synchronized  void resetElectionTimer(){
        if(electionTimer !=null){
            electionTimer.cancel(false);
        }
        int delay = ELECTION_TIMEOUT_MIN_MS + new Random().nextInt(ELECTION_TIMEOUT_MAX_MS - ELECTION_TIMEOUT_MIN_MS);

        electionTimer  = scheduler.schedule(this::startElection, delay, TimeUnit.MILLISECONDS);
    }

    private boolean isAtLeastAsUpToDate(int candidateLastLogIdx, int candidateLastLogTrm){
        int myLastTerm = lastLogTerm();

        if(candidateLastLogTrm != myLastTerm) return candidateLastLogTrm > myLastTerm;
        return candidateLastLogIdx >= lastLogIndex();
    }
    // Request Vote RPC
    public synchronized VoteResponse requestVote(int term, int candidateId,int lastLogIdx, int lastLogtrm){
        if(term > currentTerm.get()) stepDown(term);

        boolean grant = false;

        if(term == currentTerm.get() && (votedFor == null || votedFor == candidateId) && isAtLeastAsUpToDate(lastLogIdx, lastLogtrm)){
            grant =true;
            votedFor = candidateId;
            resetElectionTimer();

        }

        log.info("[Node " + nodeId + "] Vote for " + candidateId + " in term " + term + ": " + grant);
        return new VoteResponse(currentTerm.get(), grant);
    }

    private void stepDown(int newTerm){
        log.info("]Node" + nodeId +"] Stepping down; new term = "+newTerm);
        currentTerm.set(newTerm);
        role = Role.FOLLOWER;
        votedFor = null;

        if(heartbeatTask !=null) heartbeatTask.cancel(false);
        resetElectionTimer();
    }
}

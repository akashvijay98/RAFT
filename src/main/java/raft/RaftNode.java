package raft;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class RaftNode {
    private static final Logger log = Logger.getLogger(RaftNode.class.getName());

    private static final int HEARTBEAT_INTERVAL_MS   = 100;
    private static final int ELECTION_TIMEOUT_MIN_MS = 1500;;
    private static final int ELECTION_TIMEOUT_MAX_MS = 3000;


    public enum Role { FOLLOWER, CANDIDATE, LEADER }

    private final int nodeId;
    private final List<RaftPeer> peers;          // other nodes in the cluster

    // Persistent state (would be written to disk in production)
    private final AtomicInteger currentTerm = new AtomicInteger(0);
    private volatile Integer    votedFor    = null;

    // single source of truth, will contain ordered List of LogEntry Objects
    private final List<LogEntry> raftLog    = new CopyOnWriteArrayList<>();

    // Volatile state
    private volatile Role    role        = Role.FOLLOWER;
    private volatile int     leaderId    = -1;
    private volatile int     commitIndex = -1;
    private volatile int     lastApplied = -1;

    // Leader-only volatile state
    private final Map<Integer, Integer> nextIndex  = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> matchIndex = new ConcurrentHashMap<>();


    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private ScheduledFuture<?> electionTimer;
    private ScheduledFuture<?> heartbeatTask;

    private final ExecutorService rpcExecutor = Executors.newCachedThreadPool();

    // State machine (simple key-value store)
    private final Map<String, String> stateMachine = new ConcurrentHashMap<>();

    // Metrics
    private final AtomicInteger electionsStarted = new AtomicInteger(0);
    private final AtomicInteger logsCommitted    = new AtomicInteger(0);


    private int lastLogIndex() { return raftLog.size() - 1; }
    private int lastLogTerm()  { return raftLog.isEmpty() ? -1 : raftLog.get(raftLog.size() - 1).term(); }



    public int    getNodeId()           { return nodeId; }
    public Role   getRole()             { return role; }
    public int    getCurrentTerm()      { return currentTerm.get(); }
    public int    getLeaderId()         { return leaderId; }
    public int    getCommitIndex()      { return commitIndex; }
    public int    getLogSize()          { return raftLog.size(); }
    public int    getElectionsStarted() { return electionsStarted.get(); }
    public int    getLogsCommitted()    { return logsCommitted.get(); }
    public Map<String, String> getStateMachine() { return Collections.unmodifiableMap(stateMachine); }


    public RaftNode(int nodeId, List<RaftPeer> peers) {
        this.nodeId = nodeId;
        this.peers = peers;
    }

    public void addPeer(RaftPeer peer) { peers.add(peer);}

    public void start(){
        log.info("[Node " + nodeId + "] Starting as FOLLOWER with term: " + currentTerm.get());
        waitForPeers();
        resetElectionTimer();
    }

    public void stop(){
        scheduler.shutdown();
        rpcExecutor.shutdown();
        log.info("[Node " + nodeId + "] Stopped");
    }
    private void waitForPeers() {
        List<Thread> threads = new ArrayList<>();

        for (RaftPeer peer : peers) {
            Thread t = new Thread(() -> {
                int attempts = 0;
                while (true) {
                    // First verify raw TCP connectivity
                    try (java.net.Socket s = new java.net.Socket()) {
                        // Get host/port from peer - you'll need to expose these
                        s.connect(new java.net.InetSocketAddress(
                                ((GrpcRaftPeer) peer).getHost(),
                                ((GrpcRaftPeer) peer).getPort()), 500);
                        log.info("[Node " + nodeId + "] TCP reachable: peer " + peer.peerId());
                    } catch (Exception tcpEx) {
                        attempts++;
                        log.warning("[Node " + nodeId + "] TCP FAILED for peer " + peer.peerId()
                                + " attempt " + attempts + ": " + tcpEx.getMessage());
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                        continue;
                    }

                    // Then try gRPC
                    try {
                        peer.requestVote(0, nodeId, -1, -1);
                        log.info("[Node " + nodeId + "] gRPC reachable: peer " + peer.peerId());
                        break;
                    } catch (Exception e) {
                        log.warning("[Node " + nodeId + "] TCP ok but gRPC failed peer "
                                + peer.peerId() + ": " + e.getMessage());
                        e.printStackTrace();
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    }
                }
            });
            t.start();
            threads.add(t);
        }

        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException ignored) {}
        }
    }





    // Leader Election
    private synchronized void startElection(){
        if (role == Role.LEADER) return;
        role = Role.CANDIDATE;

        int term = currentTerm.incrementAndGet();
        votedFor = nodeId;
        electionsStarted.incrementAndGet();

        log.info("[Node " + nodeId + "] Started election for term " + term);

        AtomicInteger votes = new AtomicInteger(1); // selft vote
        int majority = (peers.size()+1)/2+1;

        resetElectionTimer(); // reschedule next election if this one fails

        for(RaftPeer peer: peers){
            rpcExecutor.submit(()->
                    {
                        VoteResponse resp = null;
                        try {
                            resp = peer.requestVote( term, nodeId, lastLogIndex(), lastLogTerm());
                        } catch (Exception e) {
                            log.warning("[Node " + nodeId + "] Failed to reach peer " + peer.peerId() + ": " + e.getMessage());
                            return; // Gracefully exit the thread. We just don't get this vote
                        }

                        synchronized (this) {
                            // if response node's term is greater, the current node must step down
                            if (resp.term() > currentTerm.get()) {
                                stepDown(resp.term());
                                return;
                            }
                            // if the response node granted its vote, the current node can update its vote
                            if (role == Role.CANDIDATE && resp.granted() && votes.incrementAndGet() >= majority) {
                                becomeLeader();
                            }
                        }
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
        if (term == 0) {
            return new VoteResponse(0, false);
        }
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
        log.info("[Node " + nodeId + "] Stepping down; new term = "+newTerm);
        currentTerm.set(newTerm);
        role = Role.FOLLOWER;
        votedFor = null;

        if(heartbeatTask !=null) heartbeatTask.cancel(false);
        resetElectionTimer();
    }

    private void becomeLeader(){
        if (role == Role.LEADER) return;
        if (electionTimer != null) electionTimer.cancel(false);
        role = Role.LEADER;
        leaderId = nodeId;
        log.info("[Node " + nodeId + "] Became LEADER for term " + currentTerm.get());

        for(RaftPeer peer:peers){
            nextIndex.put(peer.peerId(), raftLog.size());
            matchIndex.put(peer.peerId(),-1);
        }

        if(electionTimer != null) electionTimer.cancel(false);
        startHeartBeats();

    }


    private synchronized void startHeartBeats(){
        if(role != Role.LEADER) return;

        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            for(RaftPeer peer : peers) {
                rpcExecutor.submit(()-> {
                    try {
                        replicateToPeer(peer);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }, 0, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void replicateToPeer(RaftPeer peer) throws Exception {
        int peerId = peer.peerId();
        int prevIdx;
        int prevTrm;
        int currentTermCopy;
        int commitIndexCopy;
        List<LogEntry> entries = new ArrayList<>();

        // 1. Lock only briefly to gather the current state
        synchronized (this) {
            if (role != Role.LEADER) return; // Abort if we lost leadership

            currentTermCopy = currentTerm.get();
            commitIndexCopy = commitIndex;
            prevIdx = nextIndex.getOrDefault(peerId, 0) - 1;
            prevTrm = (prevIdx >= 0 && prevIdx < raftLog.size()) ? raftLog.get(prevIdx).term() : -1;

            for (int i = nextIndex.getOrDefault(peerId, 0); i < raftLog.size(); i++) {
                entries.add(raftLog.get(i));
            }
        }

        // 2. Perform the blocking network call WITHOUT holding the lock!
        AppendResponse resp = peer.appendEntries(currentTermCopy, nodeId, prevIdx, prevTrm, entries, commitIndexCopy);

        // 3. Lock again only to update the state with the response
        synchronized (this) {
            if (resp.term() > currentTerm.get()) {
                stepDown(resp.term());
                return;
            }

            if (resp.success()) {
                int newMatch = prevIdx + entries.size();
                matchIndex.put(peerId, newMatch);
                nextIndex.put(peerId, newMatch + 1);
                advanceCommitIndex();
            } else {
                nextIndex.put(peerId, Math.max(0, nextIndex.getOrDefault(peerId, 0) - 1));
            }
        }
    }

    // Handle an AppendEntries RPC (heartbeat or replication).

    public synchronized AppendResponse appendEntries(int term, int leaderId, int prevLogIdx, int prevLogTrm,
                                                     List<LogEntry> entries, int leaderCommit){

        if(term<currentTerm.get()) return new AppendResponse(currentTerm.get(), false);

        if (term >= currentTerm.get()) {
            // A valid leader is asserting authority — always step down
            if (term > currentTerm.get()) stepDown(term);
            else if (role == Role.CANDIDATE) {   // ← MISSING: same term, but we lost
                role = Role.FOLLOWER;
                votedFor = null;
                if (heartbeatTask != null) heartbeatTask.cancel(false);
            }
            this.leaderId = leaderId;
            resetElectionTimer();
        }

        if(prevLogIdx >=0){
            if(prevLogIdx >= raftLog.size() || raftLog.get(prevLogIdx).term() != prevLogTrm){
                return new AppendResponse(currentTerm.get(),false);
            }
        }

        int insertAt = prevLogIdx + 1;

        for(int i=0;i<entries.size();i++){
            int idx = insertAt + i;

            if(idx < raftLog.size()){
                if(raftLog.get(idx).term() != entries.get(i).term()){
                    while(raftLog.size()>idx) raftLog.remove(raftLog.size() -1);
                    raftLog.add(entries.get(i));
                }
            }
            else {
                raftLog.add(entries.get(i));
            }
        }

        if(leaderCommit > commitIndex){
            commitIndex = Math.min(leaderCommit, raftLog.size()-1);
            applyCommittedEntries();
        }

        return new AppendResponse(currentTerm.get(), true);
    }

    private void applyCommittedEntries() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = raftLog.get(lastApplied);
            applyToStateMachine(entry);
            logsCommitted.incrementAndGet();
            log.info("[Node " + nodeId + "] Applied log[" + lastApplied + "]: " + entry.command());
        }
    }

    private void applyToStateMachine(LogEntry entry) {
        String[] parts = entry.command().split("=", 2);
        if (parts.length == 2) stateMachine.put(parts[0].trim(), parts[1].trim());
    }

    private void advanceCommitIndex() {
        for (int n = raftLog.size() - 1; n > commitIndex; n--) {
            if (raftLog.get(n).term() != currentTerm.get()) continue; // §5.4.2 safety
            int replicatedCount = 1; // self
            for (int m : matchIndex.values()) if (m >= n) replicatedCount++;
            if (replicatedCount > (peers.size() + 1) / 2) {
                commitIndex = n;
                applyCommittedEntries();
                break;
            }
        }
    }

    public synchronized boolean submitCommand(String command) {
        if (role != Role.LEADER) {
            log.warning("[Node " + nodeId + "] Not leader (leader=" + leaderId + "). Redirect client.");
            return false;
        }
        raftLog.add(new LogEntry(currentTerm.get(), command));
        log.info("[Node " + nodeId + "] Appended command: " + command + " at index " + (raftLog.size() - 1));
        return true;
    }

    public String readFromStateMachine(String key) { return stateMachine.getOrDefault(key, null); }

}

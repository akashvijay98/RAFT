package raft;

import java.util.*;
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



    public int    getNodeId()           { return nodeId; }
    public Role   getRole()             { return role; }
    public int    getCurrentTerm()      { return currentTerm.get(); }
    public int    getLeaderId()         { return leaderId; }
    public int    getCommitIndex()      { return commitIndex; }
    public int    getLogSize()          { return raftLog.size(); }
    public int    getElectionsStarted() { return electionsStarted.get(); }
    public int    getLogsCommitted()    { return logsCommitted.get(); }
    public Map<String, String> getStateMachine() { return Collections.unmodifiableMap(stateMachine); }


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

    private void becomeLeader(){
        role = Role.LEADER;
        leaderId = nodeId;
        log.info("[Node " + nodeId + "] Became LEADER for term " + currentTerm.get());

        for(RaftNode peer:peers){
            nextIndex.put(peer.getNodeId(), raftLog.size());
            matchIndex.put(peer.getNodeId(),-1);
        }

        if(electionTimer != null) electionTimer.cancel(false);
        startHeartBeats();

    }


    private synchronized void startHeartBeats(){
        if(role != Role.LEADER) return;

        for(RaftNode peer:peers){
            scheduler.submit(()-> replicateToPeer(peer));
        }
    }

    private synchronized void replicateToPeer(RaftNode peer){
        int peerId = peer.getNodeId();
        int prevIdx = nextIndex.getOrDefault(peerId,0) - 1;
        int prevTrm = (prevIdx >= 0 && prevIdx < raftLog.size()) ? raftLog.get(prevIdx).term() : -1;

        List<LogEntry> entries = new ArrayList<>();
        for(int i=nextIndex.getOrDefault(peerId,0); i< raftLog.size();i++){
            entries.add(raftLog.get(i));
        }

        AppendResponse resp = peer.appendEntries(currentTerm.get(), nodeId, prevIdx, prevTrm, entries, commitIndex);

        synchronized (this){
            if(resp.term() > currentTerm.get()){ stepDown(resp.term()); return; }

            if(resp.success()){
                int newMatch = prevIdx + entries.size();
                matchIndex.put(peerId, newMatch);
                nextIndex.put(peerId, newMatch+1);
                advanceCommitIndex();
            }
            else{
                nextIndex.put(peerId, Math.max(0, nextIndex.getOrDefault(peerId,0)-1));
            }
        }

    }

    // Handle an AppendEntries RPC (heartbeat or replication).

    public synchronized AppendResponse appendEntries(int term, int leaderId, int prevLogIdx, int prevLogTrm,
                                                     List<LogEntry> entries, int leaderCommit){

        if(term<currentTerm.get()) return new AppendResponse(currentTerm.get(), false);

        if(term > currentTerm.get()) stepDown(term);

        this.leaderId = leaderId;
        resetElectionTimer();

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

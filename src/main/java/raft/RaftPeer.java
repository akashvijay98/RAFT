package raft;

import raft.AppendResponse;
import raft.VoteResponse;

import java.util.List;

public interface RaftPeer {

    // The ID of the remote node
    int peerId();


    // request Vote RPC- ask peer for vote
    VoteResponse requestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm)
            throws Exception;

   // Append Entries RPC - for logReplication from the leader Node.
    AppendResponse appendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm,
                                 List<LogEntry> entries, int leaderCommit) throws Exception;
}

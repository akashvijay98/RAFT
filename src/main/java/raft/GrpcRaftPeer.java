package raft;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import raft.grpc.AppendEntriesRequest;
import raft.grpc.AppendEntriesResponse;
import raft.grpc.RaftServiceGrpc;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class GrpcRaftPeer implements RaftPeer {
    private static final Logger log = Logger.getLogger(GrpcRaftPeer.class.getName());

    private static final int RPC_DEADLINE_MS = 5000;
    private static final int PROBE_DEADLINE_MS = 500;
    private static final long MIN_CHANNEL_RESET_INTERVAL_MS = 2_000;

    private final int peerId;
    private final String host;
    private final int port;

    private volatile ManagedChannel channel;
    private volatile RaftServiceGrpc.RaftServiceBlockingStub stub;
    private volatile long lastChannelResetAtMs = 0;

    public GrpcRaftPeer(int peerId, String host, int port) {
        this.peerId = peerId;
        this.host = host;
        this.port = port;

        resetChannel("init");
    }

    @Override
    public int peerId() {
        return peerId;
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

    private synchronized void resetChannel(String reason) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastChannelResetAtMs < MIN_CHANNEL_RESET_INTERVAL_MS) {
            return;
        }
        lastChannelResetAtMs = nowMs;

        ManagedChannel old = this.channel;
        if (old != null) {
            old.shutdownNow();
        }

        String resolved;
        try {
            resolved = resolveIpv4OrFirst(host);
        } catch (Exception ex) {
            // If DNS fails, keep the raw host. (It will fail fast and we'll retry on the next reset.)
            resolved = host;
        }

        // Use a resolved InetSocketAddress (IP) so Netty doesn't attempt to connect with an
        // unresolved address (which can surface as "Invalid argument: /host:port").
        InetSocketAddress remote = new InetSocketAddress(resolved, port);

        this.channel = NettyChannelBuilder
                .forAddress(remote)
                .usePlaintext()
                .build();
        this.stub = RaftServiceGrpc.newBlockingStub(channel);

        log.info("GrpcRaftPeer channel reset -> " + host + ":" + port + " (peer " + peerId + ", resolved=" + resolved + ", reason=" + reason + ")");
    }

    private static boolean shouldResetChannel(StatusRuntimeException e) {
        return e.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE;
    }

    @Override
    public VoteResponse requestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm) throws Exception {
        raft.grpc.VoteRequest req = raft.grpc.VoteRequest.newBuilder()
                .setTerm(term)
                .setCandidateId(candidateId)
                .setLastLogIndex(lastLogIndex)
                .setLastLogTerm(lastLogTerm)
                .build();

        long deadlineMs = (term == 0) ? PROBE_DEADLINE_MS : RPC_DEADLINE_MS;

        try {
            raft.grpc.VoteResponse resp = stub
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .requestVote(req);
            return new VoteResponse(resp.getTerm(), resp.getGranted());

        } catch (StatusRuntimeException e) {
            if (shouldResetChannel(e)) {
                resetChannel("requestVote:" + e.getStatus().getCode());
            }
            log.warning("[GrpcRaftPeer] requestVote to peer " + peerId + " failed: " + e.getStatus());
            throw e;
        }
    }

    public AppendResponse appendEntries(
            int term,
            int leaderId,
            int prevLogIndex,
            int prevLogTerm,
            List<LogEntry> entries,
            int leaderCommit
    ) throws Exception {

        try {
            AppendEntriesRequest.Builder builder = AppendEntriesRequest.newBuilder()
                    .setTerm(term)
                    .setLeaderId(leaderId)
                    .setPrevLogIndex(prevLogIndex)
                    .setPrevLogTerm(prevLogTerm)
                    .setLeaderCommit(leaderCommit);

            for (LogEntry e : entries) {
                builder.addEntries(raft.grpc.LogEntry.newBuilder()
                        .setTerm(e.term())
                        .setCommand(e.command())
                        .build());
            }

            AppendEntriesResponse resp = stub
                    .withDeadlineAfter(RPC_DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .appendEntries(builder.build());

            return new AppendResponse(resp.getTerm(), resp.getSuccess());

        } catch (StatusRuntimeException e) {
            if (shouldResetChannel(e)) {
                resetChannel("appendEntries:" + e.getStatus().getCode());
            }
            log.warning("AppendEntries to peer " + peerId + " failed: " + e.getStatus());
            throw e;
        }
    }

    public void shutdown() throws InterruptedException {
        ManagedChannel ch = channel;
        if (ch != null) {
            ch.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}

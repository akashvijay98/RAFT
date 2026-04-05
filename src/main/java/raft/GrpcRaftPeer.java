package raft;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel;
import raft.grpc.AppendEntriesRequest;
import raft.grpc.AppendEntriesResponse;
import raft.grpc.RaftServiceGrpc;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class GrpcRaftPeer implements RaftPeer{
    private static final Logger log = Logger.getLogger(GrpcRaftPeer.class.getName());

    private static final int RPC_DEADLINE_MS = 5000;

    private final int peerId;
    private final ManagedChannel channel;
    private  final RaftServiceGrpc.RaftServiceBlockingStub stub;
    private final String host;
    private final int port;

    public GrpcRaftPeer(int peerId, String host, int port){
        this.peerId = peerId;
        this.host = host;
        this.port = port;

        String resolvedIp;
        try {
            // Force IPv4 - get all addresses and pick the IPv4 one
            InetAddress[] addresses = InetAddress.getAllByName(host);

            String ipv4 = null;
            for (InetAddress addr : addresses) {
                log.info("DNS result for " + host + ": " + addr.getHostAddress()
                        + " type: " + addr.getClass().getSimpleName());
                if (addr instanceof java.net.Inet4Address) {
                    ipv4 = addr.getHostAddress();
                    break;
                }
            }
            resolvedIp = (ipv4 != null) ? ipv4 : addresses[0].getHostAddress();
            log.info("GrpcRaftPeer resolved " + host + " -> " + resolvedIp);
        } catch (Exception e) {
            log.warning("DNS resolution failed for " + host + ": " + e.getMessage());
            resolvedIp = host;
        }

        NioEventLoopGroup group = new NioEventLoopGroup();
        log.info("GrpcRaftPeer creating channel to: [" + resolvedIp + "] port: " + port
                + " class: " + resolvedIp.getClass().getName());

        // Replace the channel creation with this
        java.net.InetSocketAddress socketAddress = new java.net.InetSocketAddress(resolvedIp, port);
        log.info("Socket address: " + socketAddress + " isUnresolved: " + socketAddress.isUnresolved());

        this.channel = NettyChannelBuilder
                .forAddress(socketAddress)   // pass InetSocketAddress, not String
                .channelType(NioSocketChannel.class)
                .eventLoopGroup(group)
                .usePlaintext()
                .build();

        this.stub = RaftServiceGrpc.newBlockingStub(channel);
        log.info("GrpcRaftPeer created → " + host + ":" + port + " (peer " + peerId + ")");

    }

    @Override
    public int peerId() { return peerId; }


    // ── RequestVote from a Peer node
    private static final int PROBE_DEADLINE_MS = 500;

    @Override
    public VoteResponse requestVote(int term, int candidateId, int lastLogIndex,
                                    int lastLogTerm) throws Exception {
        raft.grpc.VoteRequest req = raft.grpc.VoteRequest.newBuilder()
                .setTerm(term)
                .setCandidateId(candidateId)
                .setLastLogIndex(lastLogIndex)
                .setLastLogTerm(lastLogTerm)
                .build();

        long deadline = (term == 0) ? PROBE_DEADLINE_MS : RPC_DEADLINE_MS;

        try {
            raft.grpc.VoteResponse resp = stub
                    .withDeadlineAfter(deadline, TimeUnit.MILLISECONDS)
                    .requestVote(req);
            return new VoteResponse(resp.getTerm(), resp.getGranted());

        } catch (StatusRuntimeException e) {
            log.warning("[GrpcRaftPeer] Full exception for peer " + peerId + ": " + e.getStatus());
            if (e.getCause() != null) {
                log.warning("[GrpcRaftPeer] Caused by: " + e.getCause());
                if (e.getCause().getCause() != null) {
                    log.warning("[GrpcRaftPeer] Root cause: " + e.getCause().getCause());
                }
            }
            e.printStackTrace();
            throw e;
        }
    }

    // ── AppendEntries to a PeerNode
    public AppendResponse appendEntries(int term, int leaderId,
                                        int prevLogIndex, int prevLogTerm,
                                        List<LogEntry> entries, int leaderCommit) throws Exception {

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
            log.warning("AppendEntries to peer " + peerId + " failed: " + e.getStatus());
            throw e;
        }

    }


    // channel shutdown
    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
    public String getHost() { return host; }
    public int getPort() { return port; }
}

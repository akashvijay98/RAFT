package raft;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import io.grpc.stub.StreamObserver;

import raft.grpc.*;



public class RaftServiceImpl extends RaftServiceGrpc.RaftServiceImplBase{

    private static final Logger log = Logger.getLogger(RaftServiceImpl.class.getName());

    private final RaftNode node;

    public RaftServiceImpl(RaftNode node){ this.node = node;

    }


    @Override
    public void requestVote(raft.grpc.VoteRequest req, StreamObserver<raft.grpc.VoteResponse> responseObserver){
        try{
            VoteResponse result = node.requestVote(
                    req.getTerm(),
                    req.getCandidateId(),
                    req.getLastLogIndex(),
                    req.getLastLogTerm()
            );

            raft.grpc.VoteResponse proto = raft.grpc.VoteResponse.newBuilder()
                    .setTerm(result.term())
                    .setGranted(result.granted())
                    .build();

            responseObserver.onNext(proto);
            responseObserver.onCompleted();
        }
        catch (Exception e){
            log.severe("requestVote handler error: " + e.getMessage());
            responseObserver.onError(e);
        }

    }

    public void appendEntries(AppendEntriesRequest req, StreamObserver<AppendEntriesResponse> responseObserver){
        try{
            List<LogEntry> entries = req.getEntriesList().stream()
                    .map(e-> new LogEntry(e.getTerm(), e.getCommand()))
                    .collect(Collectors.toList());

            AppendResponse result = node.appendEntries(
                    req.getTerm(),
                    req.getLeaderId(),
                    req.getPrevLogIndex(),
                    req.getPrevLogTerm(),
                    entries,
                    req.getLeaderCommit()
            );

            AppendEntriesResponse proto= AppendEntriesResponse.newBuilder()
                    .setTerm(result.term())
                    .setSuccess(result.success())
                    .build();

            responseObserver.onNext(proto);
            responseObserver.onCompleted();
        }
        catch (Exception e){
            log.severe("appendEntries handler error: " + e.getMessage());
            responseObserver.onError(e);
        }
    }

    @Override
    public void submitCommand(CommandRequest req,
                              StreamObserver<CommandResponse> responseObserver) {
        try {
            boolean accepted = node.submitCommand(req.getCommand());

            CommandResponse proto = CommandResponse.newBuilder()
                    .setSuccess(accepted)
                    .setLeaderId(node.getLeaderId())
                    .setMessage(accepted
                            ? "Command accepted"
                            : "Not leader, redirect to node " + node.getLeaderId())
                    .build();

            responseObserver.onNext(proto);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.severe("submitCommand handler error: " + e.getMessage());
            responseObserver.onError(e);
        }
    }

    // ── Read ───────────────────────────────────────────────────────────────

    @Override
    public void read(ReadRequest req, StreamObserver<ReadResponse> responseObserver) {
        try {
            String value = node.readFromStateMachine(req.getKey());

            ReadResponse proto = ReadResponse.newBuilder()
                    .setFound(value != null)
                    .setValue(value != null ? value : "")
                    .build();

            responseObserver.onNext(proto);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.severe("read handler error: " + e.getMessage());
            responseObserver.onError(e);
        }
    }

    // ── GetStatus ──────────────────────────────────────────────────────────

    @Override
    public void getStatus(StatusRequest req, StreamObserver<StatusResponse> responseObserver) {
        try {
            StatusResponse proto = StatusResponse.newBuilder()
                    .setNodeId(node.getNodeId())
                    .setRole(node.getRole().name())
                    .setCurrentTerm(node.getCurrentTerm())
                    .setLeaderId(node.getLeaderId())
                    .setCommitIndex(node.getCommitIndex())
                    .setLogSize(node.getLogSize())
                    .setElectionsStarted(node.getElectionsStarted())
                    .setLogsCommitted(node.getLogsCommitted())
                    .build();

            responseObserver.onNext(proto);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.severe("getStatus handler error: " + e.getMessage());
            responseObserver.onError(e);
        }
    }
}

package raft;

public record AppendResponse(int term, boolean success) {
}

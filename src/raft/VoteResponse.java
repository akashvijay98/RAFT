package raft;

public record VoteResponse(int term, boolean granted) {

}

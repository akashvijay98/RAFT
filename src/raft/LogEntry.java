package raft;

public record LogEntry(int term, String command) {
}

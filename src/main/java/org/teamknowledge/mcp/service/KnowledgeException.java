package org.teamknowledge.mcp.service;

public class KnowledgeException extends RuntimeException {
    private final String code;
    public KnowledgeException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}

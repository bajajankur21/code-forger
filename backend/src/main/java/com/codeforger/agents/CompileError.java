package com.codeforger.agents;

public record CompileError(String file, int line, String message) {
}

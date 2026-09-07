package com.localcore.server;

final class HttpProblem extends RuntimeException {
    final int status;
    final String type;

    HttpProblem(int status, String type, String message) {
        super(message);
        this.status = status;
        this.type = type;
    }
}


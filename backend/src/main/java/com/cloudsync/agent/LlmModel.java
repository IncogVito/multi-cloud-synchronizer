package com.cloudsync.agent;

public record LlmModel(String id) {

    @Override
    public String toString() {
        return id;
    }
}

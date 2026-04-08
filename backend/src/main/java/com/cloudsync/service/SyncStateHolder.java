package com.cloudsync.service;

import com.cloudsync.model.dto.SyncProgressEvent;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Singleton
public class SyncStateHolder {

    private final ConcurrentHashMap<String, SyncProgressEvent> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<FluxSink<SyncProgressEvent>>> sinks = new ConcurrentHashMap<>();

    public void updateAndEmit(String accountId, SyncProgressEvent event) {
        states.put(accountId, event);
        List<FluxSink<SyncProgressEvent>> accountSinks = sinks.getOrDefault(accountId, List.of());
        accountSinks.forEach(sink -> sink.next(event));
    }

    public Publisher<SyncProgressEvent> subscribe(String accountId) {
        return Flux.create(sink -> {
            sinks.computeIfAbsent(accountId, k -> new CopyOnWriteArrayList<>()).add(sink);
            sink.onDispose(() -> sinks.getOrDefault(accountId, List.of()).remove(sink));
            Optional.ofNullable(states.get(accountId)).ifPresent(sink::next);
        });
    }

    public Optional<SyncProgressEvent> getSnapshot(String accountId) {
        return Optional.ofNullable(states.get(accountId));
    }
}

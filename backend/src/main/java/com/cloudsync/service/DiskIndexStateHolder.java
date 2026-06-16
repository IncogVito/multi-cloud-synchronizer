package com.cloudsync.service;

import com.cloudsync.model.dto.DiskIndexProgressEvent;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-account disk-index progress state and SSE fan-out (account-scoped, mirroring
 * {@link SyncStateHolder}). Each account has its own last event and set of subscribers.
 */
@Singleton
public class DiskIndexStateHolder {

    private final ConcurrentHashMap<String, DiskIndexProgressEvent> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<FluxSink<DiskIndexProgressEvent>>> sinks = new ConcurrentHashMap<>();

    public void updateAndEmit(String accountId, DiskIndexProgressEvent event) {
        states.put(accountId, event);
        List<FluxSink<DiskIndexProgressEvent>> accountSinks = sinks.get(accountId);
        if (accountSinks != null) {
            accountSinks.removeIf(sink -> {
                try {
                    sink.next(event);
                    return false;
                } catch (Exception e) {
                    return true;
                }
            });
        }
    }

    public Publisher<DiskIndexProgressEvent> subscribe(String accountId) {
        return Flux.create(sink -> {
            sinks.computeIfAbsent(accountId, k -> new CopyOnWriteArrayList<>()).add(sink);
            sink.onDispose(() -> sinks.getOrDefault(accountId, List.of()).remove(sink));
            Optional.ofNullable(states.get(accountId)).ifPresent(sink::next);
        });
    }

    public Optional<DiskIndexProgressEvent> getSnapshot(String accountId) {
        return Optional.ofNullable(states.get(accountId));
    }
}

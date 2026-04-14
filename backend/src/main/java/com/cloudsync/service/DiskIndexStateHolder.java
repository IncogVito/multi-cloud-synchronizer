package com.cloudsync.service;

import com.cloudsync.model.dto.DiskIndexProgressEvent;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Singleton
public class DiskIndexStateHolder {

    private volatile DiskIndexProgressEvent lastEvent;
    private final List<FluxSink<DiskIndexProgressEvent>> sinks = new CopyOnWriteArrayList<>();

    public void updateAndEmit(DiskIndexProgressEvent event) {
        lastEvent = event;
        sinks.removeIf(sink -> {
            try {
                sink.next(event);
                return false;
            } catch (Exception e) {
                return true;
            }
        });
    }

    public Publisher<DiskIndexProgressEvent> subscribe() {
        return Flux.create(sink -> {
            sinks.add(sink);
            sink.onDispose(() -> sinks.remove(sink));
            Optional.ofNullable(lastEvent).ifPresent(sink::next);
        });
    }

    public Optional<DiskIndexProgressEvent> getSnapshot() {
        return Optional.ofNullable(lastEvent);
    }
}

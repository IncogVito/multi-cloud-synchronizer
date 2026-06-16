package com.cloudsync.service;

import com.cloudsync.model.dto.DiskIndexProgressEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-account state + SSE fan-out for disk indexing (issue #8). Each account must have an
 * isolated last-event snapshot and isolated subscriber stream — mirroring {@link SyncStateHolder}.
 */
class DiskIndexStateHolderTest {

    DiskIndexStateHolder holder;

    @BeforeEach
    void setUp() {
        holder = new DiskIndexStateHolder();
    }

    private DiskIndexProgressEvent event(String phase, int scanned) {
        DiskIndexProgressEvent e = new DiskIndexProgressEvent(phase);
        e.setScanned(scanned);
        return e;
    }

    private List<DiskIndexProgressEvent> collect(String accountId) {
        List<DiskIndexProgressEvent> received = new CopyOnWriteArrayList<>();
        // Subscription is left active for the duration of the test (not disposed) so it keeps
        // receiving fan-out events into the captured list.
        Flux.from(holder.subscribe(accountId)).subscribe(received::add);
        return received;
    }

    @Test
    void getSnapshot_unknownAccount_isEmpty() {
        assertTrue(holder.getSnapshot("nobody").isEmpty());
    }

    @Test
    void updateAndEmit_storesSnapshotPerAccount_isolated() {
        DiskIndexProgressEvent a = event("SCANNING", 3);
        DiskIndexProgressEvent b = event("DONE", 9);

        holder.updateAndEmit("acc-a", a);
        holder.updateAndEmit("acc-b", b);

        assertSame(a, holder.getSnapshot("acc-a").orElseThrow());
        assertSame(b, holder.getSnapshot("acc-b").orElseThrow());
    }

    @Test
    void subscriber_receivesOnlyOwnAccountEvents() {
        List<DiskIndexProgressEvent> a = collect("acc-a");
        List<DiskIndexProgressEvent> b = collect("acc-b");

        holder.updateAndEmit("acc-a", event("SCANNING", 1));
        holder.updateAndEmit("acc-a", event("DONE", 2));
        holder.updateAndEmit("acc-b", event("SCANNING", 5));

        assertEquals(2, a.size(), "acc-a subscriber must see its 2 events");
        assertEquals(1, b.size(), "acc-b subscriber must see only its own event");
        assertEquals(5, b.get(0).getScanned());
    }

    @Test
    void lateSubscriber_replaysLastEventImmediately() {
        holder.updateAndEmit("acc-a", event("DONE", 42));

        List<DiskIndexProgressEvent> received = collect("acc-a");

        assertEquals(1, received.size(), "late subscriber must immediately get the cached last event");
        assertEquals(42, received.get(0).getScanned());
    }
}

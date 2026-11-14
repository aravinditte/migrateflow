package com.migrateflow.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEST-ONLY fault injection (spec 13.2): when migrateflow.test-fail-after-chunks
 * is set (>0), a run's batch writer throws once after that many of its chunks
 * have committed, simulating a worker crash mid-run. State is in-memory and
 * per run id, so each run fails at most once per JVM and its same-identity
 * restart proceeds. Never enable outside tests.
 */
@Component
public class FaultInjection {
    private final int failAfterChunks;
    private final ConcurrentHashMap<UUID, AtomicInteger> committedChunks = new ConcurrentHashMap<>();
    private final Set<UUID> fired = ConcurrentHashMap.newKeySet();

    public FaultInjection(@Value("${migrateflow.test-fail-after-chunks:0}") int failAfterChunks) {
        this.failAfterChunks = failAfterChunks;
    }

    public void onChunkCommitted(UUID runId) {
        committedChunks.computeIfAbsent(runId, k -> new AtomicInteger()).incrementAndGet();
    }

    /** Called by writers before writing a chunk; throws the injected failure at most once per run. */
    public void beforeWrite(UUID runId) {
        if (failAfterChunks <= 0) return;
        int committed = committedChunks.getOrDefault(runId, new AtomicInteger(0)).get();
        if (committed >= failAfterChunks && fired.add(runId)) {
            throw new IllegalStateException("Injected test failure for run " + runId
                    + " after " + committed + " committed chunks");
        }
    }

    public boolean enabled() { return failAfterChunks > 0; }
}

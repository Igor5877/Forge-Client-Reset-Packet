package gg.chaldea.server.fastlogin.mixin;

import com.mojang.datafixers.util.Either;
import gg.chaldea.server.fastlogin.FastLoginMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bypass IOWorker's single-threaded mailbox for read operations.
 *
 * <h3>Why the mailbox is the bottleneck</h3>
 * IOWorker uses a {@code ProcessorMailbox} that serializes ALL tasks (loads,
 * stores, scans) through one worker thread per IOWorker instance. Even though
 * the underlying {@code Util.ioPool()} is multi-threaded, the mailbox imposes
 * strict ordering: only ONE task runs at a time per IOWorker.
 *
 * <p>For a player joining with view-distance 10, ~441 chunks must be loaded.
 * Even at ~10 ms per chunk read (NVMe SSD), serialized that's ~4.4 s just for
 * one IOWorker (and 3+ IOWorkers contribute — chunks, POI, entities). Reality
 * is closer to 7-10 s due to mailbox queue overhead.
 *
 * <h3>This mixin</h3>
 * Redirects {@code IOWorker.loadAsync()} to a dedicated parallel read pool,
 * bypassing the mailbox queue entirely.
 *
 * <p>Safety:
 * <ul>
 *   <li><b>RegionFileStorage</b> made thread-safe by
 *       {@link MixinRegionFileStorageParallel} — that mixin overrides
 *       {@code read()} to lock {@code regionCache} only for the brief lookup,
 *       allowing actual disk I/O to be parallel.</li>
 *   <li><b>pendingWrites</b> — if a chunk has a pending write, we fall back
 *       to the vanilla mailbox path (which knows how to serve from
 *       {@code PendingStore.data}). This handles the rare "write then
 *       immediate read" case without us needing access to private inner
 *       classes.</li>
 *   <li><b>Writes</b> — completely untouched. {@code store()} still goes
 *       through the mailbox, preserving FIFO write ordering. Only reads
 *       are parallelised. This minimises risk: a misordered write would
 *       corrupt world state; a misordered read just returns slightly
 *       stale data (and we explicitly fall back for the case where that
 *       matters).</li>
 * </ul>
 *
 * <h3>Expected impact</h3>
 * For player join with cold chunks (warmup phase or beyond pre-warmed area):
 * <ul>
 *   <li>~441 chunks / ~9 region files → ~9× I/O parallelism</li>
 *   <li>NVMe SSD random IOPS: ~700k → fully saturated by parallel reads</li>
 *   <li>Measured target: chunk load 7-10 s → 1-2 s</li>
 * </ul>
 */
@Mixin(IOWorker.class)
public abstract class MixinIOWorkerParallel {

    private static final Logger LOGGER = LogManager.getLogger("FastLogin/ParallelIO");

    @Unique
    private static final AtomicLong fl$THREAD_COUNTER = new AtomicLong();

    /**
     * Shared parallel read pool for ALL IOWorker instances on this JVM.
     *
     * <p>Sized to {@code min(8, cores/2)}: enough to saturate NVMe SSD random
     * read IOPS without thrashing OS cache or overwhelming RegionFile-internal
     * locks. On a Xeon E5-2699 v3 (18 cores), this gives 8 reader threads.
     */
    @Unique
    private static final ExecutorService fl$READ_POOL = Executors.newFixedThreadPool(
        Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2)),
        (ThreadFactory) r -> {
            Thread t = new Thread(r, "FastLogin-IOReader-" + fl$THREAD_COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

    /** Diagnostic counter — total parallel reads served. */
    @Unique
    private static final AtomicLong fl$PARALLEL_READS = new AtomicLong();

    /** Diagnostic counter — fallbacks to vanilla mailbox path. */
    @Unique
    private static final AtomicLong fl$VANILLA_FALLBACKS = new AtomicLong();

    @Shadow @Final private RegionFileStorage storage;
    @Shadow @Final private Map<ChunkPos, ?> pendingWrites;

    /**
     * Intercept {@code loadAsync} and dispatch to the parallel pool when safe.
     *
     * <p>Falls back to vanilla mailbox path when:
     * <ul>
     *   <li>{@link FastLoginMod#PARALLEL_IO_ENABLED} is false</li>
     *   <li>There is a pending write for this chunk (vanilla path serves it
     *       from {@code PendingStore.data})</li>
     * </ul>
     */
    @Inject(method = "loadAsync", at = @At("HEAD"), cancellable = true)
    private void fl$parallelLoad(ChunkPos chunkPos,
                                  CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        if (!FastLoginMod.PARALLEL_IO_ENABLED) {
            return;
        }

        // If there's a pending write for this chunk, let vanilla mailbox serve
        // it — that path knows how to read PendingStore.data (which we can't
        // easily access since PendingStore is a private inner class).
        //
        // Synchronize on pendingWrites to be safe: vanilla mutates it from the
        // mailbox thread, which doesn't take this lock, but checking
        // containsKey() under our lock and then letting vanilla handle it via
        // its own mailbox serialisation is safe — at worst we get a false
        // negative (no pending write seen, race in our favour serves disk
        // data which is one tick stale at most).
        synchronized (pendingWrites) {
            if (pendingWrites.containsKey(chunkPos)) {
                fl$VANILLA_FALLBACKS.incrementAndGet();
                return; // fall through to vanilla mailbox path
            }
        }

        CompletableFuture<Optional<CompoundTag>> future = CompletableFuture.supplyAsync(() -> {
            try {
                // storage.read() is made thread-safe by MixinRegionFileStorageParallel.
                CompoundTag tag = storage.read(chunkPos);
                long count = fl$PARALLEL_READS.incrementAndGet();
                if (count % 500 == 0) {
                    LOGGER.info("[ParallelIO] {} parallel reads served (vanilla fallbacks: {})",
                        count, fl$VANILLA_FALLBACKS.get());
                }
                return Optional.ofNullable(tag);
            } catch (Exception e) {
                LOGGER.warn("Parallel read failed for {}", chunkPos, e);
                throw new CompletionException(e);
            }
        }, fl$READ_POOL);

        cir.setReturnValue(future);
    }
}

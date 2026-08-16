package ch.uzh.ifi.access.service

import java.nio.file.Path

enum class ContainerState { IDLE, BUSY, DEAD }

/**
 * Result of running a submission inside a pooled container.
 * [timedOut] lets the caller distinguish a timeout kill (137) from a cgroup OOM kill (137),
 * exactly like the `killedContainer` flag in the one-shot path.
 */
data class ExecResult(val exitCode: Int, val timedOut: Boolean)

/**
 * One long-lived, reusable Docker container in the pool (Variant A: all tasks
 * share a single image).
 *
 * A pooled container is started once with `sleep infinity` and kept running.
 * Each submission is executed inside it via [exec] and cleaned up with [reset]
 * before the container returns to the pool — this is what replaces the expensive
 * per-submission create / start / remove cycle.
 */
interface DockerContainer {

    /** The docker container id. */
    val id: String

    /** Host directory bound into the container (at /submission). Per-submission files go here. */
    val hostWorkDir: Path

    /** IDLE = free in the pool, BUSY = currently serving a submission, DEAD = being recycled. */
    var state: ContainerState

    /** How many submissions this container has served (drives max-reuse recycling). */
    var reuseCount: Int

    /**
     * Run [script] inside the container via `docker exec`, wrapped so it is force-killed
     * after [timeoutSeconds]. Student code is executed as a NON-ROOT user (see the pool's
     * `docker.pool.user`). Returns the command's exit code (same semantics as the
     * one-shot path: 137 = OOM/timeout, 201/202 = quota, else grading exit code).
     */
    fun exec(script: String, timeoutSeconds: Long): ExecResult

    /**
     * Make the container pristine again between reuses: wipe /workspace and /submission,
     * kill any stray processes from the previous run. CRITICAL for isolation — a leak here
     * means one student could see another's data.
     */
    fun reset()

    /** Cheap liveness check (e.g. `docker inspect` → state.running == true). */
    fun isHealthy(): Boolean

    /**
     * Sanity check: the effective UID that student workloads run as inside this container
     * (i.e. `id -u` evaluated as the configured exec user). The pool refuses to serve any
     * container whose workload resolves to uid 0, so student code is never executed as root.
     */
    fun effectiveUid(): Int

    /** Stop and remove the underlying container and clean up its host workdir. */
    fun destroy()
}

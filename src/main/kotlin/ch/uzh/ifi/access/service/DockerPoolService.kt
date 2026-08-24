package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.Util
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Capability
import com.github.dockerjava.api.model.HostConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Variant A container pool: a SINGLE homogeneous pool of long-lived containers,
 * because all tasks share one image. Holds the containers directly — there is no
 * per-image sub-pool.
 *
 * DISABLED BY DEFAULT (`docker.pool.enabled=false`). While disabled, borrow()
 * returns null and ExecutionService keeps using the existing one-shot path, so
 * nothing changes at runtime until the flag is flipped.
 *
 * Sanity checks:
 *  - non-root enforcement: every container is verified to run its workload as a
 *    non-root user at creation (fail-closed — a container that resolves to uid 0
 *    is destroyed and never added to the pool).
 *  - availability: a scheduled reaper drops dead idle containers and refills the
 *    pool to [poolSize], and borrow() re-validates health before handing one out.
 */
@Service
class DockerPoolService(
    private val dockerClient: DockerClient,
    private val workingDir: Path,
    @Value("\${docker.pool.enabled:true}") val enabled: Boolean,
    @Value("\${docker.pool.image:python:latest}") private val image: String,

    /**
     * Modify the pool size if necessary. Default will be 10
     * TODO: Maybe higher?
     */
    @Value("\${docker.pool.size:10}") private val poolSize: Int,

    /**
     * For container health and simple security measure.
     * TODO: Maybe lower?
     */
    @Value("\${docker.pool.maxReuse:100}") private val maxReuse: Int,

    /**
     * How long an individual container can be borrowed until the system realizes something is wrong.
     */
    @Value("\${docker.pool.borrowTimeoutSeconds:30}") private val borrowTimeoutSeconds: Long,

    /**
     * Non-root user student workloads run as (docker `--user` syntax). Numeric so it works on
     * any image without a matching /etc/passwd entry (e.g. stock python:latest, which is root-only).
     */
    @Value("\${docker.pool.user:1000:1000}") private val execUser: String,

    /**
     * Read-only container root filesystem. With this on, the only writable surfaces are the tmpfs
     * mounts and the bound /submission — all wiped by reset() — so nothing a student writes can leak
     * into the next borrower's submission. Toggle off if a grading image needs to write elsewhere.
     */
    @Value("\${docker.pool.readOnlyRootfs:true}") private val readOnlyRootfs: Boolean,

    /**
     * Max processes/threads a container may spawn — fork-bomb DoS guard.
     */
    @Value("\${docker.pool.pidsLimit:256}") private val pidsLimit: Long,

    /**
     * Per-container CPU ceiling in cores (CFS quota) — stops one submission pegging every host core.
     * One core is enought because the python tests run single threaded.
     */
    @Value("\${docker.pool.cpuLimit:1.0}") private val cpuLimit: Double,

    /**
     * Optional metrics registry. Null when the pool is built by hand (e.g. in tests); Spring injects
     * the real one at runtime. Exposes borrow-wait time, fallback + recycle counts, and idle/busy/total
     * gauges — the observability behind "the fallback rate must be observable".
     */
    private val meterRegistry: MeterRegistry? = null,
) {
    private val logger = KotlinLogging.logger {}

    // Metrics (no-ops when meterRegistry is null, e.g. in tests).
    private val borrowWaitTimer: Timer? by lazy {
        meterRegistry?.let { Timer.builder("docker.pool.borrow.wait").register(it) }
    }
    private val fallbackCounter: Counter? by lazy {
        meterRegistry?.let { Counter.builder("docker.pool.borrow.fallback").register(it) }
    }
    private val recycleCounter: Counter? by lazy {
        meterRegistry?.let { Counter.builder("docker.pool.recycle").register(it) }
    }

    /** Ready-to-use containers (backpressure: borrow() blocks on this queue). */
    private val idle = LinkedBlockingDeque<DockerContainer>()

    /** Every container we own (idle + busy) — for shutdown and bookkeeping. */
    private val all = ConcurrentHashMap.newKeySet<DockerContainer>()

    /** Set during shutdown so the reaper / fill never resurrect the pool while we're draining. */
    @Volatile
    private var shuttingDown = false

    @PostConstruct
    fun warmup() {
        if (!enabled) {
            logger.info { "Docker pool disabled — using one-shot execution path" }
            return
        }
        if (meterRegistry != null) registerGauges()
        else logger.warn { "No MeterRegistry injected — pool metrics are disabled" }
        fill()
    }

    /** idle / busy / total container counts, sampled live off the pool's own collections. */
    private fun registerGauges() {
        val reg = meterRegistry ?: return
        Gauge.builder("docker.pool.idle", idle) { it.size.toDouble() }.register(reg)
        Gauge.builder("docker.pool.busy", all) { (it.size - idle.size).toDouble() }.register(reg)
        Gauge.builder("docker.pool.total", all) { it.size.toDouble() }.register(reg)
    }

    /** Create containers until the pool holds [poolSize] of them. Safe to call repeatedly (tops up). */
    @Synchronized
    fun fill() {
        if (!enabled || shuttingDown) return
        if (image.isBlank()) {
            logger.error { "docker.pool.enabled=true but docker.pool.image is empty — pool NOT started" }
            return
        }
        ensureImage(image)
        val missing = (poolSize - all.size).coerceAtLeast(0)
        if (missing > 0) logger.info { "Filling docker pool: +$missing (target=$poolSize, image=$image)" }
        repeat(missing) {
            runCatching { createContainer() }
                .onSuccess { c -> idle.add(c); all.add(c) }
                .onFailure { e -> logger.error(e) { "Failed to create a pool container" } }
        }
        if (all.isEmpty()) {
            logger.error {
                "Docker pool is enabled but holds 0 containers — every submission will fall back to the " +
                    "one-shot path. Check the image and the non-root user ('$execUser')."
            }
        }
    }

    /** Destroy every container and empty the pool. */
    @Synchronized
    fun drain() {
        logger.info { "Draining docker pool (${all.size} containers)" }
        all.forEach { runCatching { it.destroy() } }
        all.clear()
        idle.clear()
    }

    /** Drain then re-fill — a clean slate between benchmark runs. */
    @Synchronized
    fun reinit() {
        drain()
        fill()
    }

    /** Current pool counts, for observability / benchmarking. */
    fun status(): Map<String, Any> = mapOf(
        "enabled" to enabled,
        "image" to image,
        "user" to execUser,
        "readOnlyRootfs" to readOnlyRootfs,
        "pidsLimit" to pidsLimit,
        "cpuLimit" to cpuLimit,
        "configuredSize" to poolSize,
        "total" to all.size,
        "idle" to idle.size,
        "busy" to (all.size - idle.size),
        "maxReuse" to maxReuse,
    )

    /**
     * Borrow an idle container, blocking up to [borrowTimeoutSeconds]. Dead containers found in the
     * idle queue are discarded (and recycled) rather than handed out; borrowing keeps trying until a
     * healthy one appears or the timeout elapses. Returns null if the pool is disabled or none became
     * available in time — the caller must then fall back to the one-shot path.
     */
    fun borrow(): DockerContainer? {
        if (!enabled) return null
        val startNanos = System.nanoTime()
        val deadlineNanos = startNanos + TimeUnit.SECONDS.toNanos(borrowTimeoutSeconds)
        while (true) {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) {
                fallbackCounter?.increment()   // enabled but saturated -> caller falls back to one-shot
                return null
            }
            val c = idle.poll(remaining, TimeUnit.NANOSECONDS)
            if (c == null) {
                fallbackCounter?.increment()
                return null
            }
            if (c.isHealthy()) {
                c.state = ContainerState.BUSY
                borrowWaitTimer?.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS)
                return c
            }
            // Availability check: don't hand out a dead container — recycle it and keep looking.
            logger.warn { "borrow() found unhealthy idle container ${c.id}, recycling" }
            recycle(c)
        }
    }

    /**
     * Return a container after use: reset it and re-enqueue, or recycle it if it is
     * unhealthy or has exceeded max reuse.
     */
    fun release(container: DockerContainer) {
        try {
            container.reuseCount++
            if (container.reuseCount >= maxReuse || !container.isHealthy()) {
                recycle(container)
                return
            }
            container.reset()
            container.state = ContainerState.IDLE
            idle.add(container)
        } catch (e: Exception) {
            logger.warn(e) { "release() failed for ${container.id}, recycling" }
            recycle(container)
        }
    }

    /**
     * Availability sanity check: periodically drop dead idle containers and refill the pool back to
     * [poolSize]. This is what lets the pool self-heal after a docker daemon restart or containers
     * dying while idle — without it, borrow() would only discover the loss on demand.
     */
    @Scheduled(fixedRateString = "\${docker.pool.healthCheckRate:30s}")
    @Synchronized
    fun reap() {
        if (!enabled || shuttingDown) return
        val survivors = ArrayList<DockerContainer>()
        while (true) {
            val c = idle.poll() ?: break
            if (c.isHealthy()) {
                survivors.add(c)
            } else {
                logger.warn { "reaper removing dead idle container ${c.id}" }
                c.state = ContainerState.DEAD
                all.remove(c)
                runCatching { c.destroy() }
            }
        }
        survivors.forEach { idle.add(it) }
        fill()
    }

    /** Destroy a spent/broken container and replace it, keeping the pool at [poolSize]. */
    private fun recycle(container: DockerContainer) {
        recycleCounter?.increment()
        container.state = ContainerState.DEAD
        all.remove(container)
        runCatching { container.destroy() }
        if (shuttingDown) return
        runCatching { createContainer() }
            .onSuccess { fresh -> idle.add(fresh); all.add(fresh) }
            .onFailure { e -> logger.error(e) { "Failed to create replacement container" } }
    }

    /**
     * Create ONE long-lived pooled container: `sleep infinity`, network=none, memory limit,
     * tmpfs /workspace, and a dedicated host workdir bound at /submission. The workload runs
     * as the non-root [execUser]; the container is verified non-root before it is returned
     * (fail-closed — a root workload is destroyed and the caller sees the failure).
     */
    private fun createContainer(): DockerContainer {
        val hostWorkDir = workingDir.resolve("pool").resolve(UUID.randomUUID().toString())
        Files.createDirectories(hostWorkDir)
        // The bound /submission dir must be writable by the non-root workload UID so the grading
        // script can copy grade_results.json / logs.txt back out. It is a per-container ephemeral dir.
        runCatching {
            Files.setPosixFilePermissions(hostWorkDir, PosixFilePermissions.fromString("rwxrwxrwx"))
        }.onFailure { logger.warn(it) { "Could not relax permissions on $hostWorkDir" } }

        val id = dockerClient.createContainerCmd(image).use { cmd ->
            cmd.withLabels(mapOf("access-pool" to "true"))
                .withWorkingDir("/workspace")
                .withCmd("sleep", "infinity")
                .withHostConfig(
                    HostConfig()
                        // no network at all (not even loopback to the backend/DB): blocks a workload
                        // from exfiltrating or pulling other students' data over the network.
                        .withNetworkMode("none")
                        // read-only root fs: the ONLY writable surfaces are the tmpfs mounts below and
                        // the bound /submission, all wiped by reset() between borrowers.
                        .withReadonlyRootfs(readOnlyRootfs)
                        // Drop every Linux capability, then add back ONLY the three the root
                        // maintenance execs need: KILL (kill the workload's processes), FOWNER +
                        // DAC_OVERRIDE (chmod / rm host-owned and sticky files). The non-root
                        // workload gets no effective caps regardless — a uid-1000 process holds none.
                        .withCapDrop(Capability.ALL)
                        .withCapAdd(Capability.KILL, Capability.FOWNER, Capability.DAC_OVERRIDE)
                        // Block setuid binaries from granting privileges. Read-only rootfs does NOT
                        // cover this: base images already ship setuid-root binaries (su, mount, ...).
                        .withSecurityOpts(listOf("no-new-privileges:true"))
                        // Fork-bomb guard: cap the number of processes/threads.
                        .withPidsLimit(pidsLimit)
                        // CPU ceiling via CFS quota/period: cap a single submission at cpuLimit cores.
                        .withCpuPeriod(100_000L)
                        .withCpuQuota((cpuLimit * 100_000L).toLong())
                        // mode 1777 so the non-root workload can write; /tmp is scratch space many
                        // images expect to be writable even with a read-only root fs.
                        .withTmpFs(
                            mapOf(
                                "/workspace" to "size=50M,mode=1777",
                                "/tmp" to "size=50M,mode=1777",
                            )
                        )
                        .withMemory(250 * Util.MEGABYTE)
                        .withBinds(Bind.parse("$hostWorkDir:/submission"))
                )
                .exec()
                .id
        }
        dockerClient.startContainerCmd(id).exec()
        val container = DockerContainerInstance(dockerClient, id, hostWorkDir, image, execUser)

        // SECURITY sanity check (fail-closed): never serve a container whose workload is root.
        val uid = try {
            container.effectiveUid()
        } catch (e: Exception) {
            runCatching { container.destroy() }
            throw IllegalStateException("Non-root sanity check failed for container $id: ${e.message}", e)
        }
        if (uid == 0) {
            runCatching { container.destroy() }
            throw IllegalStateException(
                "Refusing pool container $id: workload user '$execUser' resolves to root (uid 0)"
            )
        }

        logger.debug { "Created pooled container $id (workdir=$hostWorkDir, user=$execUser -> uid $uid)" }
        return container
    }

    /** Pull the image once if it isn't present locally (mirrors the one-shot inspect/pull). */
    private fun ensureImage(image: String) {
        try {
            dockerClient.inspectImageCmd(image).exec()
        } catch (e: NotFoundException) {
            logger.info { "Pulling pool image $image ..." }
            dockerClient.pullImageCmd(image).exec(PullImageResultCallback()).awaitCompletion()
        }
    }

    @PreDestroy
    fun shutdown() {
        shuttingDown = true
        drain()
    }
}

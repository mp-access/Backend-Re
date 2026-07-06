package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.Util
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.HostConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
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
 * nothing changes at runtime until the TODOs are filled and the flag is flipped.
 *
 * SKELETON — the container create/destroy internals are TODOs; the borrow/release/
 * recycle orchestration is sketched so the control flow is clear.
 */
@Service
class DockerPoolService(
    private val dockerClient: DockerClient,
    private val workingDir: Path,
    @Value("\${docker.pool.enabled:false}") val enabled: Boolean,
    @Value("\${docker.pool.image:}") private val image: String,
    @Value("\${docker.pool.size:8}") private val poolSize: Int,
    @Value("\${docker.pool.maxReuse:100}") private val maxReuse: Int,
    @Value("\${docker.pool.borrowTimeoutSeconds:30}") private val borrowTimeoutSeconds: Long,
) {
    private val logger = KotlinLogging.logger {}

    /** Ready-to-use containers (backpressure: borrow() blocks on this queue). */
    private val idle = LinkedBlockingDeque<DockerContainer>()

    /** Every container we own (idle + busy) — for shutdown and bookkeeping. */
    private val all = ConcurrentHashMap.newKeySet<DockerContainer>()

    @PostConstruct
    fun warmup() {
        if (!enabled) {
            logger.info { "Docker pool disabled — using one-shot execution path" }
            return
        }
        fill()
    }

    /** Create containers until the pool holds [poolSize] of them. Safe to call repeatedly (tops up). */
    @Synchronized
    fun fill() {
        if (!enabled) return
        if (image.isBlank()) {
            logger.error { "docker.pool.enabled=true but docker.pool.image is empty — pool NOT started" }
            return
        }
        ensureImage(image)
        val missing = (poolSize - all.size).coerceAtLeast(0)
        logger.info { "Filling docker pool: +$missing (target=$poolSize, image=$image)" }
        repeat(missing) {
            runCatching { createContainer() }
                .onSuccess { c -> idle.add(c); all.add(c) }
                .onFailure { e -> logger.error(e) { "Failed to create a pool container" } }
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
        "configuredSize" to poolSize,
        "total" to all.size,
        "idle" to idle.size,
        "busy" to (all.size - idle.size),
        "maxReuse" to maxReuse,
    )

    /**
     * Borrow an idle container, blocking up to [borrowTimeoutSeconds]. Returns null if
     * the pool is disabled or none became free in time — the caller must then fall back
     * to the one-shot path so a submission is never stuck.
     */
    fun borrow(): DockerContainer? {
        if (!enabled) return null
        val c = idle.poll(borrowTimeoutSeconds, TimeUnit.SECONDS) ?: return null
        c.state = ContainerState.BUSY
        return c
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

    /** Destroy a spent/broken container and replace it, keeping the pool at [poolSize]. */
    private fun recycle(container: DockerContainer) {
        container.state = ContainerState.DEAD
        all.remove(container)
        runCatching { container.destroy() }
        runCatching { createContainer() }
            .onSuccess { fresh -> idle.add(fresh); all.add(fresh) }
            .onFailure { e -> logger.error(e) { "Failed to create replacement container" } }
    }

    /**
     * Create ONE long-lived pooled container: `sleep infinity`, network=none, memory limit,
     * tmpfs /workspace, and a dedicated host workdir bound at /submission.
     */
    private fun createContainer(): DockerContainer {
        val hostWorkDir = workingDir.resolve("pool").resolve(UUID.randomUUID().toString())
        Files.createDirectories(hostWorkDir)
        val id = dockerClient.createContainerCmd(image).use { cmd ->
            cmd.withLabels(mapOf("access-pool" to "true"))
                .withWorkingDir("/workspace")
                .withCmd("sleep", "infinity")
                .withHostConfig(
                    HostConfig()
                        .withNetworkMode("none")
                        .withTmpFs(mapOf("/workspace" to "size=50M"))
                        .withMemory(250 * Util.MEGABYTE)
                        .withBinds(Bind.parse("$hostWorkDir:/submission"))
                )
                .exec()
                .id
        }
        dockerClient.startContainerCmd(id).exec()
        logger.debug { "Created pooled container $id (workdir=$hostWorkDir)" }
        return DockerContainerInstance(dockerClient, id, hostWorkDir, image)
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
        drain()
    }
}

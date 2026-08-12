package ch.uzh.ifi.access.execution

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.service.DockerContainer
import ch.uzh.ifi.access.service.DockerPoolService
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Path

/**
 * Integration tests for the pooled-execution sanity checks:
 *  - student workloads run as a NON-ROOT user (never uid 0),
 *  - a root-resolving user is rejected fail-closed (the pool stays empty),
 *  - the pool self-heals when containers die (reaper refill + borrow re-validation).
 *
 * These spin up real containers (like [ExecutionServiceTests]); each test builds its own
 * pool and drains it in a finally block so nothing leaks between tests.
 */
class DockerPoolServiceTests(
    @Autowired val dockerClient: DockerClient,
    @Autowired val workingDir: Path,
) : BaseTest() {

    private val image = "python:latest"

    private fun newPool(
        size: Int,
        user: String = "1000:1000",
        borrowTimeoutSeconds: Long = 5,
        readOnlyRootfs: Boolean = true,
        pidsLimit: Long = 256,
        cpuLimit: Double = 2.0,
    ) = DockerPoolService(
        dockerClient = dockerClient,
        workingDir = workingDir,
        enabled = true,
        image = image,
        poolSize = size,
        maxReuse = 100,
        borrowTimeoutSeconds = borrowTimeoutSeconds,
        execUser = user,
        readOnlyRootfs = readOnlyRootfs,
        pidsLimit = pidsLimit,
        cpuLimit = cpuLimit,
    )

    private fun dockerAvailable(): Boolean = try {
        dockerClient.pingCmd().exec(); true
    } catch (e: Exception) {
        false
    }

    /** Force-remove one of our pool's containers behind the pool's back, simulating a crash. */
    private fun killOnePoolContainerExternally() {
        val id = dockerClient.listContainersCmd()
            .withShowAll(true)
            .withLabelFilter(mapOf("access-pool" to "true"))
            .exec()
            .firstOrNull()?.id ?: error("no pool container found to kill")
        dockerClient.removeContainerCmd(id).withForce(true).exec()
    }

    /** Run a command inside [id] AS THE WORKLOAD USER and capture its stdout+stderr. */
    private fun execAsUser(id: String, cmd: String, user: String = "1000:1000"): String {
        val out = StringBuilder()
        val execId = dockerClient.execCreateCmd(id)
            .withUser(user)
            .withCmd("/bin/sh", "-c", cmd)
            .withAttachStdout(true)
            .withAttachStderr(true)
            .exec()
            .id
        dockerClient.execStartCmd(execId).exec(object : ResultCallback.Adapter<Frame>() {
            override fun onNext(frame: Frame) {
                out.append(String(frame.payload))
            }
        }).awaitCompletion()
        return out.toString()
    }

    @Test
    fun `student workloads run as a non-root user`() {
        assumeTrue(dockerAvailable(), "Docker daemon not available")
        val pool = newPool(size = 1)
        try {
            pool.fill()
            val container: DockerContainer? = pool.borrow()
            assertNotNull(container, "pool should hand out a container")
            val uid = container!!.effectiveUid()
            assertNotEquals(0, uid, "workload must NOT run as root")
            assertEquals(1000, uid, "workload should run as the configured uid 1000")
            pool.release(container)
        } finally {
            pool.drain()
        }
    }

    @Test
    fun `pooled containers are network-isolated with a read-only root filesystem`() {
        assumeTrue(dockerAvailable(), "Docker daemon not available")
        val pool = newPool(size = 1)
        try {
            pool.fill()
            val container = pool.borrow()
            assertNotNull(container)
            val info = dockerClient.inspectContainerCmd(container!!.id).exec()
            // no network → a workload cannot reach the backend/DB to pull other students' data
            assertEquals("none", info.hostConfig.networkMode, "pool containers must have no network")
            // read-only root fs → nothing a student stashes outside /workspace or /submission survives
            assertEquals(true, info.hostConfig.readonlyRootfs, "pool containers must have a read-only root fs")
            pool.release(container)
        } finally {
            pool.drain()
        }
    }

    @Test
    fun `pooled workloads run de-privileged with capped resources`() {
        assumeTrue(dockerAvailable(), "Docker daemon not available")
        val pool = newPool(size = 1)
        try {
            pool.fill()
            val container = pool.borrow()
            assertNotNull(container)
            val id = container!!.id

            // All checks run AS THE WORKLOAD USER (1000:1000) — exactly how student code executes.
            val status = execAsUser(id, "grep -E 'NoNewPrivs|CapEff|CapBnd' /proc/self/status")
            // no-new-privileges reached the exec'd workload -> setuid binaries cannot escalate
            assertTrue(Regex("NoNewPrivs:\\s*1").containsMatchIn(status), "no-new-privileges not set: $status")
            // the student process itself holds zero effective capabilities
            assertTrue(Regex("CapEff:\\s*0{16}").containsMatchIn(status), "workload must hold no effective caps: $status")
            // bounding set reduced to KILL|FOWNER|DAC_OVERRIDE (0x2a) — every other capability dropped
            assertTrue(
                Regex("CapBnd:\\s*0*2a\\b", RegexOption.IGNORE_CASE).containsMatchIn(status),
                "caps not dropped to the minimal maintenance set (expected bounding set 0x2a): $status"
            )

            // pids + cpu limits enforced via the container cgroup (cgroup v2 path first, then v1)
            val pids = execAsUser(
                id,
                "cat /sys/fs/cgroup/pids.max 2>/dev/null || cat /sys/fs/cgroup/pids/pids.max 2>/dev/null"
            ).trim()
            assertEquals("256", pids, "pids limit not enforced")
            val cpuQuota = execAsUser(
                id,
                "cat /sys/fs/cgroup/cpu.max 2>/dev/null || cat /sys/fs/cgroup/cpu/cpu.cfs_quota_us 2>/dev/null"
            ).trim()
            assertTrue(cpuQuota.startsWith("200000"), "cpu quota not enforced (expected 200000 = 2 cores): $cpuQuota")

            pool.release(container)
        } finally {
            pool.drain()
        }
    }

    @Test
    fun `a root-resolving user is rejected fail-closed`() {
        assumeTrue(dockerAvailable(), "Docker daemon not available")
        val pool = newPool(size = 2, user = "0:0", borrowTimeoutSeconds = 2)
        try {
            pool.fill()
            // every container resolves to uid 0, so createContainer() destroys them all
            assertEquals(0, pool.status()["total"], "no root container may enter the pool")
            assertNull(pool.borrow(), "borrow() must not hand out a root container")
        } finally {
            pool.drain()
        }
    }

    @Test
    fun `reaper drops a dead idle container and refills the pool`() {
        assumeTrue(dockerAvailable(), "Docker daemon not available")
        val pool = newPool(size = 2)
        try {
            pool.fill()
            assertEquals(2, pool.status()["total"])

            // one container dies while idle (daemon restart, OOM, manual removal, ...)
            killOnePoolContainerExternally()

            pool.reap()

            assertEquals(2, pool.status()["total"], "reaper should restore the pool to its target size")
            // both containers are now healthy and borrowable
            val a = pool.borrow(); assertNotNull(a)
            val b = pool.borrow(); assertNotNull(b)
            assertTrue(a!!.isHealthy() && b!!.isHealthy())
            pool.release(a); pool.release(b)
        } finally {
            pool.drain()
        }
    }

    @Test
    fun `borrow never hands out a dead container`() {
        assumeTrue(dockerAvailable(), "Docker daemon not available")
        val pool = newPool(size = 1)
        try {
            pool.fill()
            // the only pooled container dies before it is borrowed
            killOnePoolContainerExternally()

            val container = pool.borrow()
            assertNotNull(container, "borrow() should recycle the dead one and return a fresh, healthy container")
            assertTrue(container!!.isHealthy())
            assertEquals(1000, container.effectiveUid())
            pool.release(container)
        } finally {
            pool.drain()
        }
    }
}

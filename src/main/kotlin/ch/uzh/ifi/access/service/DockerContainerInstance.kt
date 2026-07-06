package ch.uzh.ifi.access.service

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.io.FileUtils
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * docker-java backed [DockerContainer]. Wraps exactly one long-lived container that was
 * started with `sleep infinity`; submissions run inside it via `docker exec`.
 */
class DockerContainerInstance(
    private val dockerClient: DockerClient,
    override val id: String,
    override val hostWorkDir: Path,
    val image: String,
) : DockerContainer {

    private val logger = KotlinLogging.logger {}

    override var state: ContainerState = ContainerState.IDLE
    override var reuseCount: Int = 0

    override fun exec(script: String, timeoutSeconds: Long): ExecResult {
        val execId = dockerClient.execCreateCmd(id)
            .withWorkingDir("/workspace")
            .withCmd("/bin/bash", "-c", script)
            .withAttachStdout(true)
            .withAttachStderr(true)
            .exec()
            .id
        // start the exec and wait up to the timeout; frames are discarded (the script
        // already redirects output to logs.txt, just like the one-shot path)
        val callback = dockerClient.execStartCmd(execId).exec(ResultCallback.Adapter<Frame>())
        var timedOut = false
        if (!callback.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS)) {
            // Do NOT kill the container (it stays in the pool) — kill only the workload
            // processes, then wait for the exec to actually finish so we can read its code.
            timedOut = true
            logger.debug { "Exec on $id exceeded ${timeoutSeconds}s, killing workload" }
            killWorkloadProcesses()
            callback.awaitCompletion()
        }
        val exitCode = dockerClient.inspectExecCmd(execId).exec().exitCodeLong?.toInt() ?: 137
        return ExecResult(exitCode, timedOut)
    }

    override fun reset() {
        // 1) kill anything the previous submission left running (never pid 1 = sleep infinity)
        killWorkloadProcesses()
        // 2) wipe the tmpfs workspace and the bound submission dir. Done inside the container
        //    (as its user) so files created by student code — possibly root-owned — are removable.
        runOneOff("rm -rf /workspace/* /workspace/.[!.]* /submission/* /submission/.[!.]* 2>/dev/null || true")
        // 3) best-effort host-side cleanup of the bound dir
        hostWorkDir.toFile().listFiles()?.forEach { FileUtils.deleteQuietly(it) }
    }

    override fun isHealthy(): Boolean = try {
        dockerClient.inspectContainerCmd(id).exec().state?.running == true
    } catch (e: Exception) {
        false
    }

    override fun destroy() {
        try {
            dockerClient.removeContainerCmd(id).withForce(true).exec()
        } catch (e: Exception) {
            logger.debug { "Container $id already gone: ${e.message}" }
        }
        FileUtils.deleteQuietly(hostWorkDir.toFile())
    }

    /** Kill every process in the container except pid 1 (the `sleep infinity`) and the killer shell itself. */
    private fun killWorkloadProcesses() {
        runOneOff(
            """
            for d in /proc/[0-9]*; do
                pid=${'$'}(basename "${'$'}d")
                if [ "${'$'}pid" != "1" ] && [ "${'$'}pid" != "${'$'}${'$'}" ]; then
                    kill -KILL "${'$'}pid" 2>/dev/null || true
                fi
            done
            true
            """.trimIndent()
        )
    }

    /** Run a short maintenance command inside the container and block until it finishes. */
    private fun runOneOff(script: String) {
        try {
            val execId = dockerClient.execCreateCmd(id)
                .withCmd("/bin/bash", "-c", script)
                .exec()
                .id
            dockerClient.execStartCmd(execId).exec(ResultCallback.Adapter<Frame>()).awaitCompletion()
        } catch (e: Exception) {
            logger.warn(e) { "maintenance exec failed on $id" }
        }
    }
}

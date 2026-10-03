package ch.uzh.ifi.access.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Service
class PointDistributionUpdater(
    private val exampleService: ExampleService
) {
    private val logger = KotlinLogging.logger {}
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val active: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    fun ensureRunning(courseSlug: String, exampleSlug: String) {
        val key = Pair(courseSlug, exampleSlug)
        if (!active.add(key)) return // already running for this example
        executor.submit {
            try {
                exampleService.sendPointDistributionUpdates(courseSlug, exampleSlug)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                logger.error(e) { "Point distribution updates failed for example $exampleSlug in course $courseSlug." }
            } finally {
                active.remove(key)
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}

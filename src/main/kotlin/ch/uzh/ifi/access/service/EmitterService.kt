package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.PerishableSseEmitter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service
class EmitterService : DisposableBean, SmartLifecycle {
    private val emitters =
        ConcurrentHashMap<EmitterType, ConcurrentHashMap<String, ConcurrentHashMap<String, PerishableSseEmitter>>>()
    private val logger = KotlinLogging.logger {}
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    /* SSE Emitters are managed as follows:
     - When a client connects, a new PerishableSseEmitter is created
        - The emitter records when it was created
        - The emitter has a unique ID
     - Shortly after being created, the server sends a message to the client containing the unique ID
     - The client will periodically make a heartbeat request which updates the emitters timestamp
     - Scheduled cleanups will destroy any emitters that are too old

     This is done because the server cannot detect if a client disconnected.
    */
    fun registerEmitter(type: EmitterType, slug: String, userId: String): PerishableSseEmitter {
        val id = "${slug}_${userId}_${UUID.randomUUID()}"
        val emitter = PerishableSseEmitter(id, ZonedDateTime.now(), Duration.ofMinutes(60 * 8).toMillis())
        logger.debug { "SSE emitter created ($id)" }
        emitter.onCompletion {
            emitters[type]?.get(slug)?.remove(id)
            logger.debug { "SSE emitter removed ($id)" }
        }
        emitter.onTimeout {
            logger.debug { "SSE connection timed out ($slug)" }
            discard(type, slug, id, emitter)
        }
        emitter.onError { throwable: Throwable? ->
            logger.debug { "SSE exception ($slug): $throwable" }
            discard(type, slug, id, emitter)
        }

        emitters.computeIfAbsent(type) { ConcurrentHashMap() }
            .computeIfAbsent(slug) { ConcurrentHashMap() }[id] = emitter
        scheduler.schedule({
            try {
                emitter.send(SseEmitter.event().name("emitter-id").data(id))
            } catch (e: Exception) {
                discard(type, slug, id, emitter)
            }
        }, 1, TimeUnit.SECONDS)

        return emitter
    }

    fun sendPayload(type: EmitterType, courseSlug: String, name: String, message: Any) {
        var delivered = 0
        var failed = 0
        if (EmitterType.SUPERVISOR == type || EmitterType.EVERYONE == type) {
            emitters[EmitterType.SUPERVISOR]?.get(courseSlug)?.forEach {
                try {
                    it.value.send(SseEmitter.event().name(name).data(message))
                    delivered++
                } catch (e: Exception) {
                    discard(EmitterType.SUPERVISOR, courseSlug, it.key, it.value)
                    failed++
                }
            }
        }

        if (EmitterType.STUDENT == type || EmitterType.EVERYONE == type) {
            emitters[EmitterType.STUDENT]?.get(courseSlug)?.forEach {
                try {
                    it.value.send(SseEmitter.event().name(name).data(message))
                    delivered++
                } catch (e: Exception) {
                    discard(EmitterType.STUDENT, courseSlug, it.key, it.value)
                    failed++
                }
            }
        }
        logger.info { "SSE '$name' in $courseSlug: delivered=$delivered failed=$failed" }
    }

    fun keepAliveEmitter(type: EmitterType, slug: String, emitterId: String) {
        emitters[type]?.get(slug)?.get(emitterId)?.lastHeartbeat = ZonedDateTime.now()
    }

    private fun discard(type: EmitterType, slug: String, id: String, emitter: PerishableSseEmitter) {
        emitters[type]?.get(slug)?.remove(id)
        try {
            emitter.complete()
        } catch (e: Exception) {
            logger.debug { "SSE emitter $id could not be completed cleanly: $e" }
        }
    }

    @Scheduled(fixedRate = 20 * 1000)
    fun pingEmitters() {
        emitters.forEach { (type, slugMap) ->
            slugMap.forEach { (slug, emitterMap) ->
                emitterMap.forEach { (id, emitter) ->
                    try {
                        emitter.send(SseEmitter.event().comment("ping"))
                    } catch (e: Exception) {
                        discard(type, slug, id, emitter)
                    }
                }
            }
        }
    }

    @Scheduled(fixedRate = 30 * 1000)
    fun cleanupEmitters() {
        emitters.forEach { (type, slugMap) ->
            slugMap.forEach { (slug, emitterMap) ->
                emitterMap.forEach { (id, emitter) ->
                    val age = Duration.between(emitter.lastHeartbeat, ZonedDateTime.now()).abs().seconds
                    if (age > 60) {
                        logger.debug { "Emitter ${emitter.id} died of old age $age" }
                        try {
                            emitter.complete()
                        } catch (_: IllegalStateException) {

                        }
                        emitters[type]?.get(slug)?.remove(id)
                    }
                }
            }
        }
    }

    /** Close all SSE connections and stop scheduler on shutdown */
    override fun destroy() = closeAllEmitters()

    private val running = AtomicBoolean(false)
    override fun isRunning(): Boolean = running.get()
    override fun start() = running.set(true)
    override fun getPhase(): Int = Integer.MAX_VALUE
    override fun stop() = closeAllEmitters()
    override fun stop(callback: Runnable) {
        try {
            closeAllEmitters()
        } finally {
            callback.run()
        }
    }

    private fun closeAllEmitters() {
        logger.info { "Closing all SSE emitters on shutdown..." }
        emitters.forEach { (_, slugMap) ->
            slugMap.forEach { (_, emitterMap) ->
                emitterMap.values.forEach { e -> runCatching { e.complete() } }
                emitterMap.clear()
            }
            slugMap.clear()
        }
        emitters.clear()
        scheduler.shutdownNow()
    }
}

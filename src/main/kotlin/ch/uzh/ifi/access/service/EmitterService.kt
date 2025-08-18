package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.PerishableSseEmitter
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
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

@Service
class EmitterService {
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
            emitter.complete()
        }
        emitter.onError { throwable: Throwable? ->
            logger.debug { "SSE exception ($slug): $throwable" }
            emitter.complete()
        }

        emitters.computeIfAbsent(type) { ConcurrentHashMap() }
            .computeIfAbsent(slug) { ConcurrentHashMap() }[id] = emitter
        scheduler.schedule({
            try {
                emitter.send(SseEmitter.event().name("emitter-id").data(id))
            } catch (e: Exception) {
                emitter.completeWithError(e)
            }
        }, 1, TimeUnit.SECONDS)

        return emitter
    }

    fun sendPayload(type: EmitterType, courseSlug: String, name: String, message: Any) {
        if (EmitterType.SUPERVISOR == type || EmitterType.EVERYONE == type) {
            emitters[EmitterType.SUPERVISOR]?.get(courseSlug)?.forEach {
                try {
                    it.value.send(SseEmitter.event().name(name).data(message))
                } catch (e: Exception) {
                    emitters[EmitterType.SUPERVISOR]?.get(courseSlug)?.remove(it.key)
                }
            }
        }

        if (EmitterType.STUDENT == type || EmitterType.EVERYONE == type) {
            emitters[EmitterType.STUDENT]?.get(courseSlug)?.forEach {
                try {
                    it.value.send(SseEmitter.event().name(name).data(message))
                } catch (e: Exception) {
                    emitters[EmitterType.STUDENT]?.get(courseSlug)?.remove(it.key)
                }
            }
        }
    }


    fun keepAliveEmitter(type: EmitterType, slug: String, emitterId: String) {
        emitters[type]?.get(slug)?.get(emitterId)?.lastHeartbeat = ZonedDateTime.now()
    }

    @Scheduled(fixedRate = 30 * 1000)
    fun cleanupEmitters() {
        emitters.forEach { (type, slugMap) ->
            slugMap.forEach { (slug, emitterMap) ->
                emitterMap.forEach { (id, emitter) ->
                    val age = Duration.between(emitter.lastHeartbeat, ZonedDateTime.now()).abs().seconds
                    if (age > 15) {
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
    @PreDestroy
    fun preDestroy() = closeAllEmitters()

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

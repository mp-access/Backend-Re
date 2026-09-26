package ch.uzh.ifi.access.sse

import ch.uzh.ifi.access.model.PerishableSseEmitter
import ch.uzh.ifi.access.service.EmitterService
import ch.uzh.ifi.access.service.EmitterType
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class EmitterServiceTests {

    private val service = EmitterService()

    @Suppress("UNCHECKED_CAST")
    private fun group(type: EmitterType, slug: String): ConcurrentHashMap<String, PerishableSseEmitter> {
        val field = EmitterService::class.java.getDeclaredField("emitters")
        field.isAccessible = true
        val emitters = field.get(service)
        as ConcurrentHashMap<EmitterType, ConcurrentHashMap<String, ConcurrentHashMap<String, PerishableSseEmitter>>>
        return emitters.computeIfAbsent(type) { ConcurrentHashMap() }.computeIfAbsent(slug) { ConcurrentHashMap() }
    }

    private fun healthy(id: String): PerishableSseEmitter {
        val emitter = mockk<PerishableSseEmitter>()
        every { emitter.id } returns id
        every { emitter.send(any<SseEmitter.SseEventBuilder>()) } just Runs
        return emitter
    }

    private fun dead(id: String): PerishableSseEmitter {
        val emitter = mockk<PerishableSseEmitter>()
        every { emitter.id } returns id
        every { emitter.send(any<SseEmitter.SseEventBuilder>()) } throws IOException("Broken pipe")
        every { emitter.complete() } throws IllegalStateException("AsyncContext already in error state")
        return emitter
    }

    @Test
    fun `A dead emitter does not interrupt the broadcast and is removed`() {
        val students = group(EmitterType.STUDENT, "course")
        val first = healthy("first")
        val broken = dead("broken")
        val last = healthy("last")
        students["a-first"] = first
        students["b-broken"] = broken
        students["c-last"] = last

        service.sendPayload(EmitterType.STUDENT, "course", "timer-update", "start/end")

        verify(exactly = 1) { first.send(any<SseEmitter.SseEventBuilder>()) }
        verify(exactly = 1) { last.send(any<SseEmitter.SseEventBuilder>()) }
        verify(exactly = 1) { broken.complete() }
        assertEquals(setOf("a-first", "c-last"), students.keys)
    }

    @Test
    fun `EVERYONE reaches supervisors and students even when a supervisor emitter is dead`() {
        val supervisors = group(EmitterType.SUPERVISOR, "course")
        val students = group(EmitterType.STUDENT, "course")
        val lecturer = healthy("lecturer")
        val staleTab = dead("stale-tab")
        val student = healthy("student")
        supervisors["lecturer"] = lecturer
        supervisors["stale-tab"] = staleTab
        students["student"] = student

        service.sendPayload(EmitterType.EVERYONE, "course", "message", "hello")

        verify(exactly = 1) { lecturer.send(any<SseEmitter.SseEventBuilder>()) }
        verify(exactly = 1) { student.send(any<SseEmitter.SseEventBuilder>()) }
        assertEquals(setOf("lecturer"), supervisors.keys)
        assertEquals(setOf("student"), students.keys)
    }

    @Test
    fun `Ping removes emitters whose connection is gone`() {
        val students = group(EmitterType.STUDENT, "course")
        val ok = healthy("ok")
        val gone = dead("gone")
        students["ok"] = ok
        students["gone"] = gone

        service.pingEmitters()

        verify(exactly = 1) { ok.send(any<SseEmitter.SseEventBuilder>()) }
        assertEquals(setOf("ok"), students.keys)
    }
}

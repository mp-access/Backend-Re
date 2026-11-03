package ch.uzh.ifi.access.service

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Current system CPU usage in [0, 1], or null if no reading is available yet.
 * Abstracted so the queue dispatch rules can be tested without a real CPU gauge.
 */
fun interface CpuUsageProvider {
    fun currentCpuUsage(): Double?
}

@Component
class MicrometerCpuUsageProvider(private val meterRegistry: MeterRegistry) : CpuUsageProvider {
    override fun currentCpuUsage(): Double? = meterRegistry.find("system.cpu.usage").gauge()?.value()
}

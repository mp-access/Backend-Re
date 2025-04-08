package ch.uzh.ifi.access.model

import lombok.Data
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.ZonedDateTime

@Data
class PerishableSseEmitter(
    val id: String,
    var lastHeartbeat: ZonedDateTime,
    val timeout: Long
) : SseEmitter(timeout)

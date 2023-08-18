package ch.uzh.ifi.access.model.dto

import lombok.Data
import java.time.LocalDateTime

@Data
class CourseDTO (
    var slug: String? = null,
    var repository: String? = null,
    var logo: String? = null,
    var information: MutableMap<String, CourseInformationDTO> = HashMap(),
    var defaultVisibility: String? = null,
    var overrideVisibility: String? = null,
    var overrideStart: LocalDateTime? = null,
    var overrideEnd: LocalDateTime? = null,
    var studentRole: String? = null,
    var assistantRole: String? = null,
    var supervisorRole: String? = null,
    var supervisors: MutableList<MemberDTO> = ArrayList(),
    var assistants: MutableList<MemberDTO> = ArrayList(),
    var assignments: MutableList<String> = ArrayList()
)

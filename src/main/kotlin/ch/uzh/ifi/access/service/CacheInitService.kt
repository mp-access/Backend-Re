package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.repository.CourseRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class CacheInitService(
    private val courseRepository: CourseRepository,
    private val userIdUpdateService: UserIdUpdateService,
    private val roleService: RoleService,
) {

    private val logger = KotlinLogging.logger {}

    @Transactional
    fun initCache() {
        courseRepository.findAllByDeletedFalse().forEach { course ->
            course.registeredStudents.map { student ->
                roleService.findUserByAllCriteria(student)?.let {
                    roleService.getRegistrationIDCandidates(it.username)
                    roleService.getUserId(it.username)
                }
            }
        }
    }

    fun renameIDs() {
        courseRepository.findAll().forEach {
            logger.info { "Course ${it?.slug}: changing userIds for evaluations and submissions..." }
            var evaluationCount = 0
            var submissionCount = 0
            it?.registeredStudents?.map { registrationId ->
                roleService.findUserByAllCriteria(registrationId)?.let { user ->
                    //logger.info { "Changing userIds for evaluations and submissions of user ${user.username}" }
                    val names = roleService.getRegistrationIDCandidates(user.username).toMutableList()
                    val userId = roleService.getUserId(user.username)
                    if (userId != null) {
                        names.remove(userId)
                        val res = userIdUpdateService.updateID(names, userId)
                        evaluationCount += res.first
                        submissionCount += res.second
                    }
                }
            }
            logger.info { "Course ${it?.slug}: changed the userId for $evaluationCount evaluations and $submissionCount submissions" }
        }
    }
}

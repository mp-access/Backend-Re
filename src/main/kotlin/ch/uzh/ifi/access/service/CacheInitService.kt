package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.repository.CourseRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class CacheInitService(
    private val courseRepository: CourseRepository,
    private val userIdentityService: UserIdentityService,
) {

    @Transactional
    fun initCache() {
        courseRepository.findAll().filterNotNull().forEach { course ->
            course.registeredStudents.forEach { student ->
                userIdentityService.findUserByAllCriteria(student)?.let {
                    userIdentityService.getRegistrationIDCandidates(it.username)
                    userIdentityService.resolveUserId(it.username)
                }
            }
        }
    }
}

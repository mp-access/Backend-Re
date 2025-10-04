package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.projections.CourseOverview
import ch.uzh.ifi.access.projections.CourseSummary
import ch.uzh.ifi.access.projections.CourseWorkspace
import ch.uzh.ifi.access.projections.MemberOverview
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.rest.core.config.Projection
import org.springframework.security.access.prepost.PostFilter

class UserPoints {
    var userId: String? = null
    var totalPoints: Double? = null
}

@Projection(types = [UserPoints::class])
interface UserPointsProjection {
    val userId: String?
    val totalPoints: Double?
}

interface CourseRepository : JpaRepository<Course?, Long?> {
    fun getBySlug(courseSlug: String?): Course?
    fun findBySlug(courseSlug: String?): CourseWorkspace?

    @PostFilter("hasRole(filterObject.slug)")
    fun findCoursesBy(): List<CourseOverview>

    // TODO: remove for public courses and handle in controller
    @PostFilter("hasRole(filterObject.slug)")
    fun findCoursesByAndDeletedFalse(): List<CourseOverview>

    @Query("""
    SELECT DISTINCT c 
    FROM Course c 
    WHERE c.deleted = false 
    AND (
        EXISTS (SELECT 1 FROM c.registeredStudents rs WHERE rs IN :userIds)
        OR EXISTS (SELECT 1 FROM c.assistants a WHERE a IN :userIds)
        OR EXISTS (SELECT 1 FROM c.supervisors s WHERE s IN :userIds)
    )
    """)
    fun findCoursesForUser(@Param("userIds") userIds: List<String>): Set<CourseOverview>

    fun findAllByDeletedFalse(): List<Course>

    @Query(
        nativeQuery = true, value = "SELECT a.value AS name, :email AS email FROM user_attribute a " +
        "WHERE a.name='displayName' AND a.user_id=(SELECT e.id FROM user_entity e WHERE e.email=:email)"
    )
    fun getTeamMemberName(email: String?): MemberOverview?
    fun findCourseBySlug(courseSlug: String?): CourseSummary?

    @Query(
        nativeQuery = true, value = """
            SELECT sum(e.best_score) AS total_points
            FROM evaluation e
            JOIN task t ON e.task_id = t.id
            JOIN assignment a ON t.assignment_id = a.id
            JOIN course c ON a.course_id = c.id
            WHERE e.id IN (
                SELECT MAX(id)
                FROM evaluation
                WHERE user_id = :userId
                GROUP BY task_id
            )
            AND c.slug = :courseSlug
            AND e.user_id = :userId
        """
    )
    fun getTotalPoints(courseSlug: String, userId: String): Double?

    @Query(
        nativeQuery = true, value = """
            SELECT e.user_id AS userId, SUM(e.best_score) AS totalPoints
            FROM evaluation e
            JOIN (
                SELECT user_id, task_id, MAX(id) AS max_id
                FROM evaluation
                WHERE user_id = ANY(:userIds)
                GROUP BY user_id, task_id
            ) latest_eval ON e.id = latest_eval.max_id
            JOIN task t ON e.task_id = t.id
            JOIN assignment a ON t.assignment_id = a.id
            JOIN course c ON a.course_id = c.id
            WHERE c.slug = :courseSlug
            GROUP BY e.user_id
        """
    )
    fun getParticipantsWithPoints(courseSlug: String, userIds: Array<String>): List<UserPointsProjection>

    // Bypasses role restrictions, use only if preventing leaks by other means.
    // Necessary for retrieving user roles upon first login.
    fun findAllUnrestrictedByDeletedFalse(): List<Course>

}

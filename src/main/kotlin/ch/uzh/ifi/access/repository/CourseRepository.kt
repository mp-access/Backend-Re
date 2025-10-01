package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.projections.CourseOverview
import ch.uzh.ifi.access.projections.CourseSummary
import ch.uzh.ifi.access.projections.CourseWorkspace
import ch.uzh.ifi.access.projections.MemberOverview
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.rest.core.config.Projection
import org.springframework.security.access.prepost.PostFilter
import org.springframework.data.repository.query.Param

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

    @Query(
        value = """
       SELECT DISTINCT c.*
        FROM course c
        JOIN keycloak_role r ON r.name LIKE CONCAT(c.slug, '-%')
        JOIN user_role_mapping urm ON urm.role_id = r.id
        JOIN user_entity u ON u.id = urm.user_id
        WHERE c.deleted = false
        AND u.email = :userId;
    """,
        nativeQuery = true
    )
    fun findCoursesForUser(@Param("userId") userId: String): List<Course>


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

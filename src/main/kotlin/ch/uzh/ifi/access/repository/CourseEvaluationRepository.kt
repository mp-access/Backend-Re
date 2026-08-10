package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.CourseEvaluation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CourseEvaluationRepository : JpaRepository<CourseEvaluation, Long> {

    // Same two-step write as AssignmentEvaluationRepository. Lock ORDER
    // rule: every writer takes the assignment row FIRST and the course row
    // SECOND — bulk recompute included — so that two writers can never
    // deadlock on the same pair of rows.
    @Modifying
    @Query(
        value = """
            INSERT INTO course_evaluation (id, user_id, course_id, points, version)
            VALUES (nextval('course_evaluation_seq'), :userId, :courseId, 0, 0)
            ON CONFLICT (user_id, course_id)
            DO UPDATE SET version = course_evaluation.version + 1
        """,
        nativeQuery = true,
    )
    fun upsertAndLock(@Param("userId") userId: String, @Param("courseId") courseId: Long): Int

    // The course total sums the (already recomputed, already locked)
    // assignment aggregates of the course: inside the transaction we read
    // our own fresh assignment row, so the two levels cannot disagree.
    @Modifying
    @Query(
        value = """
            UPDATE course_evaluation ce
            SET points = (
                SELECT COALESCE(SUM(ae.points), 0)
                FROM assignment_evaluation ae
                JOIN assignment a ON ae.assignment_id = a.id
                WHERE ae.user_id = ce.user_id
                  AND a.course_id = ce.course_id
            )
            WHERE ce.user_id = :userId AND ce.course_id = :courseId
        """,
        nativeQuery = true,
    )
    fun recomputePoints(@Param("userId") userId: String, @Param("courseId") courseId: Long): Int

    // Course-scoped backfill for the bulk refresh: every user who has
    // assignment aggregates in this course gets a course row if missing.
    @Modifying
    @Query(
        value = """
            INSERT INTO course_evaluation (id, user_id, course_id, points, version)
            SELECT nextval('course_evaluation_seq'), f.user_id, :courseId, 0, 0
            FROM (
                SELECT DISTINCT ae.user_id
                FROM assignment_evaluation ae
                JOIN assignment a ON ae.assignment_id = a.id
                WHERE a.course_id = :courseId
            ) f
            ON CONFLICT (user_id, course_id) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun backfillMissingForCourse(@Param("courseId") courseId: Long): Int

    // Bulk variant of the recompute, one statement for a whole course.
    @Modifying
    @Query(
        value = """
            UPDATE course_evaluation ce
            SET points = (
                SELECT COALESCE(SUM(ae.points), 0)
                FROM assignment_evaluation ae
                JOIN assignment a ON ae.assignment_id = a.id
                WHERE ae.user_id = ce.user_id AND a.course_id = ce.course_id
            ),
            version = version + 1
            WHERE ce.course_id = :courseId
        """,
        nativeQuery = true,
    )
    fun recomputeAllForCourse(@Param("courseId") courseId: Long): Int

    // Read side: the pre-summed course total of one student, resolved by
    // course slug. Null when the row does not exist yet (sparse rows):
    // callers read that as 0.
    @Query("SELECT ce.points FROM CourseEvaluation ce WHERE ce.userId = :userId AND ce.course.slug = :courseSlug")
    fun findPointsByCourseSlug(@Param("userId") userId: String, @Param("courseSlug") courseSlug: String): Double?

    // Read side for the staff pages: every requested student's pre-summed
    // course total in one indexed read — the aggregate replacement for the
    // big SUM over evaluations (CourseRepository.getParticipantsWithPoints).
    // Same projection type, so callers cannot tell the difference.
    @Query("SELECT ce.userId AS userId, ce.points AS totalPoints FROM CourseEvaluation ce WHERE ce.course.slug = :courseSlug AND ce.userId IN :userIds")
    fun findPointsByCourse(@Param("courseSlug") courseSlug: String, @Param("userIds") userIds: List<String>): List<UserPointsProjection>
}

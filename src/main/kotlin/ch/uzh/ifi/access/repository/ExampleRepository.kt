package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.projections.TaskWorkspace
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ExampleRepository : JpaRepository<Task?, Long?> {

    fun findByCourse_SlugOrderByOrdinalNumDesc(
        courseSlug: String?
    ): List<TaskWorkspace>

    fun findByCourse_SlugAndSlug(
        courseSlug: String?,
        exampleSlug: String?
    ): TaskWorkspace?

    fun getByCourse_SlugAndSlug(
        courseSlug: String?,
        exampleSlug: String?
    ): Task?

    @Query(
        """
    SELECT submission.user_id, COUNT(*) AS entry_count FROM task
    JOIN course ON course.id = task.course_id AND course.slug = :courseSlug
    JOIN evaluation ON evaluation.task_id = task.id
    JOIN submission ON submission.evaluation_id = evaluation.id AND submission.created_at >= task.start_date AND submission.created_at <= task.end_date
    GROUP BY submission.user_id
    """,
        nativeQuery = true
    )
    fun getSubmissionsCount(
        courseSlug: String?,
    ): List<Map<String, Any>>
}

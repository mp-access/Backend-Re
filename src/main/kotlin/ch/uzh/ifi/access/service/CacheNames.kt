package ch.uzh.ifi.access.service

/**
 * Names of all caches apart from the user and role caches ([RoleCaches]). Referenced by caching annotations,
 * manual evictions and [ch.uzh.ifi.access.config.CacheConfig], so they must be compile-time constants.
 * The values keep their historical "Class.method" form; they are only identifiers.
 */
object CacheNames {
    // permanent: never evicted periodically
    const val TASK_ID = "TaskLookupService.resolveTaskId"

    // temporary: cleared periodically (caching.eviction-rate)
    const val TASK_POINTS = "PointsService.calculateTaskPoints"
    const val AVG_TASK_POINTS = "PointsService.calculateAvgTaskPoints"
    const val ASSIGNMENT_MAX_POINTS = "PointsService.calculateAssignmentMaxPoints"
    const val COURSE_MAX_POINTS = "PointsService.getMaxPoints"
    const val COURSE_POINTS = "CourseService.calculateCoursePoints"
    const val COURSES_OVERVIEW = "CourseService.getCoursesOverview"
    const val STUDENTS = "CourseService.getStudents"
    const val STUDENT_HAS_VISIBLE_EXAMPLES = "ExampleService.studentHasVisibleExamples"
    const val SUPERVISOR_HAS_VISIBLE_EXAMPLES = "ExampleService.supervisorHasVisibleExamples"
    const val EXAMPLE_SUBMISSIONS_COUNT = "ExampleService.computeSubmissionsCount"

    // evicted individually on a short schedule (CacheEvictScheduler)
    const val INTERACTIVE_EXAMPLE = "ExampleService.getInteractiveExampleSlug"
    const val RECENTLY_ACTIVE_COUNT = "VisitQueueService.getRecentlyActiveCount"
}

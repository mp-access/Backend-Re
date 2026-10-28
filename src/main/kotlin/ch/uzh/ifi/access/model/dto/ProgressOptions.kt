package ch.uzh.ifi.access.model.dto

data class ProgressOptions(
    val submissionLimit: Int = 1,
    val includeGrade: Boolean = true,
    val includeTest: Boolean = false,
    val includeRun: Boolean = false,
) {
    /**
     * Special rule: if only graded submissions with a limit of 1 are requested, the BEST graded
     * submission is returned. Otherwise the <submissionLimit> most recent ones (newest first, 0 = all).
     */
    val onlyBestGraded: Boolean
        get() = submissionLimit == 1 && includeGrade && !includeTest && !includeRun
}

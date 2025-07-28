package ch.uzh.ifi.access.projections

interface SubmissionEmbedding {
    fun getId(): Long
    fun getEmbedding(): DoubleArray
}

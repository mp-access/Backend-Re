package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.dto.CategorizationDTO
import org.apache.commons.math3.ml.distance.EuclideanDistance
import org.springframework.stereotype.Service
import smile.clustering.SpectralClustering
import kotlin.math.roundToInt
import kotlin.random.Random

@Service
class ClusteringService {

    fun performSpectralClustering(
        embeddingsMap: Map<Long, DoubleArray>,
        numClusters: Int
    ): CategorizationDTO {
        val orderedEmbeddingsMap = embeddingsMap.entries.sortedBy { it.key }
        val orderedSubmissionIds = orderedEmbeddingsMap.map { it.key }
        val orderedEmbeddings = orderedEmbeddingsMap.map { it.value }.toTypedArray()

        val sigma = calculateAppropriateSigma(orderedEmbeddings)
        val clustering = SpectralClustering.fit(orderedEmbeddings, numClusters, sigma)
        val labels = clustering.y

        val categorizationDTO = CategorizationDTO()
        labels.forEachIndexed { index, label ->
            val clusterName = "cat${label + 1}"
            categorizationDTO.categories.getOrPut(clusterName) { mutableListOf() }.add(orderedSubmissionIds[index])
        }
        return categorizationDTO
    }

    // the default approach for calculating an appropriate sigma-value for spectral clustering is computing the median/average distance between all data points
    // if we have many embeddings, this becomes costly. Because of that, we only use 10% of the data points (if 10% exceeds 20 data points).
    private fun calculateAppropriateSigma(orderedEmbeddings: Array<DoubleArray>): Double {
        val numDataPoints = orderedEmbeddings.size
        val pointsToConsider: Array<DoubleArray>

        if (numDataPoints < 20) { // Case 1: If the number of data points is smaller than 20, use all data points.
            pointsToConsider = orderedEmbeddings
        } else {
            // Case 2: Use 10% of all data points.
            var numSamplePoints = (numDataPoints * 0.10).roundToInt()

            // Case 3: If 10% of all data points is smaller than 20, use 20.
            if (numSamplePoints < 20) {
                numSamplePoints = 20
            }

            val random = Random(123456789)
            val shuffledIndices = orderedEmbeddings.indices.toList().shuffled(random)
            val sampleIndices = shuffledIndices.take(numSamplePoints)
            pointsToConsider = Array(numSamplePoints) { i ->
                orderedEmbeddings[sampleIndices[i]]
            }
        }

        val distances = mutableListOf<Double>()
        val euclideanDistance = EuclideanDistance()
        for (i in pointsToConsider.indices) {
            for (j in i + 1 until pointsToConsider.size) {
                distances.add(euclideanDistance.compute(pointsToConsider[i], pointsToConsider[j]))
            }
        }
        return distances.median()
    }

    private fun List<Double>.median(): Double {
        val sortedList = this.sorted()
        val mid = sortedList.size / 2

        return if (sortedList.size % 2 == 0) {
            (sortedList[mid - 1] + sortedList[mid]) / 2.0
        } else {
            sortedList[mid]
        }
    }
}

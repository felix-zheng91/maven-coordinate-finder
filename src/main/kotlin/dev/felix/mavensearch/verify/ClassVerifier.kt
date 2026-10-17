package dev.felix.mavensearch.verify

import dev.felix.mavensearch.model.ArtifactCoordinates

/** 独立确认某个坐标的 jar 中是否存在指定类。 */
fun interface ClassVerifier {
    suspend fun contains(coordinates: ArtifactCoordinates, version: String, fqcn: String): Boolean
}

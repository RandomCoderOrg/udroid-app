package org.randomcoder.udroid.oci

import org.randomcoder.udroid.install.ArtifactRequest
import org.randomcoder.udroid.install.ByteProgress
import org.randomcoder.udroid.install.ResumableArtifactPipeline
import java.io.File

data class OciPullResult(
    val image: OciResolvedImage,
    val configFile: File,
    val layers: List<VerifiedOciLayer>,
)

class OciImagePuller(
    private val registryClient: OciRegistryClient = OciRegistryClient(),
    private val artifactPipeline: ResumableArtifactPipeline = ResumableArtifactPipeline(),
) {
    fun pull(
        reference: OciImageReference,
        platform: OciPlatform,
        cacheDirectory: File,
        onBlobDownloadProgress: (
            descriptor: OciBlobDescriptor,
            progress: ByteProgress,
        ) -> Unit = { _, _ -> },
        onBlobVerifyProgress: (
            descriptor: OciBlobDescriptor,
            progress: ByteProgress,
        ) -> Unit = { _, _ -> },
    ): OciPullResult {
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            "Could not create OCI blob cache"
        }
        val image = registryClient.resolve(reference, platform)
        val descriptors = listOf(image.config) + image.layers
        val totalBytes = descriptors.sumOf { it.size.coerceAtLeast(0L) }
        var completedBeforeBlob = 0L
        val config =
            download(
                image,
                image.config,
                cacheDirectory,
                ".config.json",
                completedBeforeBlob,
                totalBytes,
                onBlobDownloadProgress,
                onBlobVerifyProgress,
            )
        completedBeforeBlob += image.config.size.coerceAtLeast(0L)
        val layers =
            image.layers.map { descriptor ->
                val file =
                    download(
                        image = image,
                        descriptor = descriptor,
                        cacheDirectory = cacheDirectory,
                        suffix = OciLayerMedia.fileSuffix(descriptor.mediaType),
                        completedBeforeBlob = completedBeforeBlob,
                        totalBytes = totalBytes,
                        onBlobDownloadProgress = onBlobDownloadProgress,
                        onBlobVerifyProgress = onBlobVerifyProgress,
                    )
                completedBeforeBlob += descriptor.size.coerceAtLeast(0L)
                VerifiedOciLayer(descriptor, file)
            }
        return OciPullResult(image, config, layers)
    }

    private fun download(
        image: OciResolvedImage,
        descriptor: OciBlobDescriptor,
        cacheDirectory: File,
        suffix: String,
        completedBeforeBlob: Long,
        totalBytes: Long,
        onBlobDownloadProgress: (OciBlobDescriptor, ByteProgress) -> Unit,
        onBlobVerifyProgress: (OciBlobDescriptor, ByteProgress) -> Unit,
    ): File {
        val source = image.blobDownload(descriptor)
        val digestHex = descriptor.digest.removePrefix("sha256:")
        val finalFile = File(cacheDirectory, "$digestHex$suffix")
        val stagingFile = File(cacheDirectory, "$digestHex$suffix.part")
        return artifactPipeline.execute(
            request =
                ArtifactRequest(
                    url = source.url,
                    expectedSha256 = digestHex,
                    stagingFile = stagingFile,
                    finalFile = finalFile,
                    requestHeaders = source.requestHeaders,
                ),
            onDownloadProgress = {
                onBlobDownloadProgress(
                    descriptor,
                    it.toAggregateOciProgress(
                        completedBeforeBlob,
                        totalBytes,
                        descriptor.size,
                    ),
                )
            },
            onVerifyProgress = {
                onBlobVerifyProgress(
                    descriptor,
                    it.toAggregateOciProgress(
                        completedBeforeBlob,
                        totalBytes,
                        descriptor.size,
                    ),
                )
            },
        ).file
    }
}

internal fun ByteProgress.toAggregateOciProgress(
    completedBeforeBlob: Long,
    aggregateTotalBytes: Long,
    descriptorSize: Long,
): ByteProgress =
    copy(
        completedBytes =
            completedBeforeBlob +
                completedBytes.coerceIn(0L, descriptorSize.coerceAtLeast(0L)),
        totalBytes = aggregateTotalBytes,
    )

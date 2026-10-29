@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.features.autosync

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusReading
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVKeyValueStatusLoaded
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVURLAsset
import platform.CoreMedia.CMBlockBufferGetDataPointer
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeRangeMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.cinterop.CValue
import kotlinx.cinterop.usePinned
import platform.Foundation.writeToFile
import kotlin.coroutines.resume
import kotlin.math.sqrt

private const val PROBE_SAMPLE_RATE = 16000.0
private const val BYTES_PER_SAMPLE = 2
// Kotlin/Native does not expose these plain extern NSString* constants from
// AVFoundation, so use their literal values (each constant's value is its own name).
private const val AV_FORMAT_ID_KEY = "AVFormatIDKey"
private const val AV_SAMPLE_RATE_KEY = "AVSampleRateKey"
private const val AV_NUMBER_OF_CHANNELS_KEY = "AVNumberOfChannelsKey"
private const val AV_LINEAR_PCM_BIT_DEPTH_KEY = "AVLinearPCMBitDepthKey"
private const val AV_LINEAR_PCM_IS_FLOAT_KEY = "AVLinearPCMIsFloatKey"
private const val AV_LINEAR_PCM_IS_NON_INTERLEAVED_KEY = "AVLinearPCMIsNonInterleavedKey"
private const val AV_URL_ASSET_HTTP_HEADER_FIELDS_KEY = "AVURLAssetHTTPHeaderFieldsKey"
// 'lpcm' as a four-char code.
private const val LPCM_FORMAT_ID = 1819304813u

private val probeHttpClient by lazy { HttpClient(Darwin) }

/**
 * iOS audio probe. Two paths, one result:
 *
 * - HLS playlist: download only the segments covering the window into a temp
 *   directory (preferring a separate audio rendition when the master offers one,
 *   so a 60s probe is a few hundred KB instead of tens of MB), decode each with
 *   AVAssetReader, then delete the temp directory.
 * - Direct file (.mp4 and friends): AVAssetReader straight on the remote URL with
 *   a time range, so the OS fetches only that window via range requests.
 *
 * Everything is decoded to 16 kHz mono s16, which makes the envelope bins trivial
 * to compute. Encrypted HLS (EXT-X-KEY other than METHOD=NONE) is skipped: the
 * downloaded segments would be undecodable here.
 */
internal actual suspend fun readProbeAudioEnvelope(
    sourceUrl: String,
    headers: Map<String, String>,
    windowStartMs: Long,
    windowMs: Long,
): ProbeAudioEnvelope? {
    val binCount = (windowMs / ProbeAutoSyncAligner.BIN_MS).toInt()
    if (binCount <= 0) return null
    val sumSq = DoubleArray(binCount)
    val counts = IntArray(binCount)
    val ok = try {
        if (sourceUrl.substringBefore("?").lowercase().contains(".m3u8")) {
            readHlsIntoBins(sourceUrl, headers, windowStartMs, windowMs, binCount, sumSq, counts)
        } else {
            readRemoteFileIntoBins(sourceUrl, headers, windowStartMs, windowMs, binCount, sumSq, counts)
        }
    } catch (_: Exception) {
        false
    }
    if (!ok || counts.sum() == 0) return null
    val rms = List(binCount) { index ->
        if (counts[index] > 0) sqrt(sumSq[index] / counts[index]) else 0.0
    }
    return ProbeAudioEnvelope(windowStartMs, ProbeAutoSyncAligner.BIN_MS, rms)
}

private data class HlsSegment(
    val url: String,
    val durationMs: Long,
    val startMs: Long,
)

private suspend fun readRemoteFileIntoBins(
    sourceUrl: String,
    headers: Map<String, String>,
    windowStartMs: Long,
    windowMs: Long,
    binCount: Int,
    sumSq: DoubleArray,
    counts: IntArray,
): Boolean {
    val nsUrl = NSURL(string = sourceUrl) ?: return false
    val options: Map<Any?, Any?> = mapOf(AV_URL_ASSET_HTTP_HEADER_FIELDS_KEY to headers)
    val asset = AVURLAsset(uRL = nsUrl, options = options)
    val timeRange = CMTimeRangeMake(
        CMTimeMakeWithSeconds(windowStartMs / 1000.0, 1000),
        CMTimeMakeWithSeconds(windowMs / 1000.0, 1000),
    )
    return readAssetBins(
        asset = asset,
        timeRange = timeRange,
        absolutePts = true,
        baseMs = 0L,
        windowStartMs = windowStartMs,
        binCount = binCount,
        sumSq = sumSq,
        counts = counts,
    )
}

private suspend fun readHlsIntoBins(
    sourceUrl: String,
    headers: Map<String, String>,
    windowStartMs: Long,
    windowMs: Long,
    binCount: Int,
    sumSq: DoubleArray,
    counts: IntArray,
): Boolean {
    val masterText = fetchText(sourceUrl, headers) ?: return false
    val mediaUrl = if (masterText.contains("#EXT-X-STREAM-INF")) {
        selectMediaPlaylist(masterText, sourceUrl) ?: return false
    } else {
        sourceUrl
    }
    val mediaText = if (mediaUrl == sourceUrl) masterText else fetchText(mediaUrl, headers) ?: return false
    val segments = parseSegments(mediaText, mediaUrl)
    if (segments.isEmpty()) return false
    val windowEndMs = windowStartMs + windowMs
    val needed = segments.filter { it.startMs < windowEndMs && it.startMs + it.durationMs > windowStartMs }
    if (needed.isEmpty()) return false
    val tmpDir = NSTemporaryDirectory() + "probe-audio-" + NSUUID().UUIDString + "/"
    if (!NSFileManager.defaultManager.createDirectoryAtPath(tmpDir, withIntermediateDirectories = true, attributes = null, error = null)) {
        return false
    }
    try {
        var anySegment = false
        for ((index, segment) in needed.withIndex()) {
            val data = fetchData(segment.url, headers) ?: continue
            val path = tmpDir + "seg-$index.ts"
            if (!data.toNSData().writeToFile(path, atomically = true)) continue
            val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
            if (readAssetBins(asset, null, false, segment.startMs, windowStartMs, binCount, sumSq, counts)) {
                anySegment = true
            }
        }
        return anySegment
    } finally {
        NSFileManager.defaultManager.removeItemAtPath(tmpDir, error = null)
    }
}

/**
 * Decodes one asset's audio track into the shared bin accumulators. When
 * [timeRange] is set the sample timestamps are media times; otherwise they are
 * file-relative and [baseMs] anchors the file on the media timeline.
 */
private suspend fun readAssetBins(
    asset: AVURLAsset,
    timeRange: CValue<platform.CoreMedia.CMTimeRange>?,
    absolutePts: Boolean,
    baseMs: Long,
    windowStartMs: Long,
    binCount: Int,
    sumSq: DoubleArray,
    counts: IntArray,
): Boolean {
    if (!asset.awaitTracks()) return false
    val track = asset.tracks.firstOrNull { it.mediaType == AVMediaTypeAudio } ?: return false
    val outputSettings: Map<Any?, Any?> = mapOf(
        AV_FORMAT_ID_KEY to NSNumber(unsignedInt = LPCM_FORMAT_ID),
        AV_SAMPLE_RATE_KEY to PROBE_SAMPLE_RATE,
        AV_NUMBER_OF_CHANNELS_KEY to 1,
        AV_LINEAR_PCM_BIT_DEPTH_KEY to 16,
        AV_LINEAR_PCM_IS_FLOAT_KEY to false,
        AV_LINEAR_PCM_IS_NON_INTERLEAVED_KEY to false,
    )
    val output = AVAssetReaderTrackOutput(track, outputSettings)
    val reader = memScoped {
        val error = alloc<ObjCObjectVar<platform.Foundation.NSError?>>()
        AVAssetReader(asset, error.ptr)
    } ?: return false
    if (!reader.canAddOutput(output)) return false
    reader.addOutput(output)
    if (timeRange != null) {
        reader.timeRange = timeRange
    }
    if (!reader.startReading()) return false
    var firstPtsSeconds: Double? = null
    var anySample = false
    while (reader.status == AVAssetReaderStatusReading) {
        val sampleBuffer = output.copyNextSampleBuffer() ?: break
        val ptsSeconds = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
        if (ptsSeconds.isNaN()) continue
        if (firstPtsSeconds == null) {
            firstPtsSeconds = ptsSeconds
        }
        val sampleStartMs = if (absolutePts) {
            ptsSeconds * 1000.0
        } else {
            baseMs + (ptsSeconds - (firstPtsSeconds ?: 0.0)) * 1000.0
        }
        val bytes = memScoped {
            val dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) ?: return@memScoped null
            val totalLength = alloc<ULongVar>()
            val lengthAtOffset = alloc<ULongVar>()
            val dataPointer = alloc<CPointer<ByteVar>>()
            if (CMBlockBufferGetDataPointer(dataBuffer, 0uL, lengthAtOffset.ptr, totalLength.ptr, dataPointer.ptr) != 0) {
                null
            } else {
                val pointer = dataPointer.value ?: return@memScoped null
                pointer.readBytes(totalLength.value.toInt())
            }
        } ?: continue
        val sampleCount = bytes.size / BYTES_PER_SAMPLE
        for (i in 0 until sampleCount) {
            val lo = bytes[i * BYTES_PER_SAMPLE].toInt() and 0xFF
            val hi = bytes[i * BYTES_PER_SAMPLE + 1].toInt()
            val sample16 = (hi shl 8) or lo
            val normalized = sample16 / 32768.0
            val timeMs = sampleStartMs + i * 1000.0 / PROBE_SAMPLE_RATE
            val bin = ((timeMs - windowStartMs) / ProbeAutoSyncAligner.BIN_MS).toInt()
            if (bin in 0 until binCount) {
                sumSq[bin] += normalized * normalized
                counts[bin]++
            }
        }
        anySample = true
    }
    return anySample
}

private suspend fun AVURLAsset.awaitTracks(): Boolean = suspendCancellableCoroutine { cont ->
    loadValuesAsynchronouslyForKeys(listOf("tracks")) {
        val loaded = memScoped {
            val error = alloc<ObjCObjectVar<platform.Foundation.NSError?>>()
            statusOfValueForKey("tracks", error.ptr) == AVKeyValueStatusLoaded
        }
        cont.resume(loaded)
    }
}

private suspend fun fetchData(url: String, headers: Map<String, String>): ByteArray? = try {
    val response = probeHttpClient.get(url) {
        headers.forEach { (key, value) -> header(key, value) }
    }
    if (!response.status.isSuccess()) null else response.body<ByteArray>()
} catch (_: Exception) {
    null
}

private suspend fun fetchText(url: String, headers: Map<String, String>): String? =
    fetchData(url, headers)?.decodeToString()

private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

/** Picks the media playlist for probing: a separate audio rendition if offered, else the cheapest variant. */
private fun selectMediaPlaylist(masterText: String, masterUrl: String): String? {
    val lines = masterText.lines()
    val audioLines = lines.filter { it.startsWith("#EXT-X-MEDIA") && it.contains("TYPE=AUDIO") }
    if (audioLines.isNotEmpty()) {
        val preferred = audioLines.firstOrNull { it.contains("DEFAULT=YES") } ?: audioLines.first()
        val uri = extractQuoted(preferred, "URI") ?: return null
        return resolveUrl(masterUrl, uri)
    }
    var bestUri: String? = null
    var bestBandwidth = Long.MAX_VALUE
    for (index in lines.indices) {
        val line = lines[index]
        if (!line.startsWith("#EXT-X-STREAM-INF")) continue
        val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
            ?: Long.MAX_VALUE
        val uri = lines.getOrNull(index + 1)?.takeIf { !it.startsWith("#") } ?: continue
        if (bandwidth < bestBandwidth) {
            bestBandwidth = bandwidth
            bestUri = uri
        }
    }
    return bestUri?.let { resolveUrl(masterUrl, it) }
}

private fun parseSegments(text: String, baseUrl: String): List<HlsSegment> {
    val segments = mutableListOf<HlsSegment>()
    var elapsedMs = 0L
    var pendingDurationMs: Long? = null
    for (rawLine in text.lines()) {
        val line = rawLine.trim()
        when {
            line.startsWith("#EXTINF:") -> {
                pendingDurationMs = (line.removePrefix("#EXTINF:").substringBefore(",").toDoubleOrNull()?.times(1000.0))?.toLong()
            }
            line.startsWith("#EXT-X-KEY") && !line.contains("METHOD=NONE") -> {
                return emptyList()
            }
            line.isNotEmpty() && !line.startsWith("#") -> {
                val durationMs = pendingDurationMs ?: 0L
                pendingDurationMs = null
                segments.add(HlsSegment(resolveUrl(baseUrl, line), durationMs, elapsedMs))
                elapsedMs += durationMs
            }
        }
    }
    return segments
}

private fun extractQuoted(line: String, attribute: String): String? =
    Regex("$attribute=\"([^\"]+)\"").find(line)?.groupValues?.get(1)

private fun resolveUrl(base: String, ref: String): String {
    if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
    val schemeAndHost = base.substringBefore("/", missingDelimiterValue = base).let { scheme ->
        val rest = base.removePrefix(scheme)
        val hostEnd = rest.indexOf("/", startIndex = 3).takeIf { it >= 0 } ?: rest.length
        scheme + rest.substring(0, hostEnd)
    }
    if (ref.startsWith("/")) return schemeAndHost + ref
    val baseDir = base.substringBeforeLast("/", missingDelimiterValue = base + "/")
    val parts = mutableListOf<String>()
    for (segment in (baseDir.removePrefix(schemeAndHost) + "/" + ref).split("/")) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(segment)
        }
    }
    return schemeAndHost + "/" + parts.joinToString("/")
}

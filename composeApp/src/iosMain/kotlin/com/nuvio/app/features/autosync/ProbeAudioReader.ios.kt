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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusReading
import platform.AVFoundation.AVKeyValueStatusLoaded
import platform.AVFoundation.AVURLAsset
import platform.CoreMedia.CMBlockBufferGetDataPointer
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeRangeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSUUID
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import platform.AVFoundation.AVAssetReaderAudioMixOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import com.nuvio.app.core.logging.InAppLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.cinterop.CValue
import kotlinx.cinterop.usePinned
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


// ---------------------------------------------------------------------------
// MP4 window extraction. AVAssetReader cannot read remote URLs (gate runs 7-8
// proved it: reader creation returns nil for remote assets, works for local
// files). So for direct-file streams we fetch only the bytes the probe window
// needs and rebuild a minimal local mp4 holding just that window's audio
// samples, then decode it with the existing local-file path. Pure box parsing,
// codec-agnostic (stsd is copied verbatim). Any unsupported structure returns
// null, and the probe abstains exactly as it does today: no crash, and never
// a whole-file download. Edit lists (elst) are dropped on purpose: any AAC
// priming shift they carry is far below the probe's 100 ms bin and, if real,
// is exactly the constant offset the probe measures.
// ---------------------------------------------------------------------------

private const val MAX_MOOV_BYTES = 16 * 1024 * 1024L
private const val MAX_WINDOW_BYTES = 64 * 1024 * 1024L
private const val MAX_WINDOW_RANGES = 4096
private const val MERGE_GAP_BYTES = 256 * 1024L
private const val MAX_SPAN_BYTES = 32 * 1024 * 1024L
private const val MAX_MKV_CUES_BYTES = 4 * 1024 * 1024L
private const val MAX_MKV_TRACKS_BYTES = 2 * 1024 * 1024L
private const val MKV_FIRST_CHUNK_BYTES = 1024 * 1024L
private const val MKV_TAIL_PREFIX_BYTES = 16 * 1024 * 1024L
private const val MKV_CHUNK_BYTES = 4 * 1024 * 1024L
private const val MKV_PARALLELISM = 16
private const val MAX_MKV_FETCH_BYTES = 256 * 1024 * 1024L

private fun ByteArray.u32be(offset: Int): Long {
    return ((this[offset].toLong() and 0xFF) shl 24) or
        ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8) or
        (this[offset + 3].toLong() and 0xFF)
}

private fun ByteArray.u64be(offset: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (this[offset + i].toLong() and 0xFF)
    return v
}

private fun ByteArray.boxTypeAt(offset: Int): String =
    copyOfRange(offset + 4, offset + 8).decodeToString()

private fun headerSizeOf(bytes: ByteArray, boxStart: Int): Int =
    if (bytes.u32be(boxStart) == 1L) 16 else 8

private class BoxHeader(val size: Long, val headerSize: Int, val type: String)

private fun parseBoxHeader(bytes: ByteArray, offset: Int): BoxHeader? {
    if (offset + 8 > bytes.size) return null
    val size32 = bytes.u32be(offset)
    val type = bytes.boxTypeAt(offset)
    if (size32 == 1L) {
        if (offset + 16 > bytes.size) return null
        return BoxHeader(bytes.u64be(offset + 8), 16, type)
    }
    if (size32 == 0L) return null
    return BoxHeader(size32, 8, type)
}

// Children of a box, as (type, boxStart, boxEnd) with absolute positions in
// [bytes]. Content starts at boxStart + headerSizeOf(bytes, boxStart).
private fun childrenOf(bytes: ByteArray, start: Int, end: Int): List<Triple<String, Int, Int>> {
    val out = mutableListOf<Triple<String, Int, Int>>()
    var pos = start
    while (pos + 8 <= end) {
        val header = parseBoxHeader(bytes, pos) ?: break
        if (header.size < header.headerSize) break
        if (pos + header.size > end) break
        out.add(Triple(header.type, pos, (pos + header.size).toInt()))
        pos += header.size.toInt()
    }
    return out
}

private fun int32be(value: Long): ByteArray = byteArrayOf(
    ((value shr 24) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    (value and 0xFF).toByte(),
)

private fun int64be(value: Long): ByteArray {
    val out = ByteArray(8)
    for (i in 0 until 8) out[i] = ((value shr ((7 - i) * 8)) and 0xFF).toByte()
    return out
}

private fun concatBytes(parts: List<ByteArray>): ByteArray {
    var total = 0
    for (p in parts) total += p.size
    val out = ByteArray(total)
    var pos = 0
    for (p in parts) {
        p.copyInto(out, pos)
        pos += p.size
    }
    return out
}

private fun box(type: String, payload: ByteArray): ByteArray =
    concatBytes(listOf(int32be((8 + payload.size).toLong()), type.encodeToByteArray(), payload))

private fun fullBox(type: String, payload: ByteArray): ByteArray =
    box(type, concatBytes(listOf(byteArrayOf(0, 0, 0, 0), payload)))

private fun patchU32(boxBytes: ByteArray, offset: Int, value: Long): ByteArray {
    val out = boxBytes.copyOf()
    val b = int32be(value)
    for (i in 0 until 4) out[offset + i] = b[i]
    return out
}

private fun patchU64(boxBytes: ByteArray, offset: Int, value: Long): ByteArray {
    val out = boxBytes.copyOf()
    val b = int64be(value)
    for (i in 0 until 8) out[offset + i] = b[i]
    return out
}

// Range fetch that refuses anything but the exact byte count requested, so a
// server ignoring Range and answering 200 with the whole file can never turn
// into a multi-gigabyte download. Retries transient failures (3 attempts,
// 1s apart); every failed attempt logs its reason (status, size, or exception
// kind) so device failures can be told apart. Follows up to 5 redirects per
// attempt (the probe client surfaces 3xx instead of following them, e.g. a
// PenguPlay direct URL 307s to the real backend); the Range header is
// re-issued on each hop. Never logs the URL.
private suspend fun fetchRange(
    url: String,
    headers: Map<String, String>,
    start: Long,
    endInclusive: Long,
): ByteArray? {
    for (attempt in 1..3) {
        val bytes = fetchRangeOnce(url, headers, start, endInclusive, attempt)
        if (bytes != null) {
            if (attempt > 1) {
                InAppLogger.debug("Player/ProbeAudioReader", "fetchRange: attempt ${attempt} succeeded start=${start}")
            }
            return bytes
        }
        if (attempt < 3) {
            delay(1000)
        }
    }
    return null
}

private suspend fun fetchRangeOnce(
    url: String,
    headers: Map<String, String>,
    start: Long,
    endInclusive: Long,
    attempt: Int,
): ByteArray? = try {
    var currentUrl = url
    var currentHeaders = headers
    for (hop in 1..5) {
        val response = probeHttpClient.get(currentUrl) {
            currentHeaders.forEach { (key, value) -> header(key, value) }
            header("Range", "bytes=$start-$endInclusive")
        }
        val status = response.status.value
        if (status in setOf(301, 302, 303, 307, 308)) {
            val location = response.headers["Location"]
            if (location == null) {
                InAppLogger.debug("Player/ProbeAudioReader", "fetchRange: attempt ${attempt} redirect without location status=${status} start=${start}")
                return null
            }
            val nextUrl = resolveRedirect(currentUrl, location)
            if (nextUrl == null) {
                InAppLogger.debug("Player/ProbeAudioReader", "fetchRange: attempt ${attempt} redirect location unsupported start=${start}")
                return null
            }
            InAppLogger.debug("Player/ProbeAudioReader", "fetchRange: attempt ${attempt} redirect status=${status} hop=${hop} start=${start}")
            if (hostOf(nextUrl) != hostOf(currentUrl)) {
                currentHeaders = currentHeaders.filterKeys { it.lowercase() != "authorization" }
            }
            currentUrl = nextUrl
            continue
        }
        if (!response.status.isSuccess()) {
            InAppLogger.debug("Player/ProbeAudioReader", "fetchRange: attempt ${attempt} status=${status} start=${start}")
            return null
        }
        val bytes = response.body<ByteArray>()
        val expected = endInclusive - start + 1
        if (bytes.size.toLong() == expected) {
            return bytes
        }
        InAppLogger.debug("Player/ProbeAudioReader", "fetchRange: attempt ${attempt} size mismatch expected=${expected} got=${bytes.size} start=${start}")
        return null
    }
    InAppLogger.debug("Player/ProbeAudioReader", "fetchRange: attempt ${attempt} redirect hops exceeded start=${start}")
    null
} catch (e: Exception) {
    InAppLogger.debug("Player/ProbeAudioReader", "fetchRange: attempt ${attempt} exception kind=${e::class.simpleName} start=${start}")
    null
}

private fun hostOf(url: String): String =
    url.substringAfter("://", "").substringBefore("/").substringBefore(":")

private fun resolveRedirect(currentUrl: String, location: String): String? {
    if (location.startsWith("http://") || location.startsWith("https://")) {
        return location
    }
    if (!location.startsWith("/")) {
        return null
    }
    val scheme = currentUrl.substringBefore("://")
    val authority = currentUrl.substringAfter("://", "").substringBefore("/")
    if (scheme == currentUrl || authority.isEmpty()) {
        return null
    }
    return "$scheme://$authority$location"
}

// Walks top-level boxes by size (never downloading mdat content) until moov
// is found. Returns (ftyp box or null, moov box or null).
private suspend fun fetchMoovAndFtyp(
    url: String,
    headers: Map<String, String>,
): Pair<ByteArray?, ByteArray?> {
    var ftyp: ByteArray? = null
    var pos = 0L
    for (iter in 0 until 200) {
        val hdr = fetchRange(url, headers, pos, pos + 15)
        if (hdr == null) {
            InAppLogger.debug("Player/ProbeAudioReader", "moov scan: header fetch failed pos=${pos}")
            return Pair(ftyp, null)
        }
        val parsed = parseBoxHeader(hdr, 0)
        if (parsed == null) {
            InAppLogger.debug("Player/ProbeAudioReader", "moov scan: header parse failed pos=${pos}")
            return Pair(ftyp, null)
        }
        if (parsed.type == "moov") {
            if (parsed.size > MAX_MOOV_BYTES) {
                InAppLogger.debug("Player/ProbeAudioReader", "moov scan: moov too big size=${parsed.size}")
                return Pair(ftyp, null)
            }
            val moov = fetchRange(url, headers, pos, pos + parsed.size - 1)
            if (moov == null) {
                InAppLogger.debug("Player/ProbeAudioReader", "moov scan: moov fetch failed size=${parsed.size}")
                return Pair(ftyp, null)
            }
            return Pair(ftyp, moov)
        }
        if (parsed.type == "ftyp") {
            fetchRange(url, headers, pos, pos + parsed.size - 1)?.let { ftyp = it }
        }
        if (parsed.size <= 0) {
            InAppLogger.debug("Player/ProbeAudioReader", "moov scan: zero-size box type=${parsed.type}")
            return Pair(ftyp, null)
        }
        pos += parsed.size
    }
    InAppLogger.debug("Player/ProbeAudioReader", "moov scan: moov not found")
    return Pair(ftyp, null)
}

private class ParsedAudioTrack(
    val timescale: Long,
    val tkhd: ByteArray,
    val mdhd: ByteArray,
    val hdlr: ByteArray,
    val smhd: ByteArray?,
    val dinf: ByteArray?,
    val stsd: ByteArray,
    val stts: List<Pair<Long, Long>>,
    val sampleSizes: IntArray,
    val stsc: List<Triple<Long, Long, Long>>,
    val chunkOffsets: LongArray,
)

private class ParsedMoov(
    val mvhd: ByteArray,
    val movieTimescale: Long,
    val track: ParsedAudioTrack,
)

private fun parseAudioTrack(moovBox: ByteArray): ParsedMoov? {
    val moovHeader = headerSizeOf(moovBox, 0)
    val moovChildren = childrenOf(moovBox, moovHeader, moovBox.size)
    val mvhdEntry = moovChildren.firstOrNull { it.first == "mvhd" }
    if (mvhdEntry == null) {
        InAppLogger.debug("Player/ProbeAudioReader", "moov parse: no mvhd")
        return null
    }
    val mvhd = moovBox.copyOfRange(mvhdEntry.second, mvhdEntry.third)
    val mvhdVersion = mvhd[8].toInt()
    val movieTimescale = if (mvhdVersion == 1) mvhd.u32be(28) else mvhd.u32be(20)
    for ((trakType, trakStart, trakEnd) in moovChildren) {
        if (trakType != "trak") continue
        val trakHeader = headerSizeOf(moovBox, trakStart)
        val trakChildren = childrenOf(moovBox, trakStart + trakHeader, trakEnd)
        val tkhdEntry = trakChildren.firstOrNull { it.first == "tkhd" } ?: continue
        val tkhd = moovBox.copyOfRange(tkhdEntry.second, tkhdEntry.third)
        val mdiaEntry = trakChildren.firstOrNull { it.first == "mdia" } ?: continue
        val mdiaHeader = headerSizeOf(moovBox, mdiaEntry.second)
        val mdiaChildren = childrenOf(moovBox, mdiaEntry.second + mdiaHeader, mdiaEntry.third)
        val mdhdEntry = mdiaChildren.firstOrNull { it.first == "mdhd" } ?: continue
        val mdhd = moovBox.copyOfRange(mdhdEntry.second, mdhdEntry.third)
        val mdhdVersion = mdhd[8].toInt()
        val timescale = if (mdhdVersion == 1) mdhd.u32be(28) else mdhd.u32be(20)
        if (timescale <= 0) continue
        val hdlrEntry = mdiaChildren.firstOrNull { it.first == "hdlr" } ?: continue
        val hdlr = moovBox.copyOfRange(hdlrEntry.second, hdlrEntry.third)
        val hdlrContent = hdlrEntry.second + headerSizeOf(moovBox, hdlrEntry.second)
        if (moovBox.copyOfRange(hdlrContent + 8, hdlrContent + 12).decodeToString() != "soun") continue
        val minfEntry = mdiaChildren.firstOrNull { it.first == "minf" } ?: continue
        val minfHeader = headerSizeOf(moovBox, minfEntry.second)
        val minfChildren = childrenOf(moovBox, minfEntry.second + minfHeader, minfEntry.third)
        val stblEntry = minfChildren.firstOrNull { it.first == "stbl" } ?: continue
        val stblHeader = headerSizeOf(moovBox, stblEntry.second)
        val stblChildren = childrenOf(moovBox, stblEntry.second + stblHeader, stblEntry.third)
        val smhdEntry = minfChildren.firstOrNull { it.first == "smhd" }
        val smhd = smhdEntry?.let { moovBox.copyOfRange(it.second, it.third) }
        val dinfEntry = minfChildren.firstOrNull { it.first == "dinf" }
        val dinf = dinfEntry?.let { moovBox.copyOfRange(it.second, it.third) }
        val stsdEntry = stblChildren.firstOrNull { it.first == "stsd" } ?: continue
        val stsd = moovBox.copyOfRange(stsdEntry.second, stsdEntry.third)
        val stsdContent = stsdEntry.second + headerSizeOf(moovBox, stsdEntry.second)
        if (moovBox.u32be(stsdContent + 4) != 1L) {
            InAppLogger.debug("Player/ProbeAudioReader", "audio trak: stsd entries != 1")
            continue
        }
        val sttsEntry = stblChildren.firstOrNull { it.first == "stts" } ?: continue
        val sttsContent = sttsEntry.second + headerSizeOf(moovBox, sttsEntry.second)
        val sttsCount = moovBox.u32be(sttsContent + 4).toInt()
        val stts = mutableListOf<Pair<Long, Long>>()
        var p = sttsContent + 8
        for (i in 0 until sttsCount) {
            stts.add(Pair(moovBox.u32be(p), moovBox.u32be(p + 4)))
            p += 8
        }
        val stszEntry = stblChildren.firstOrNull { it.first == "stsz" } ?: continue
        val stszContent = stszEntry.second + headerSizeOf(moovBox, stszEntry.second)
        val uniformSize = moovBox.u32be(stszContent + 4)
        val sampleCount = moovBox.u32be(stszContent + 8).toInt()
        val sampleSizes = if (uniformSize != 0L) {
            IntArray(sampleCount) { uniformSize.toInt() }
        } else {
            val arr = IntArray(sampleCount)
            var q = stszContent + 12
            for (i in 0 until sampleCount) {
                arr[i] = moovBox.u32be(q).toInt()
                q += 4
            }
            arr
        }
        val stscEntry = stblChildren.firstOrNull { it.first == "stsc" } ?: continue
        val stscContent = stscEntry.second + headerSizeOf(moovBox, stscEntry.second)
        val stscCount = moovBox.u32be(stscContent + 4).toInt()
        val stsc = mutableListOf<Triple<Long, Long, Long>>()
        var r = stscContent + 8
        for (i in 0 until stscCount) {
            stsc.add(Triple(moovBox.u32be(r), moovBox.u32be(r + 4), moovBox.u32be(r + 8)))
            r += 12
        }
        val stcoEntry = stblChildren.firstOrNull { it.first == "stco" }
        val co64Entry = stblChildren.firstOrNull { it.first == "co64" }
        val chunkOffsets = when {
            stcoEntry != null -> {
                val c = stcoEntry.second + headerSizeOf(moovBox, stcoEntry.second)
                val n = moovBox.u32be(c + 4).toInt()
                LongArray(n) { moovBox.u32be(c + 8 + it * 4) }
            }
            co64Entry != null -> {
                val c = co64Entry.second + headerSizeOf(moovBox, co64Entry.second)
                val n = moovBox.u32be(c + 4).toInt()
                LongArray(n) { moovBox.u64be(c + 8 + it * 8) }
            }
            else -> continue
        }
        return ParsedMoov(
            mvhd = mvhd,
            movieTimescale = movieTimescale,
            track = ParsedAudioTrack(
                timescale = timescale,
                tkhd = tkhd,
                mdhd = mdhd,
                hdlr = hdlr,
                smhd = smhd,
                dinf = dinf,
                stsd = stsd,
                stts = stts,
                sampleSizes = sampleSizes,
                stsc = stsc,
                chunkOffsets = chunkOffsets,
            ),
        )
    }
    InAppLogger.debug("Player/ProbeAudioReader", "moov parse: no audio trak")
    return null
}

// Chunk (1-based) and first sample index of that chunk for sample [s].
private fun chunkForSample(stsc: List<Triple<Long, Long, Long>>, s: Long): Pair<Long, Long> {
    var firstSample: Long = 0
    for (j in stsc.indices) {
        val (firstChunk, spc, _) = stsc[j]
        if (spc <= 0) return Pair(-1, -1)
        if (j == stsc.size - 1) {
            val chunk = firstChunk + (s - firstSample) / spc
            return Pair(chunk, firstSample + (chunk - firstChunk) * spc)
        }
        val nextFirst = stsc[j + 1].first
        val covered = (nextFirst - firstChunk) * spc
        if (s < firstSample + covered) {
            val chunk = firstChunk + (s - firstSample) / spc
            return Pair(chunk, firstSample + (chunk - firstChunk) * spc)
        }
        firstSample += covered
    }
    return Pair(-1, -1)
}

private fun buildMoov(
    mvhdOrig: ByteArray,
    tkhdOrig: ByteArray,
    mdhdOrig: ByteArray,
    hdlr: ByteArray,
    smhd: ByteArray?,
    dinf: ByteArray?,
    stsd: ByteArray,
    sttsEntries: List<Pair<Long, Long>>,
    sampleSizes: List<Int>,
    movieTimescale: Long,
    windowMs: Long,
    selectedDeltaSum: Long,
    stcoValue: Long,
): ByteArray {
    val movieDuration = windowMs * movieTimescale / 1000
    val mvhdPatched = if (mvhdOrig[8].toInt() == 1) {
        patchU64(mvhdOrig, 32, movieDuration)
    } else {
        patchU32(mvhdOrig, 24, movieDuration)
    }
    val tkhdPatched = if (tkhdOrig[8].toInt() == 1) {
        patchU64(tkhdOrig, 36, movieDuration)
    } else {
        patchU32(tkhdOrig, 28, movieDuration)
    }
    val mdhdPatched = if (mdhdOrig[8].toInt() == 1) {
        patchU64(mdhdOrig, 32, selectedDeltaSum)
    } else {
        patchU32(mdhdOrig, 24, selectedDeltaSum)
    }
    val sttsPayload = concatBytes(
        listOf(int32be(sttsEntries.size.toLong())) +
            sttsEntries.map { (c, d) -> int32be(c) + int32be(d) },
    )
    val stszPayload = concatBytes(
        listOf(int32be(0), int32be(sampleSizes.size.toLong())) +
            sampleSizes.map { int32be(it.toLong()) },
    )
    val stscPayload = concatBytes(
        listOf(int32be(1), int32be(1), int32be(sampleSizes.size.toLong()), int32be(1)),
    )
    val stcoPayload = concatBytes(listOf(int32be(1), int32be(stcoValue)))
    val smhdBox = smhd ?: fullBox("smhd", byteArrayOf(0, 0, 0, 0))
    val dinfBox = dinf ?: fullBox(
        "dinf",
        fullBox("dref", concatBytes(listOf(int32be(1), box("url ", byteArrayOf(0, 0, 0, 1))))),
    )
    val stbl = box(
        "stbl",
        concatBytes(
            listOf(
                stsd,
                fullBox("stts", sttsPayload),
                fullBox("stsz", stszPayload),
                fullBox("stsc", stscPayload),
                fullBox("stco", stcoPayload),
            ),
        ),
    )
    val minf = box("minf", concatBytes(listOf(smhdBox, dinfBox, stbl)))
    val mdia = box("mdia", concatBytes(listOf(mdhdPatched, hdlr, minf)))
    val trak = box("trak", concatBytes(listOf(tkhdPatched, mdia)))
    return box("moov", concatBytes(listOf(mvhdPatched, trak)))
}


// ---- Matroska / WebM (EBML) window extraction ----
// The probe's MP4 path walks boxes; Matroska is a different byte grammar
// (EBML variable-length IDs and sizes), so it gets its own extractor that
// returns the same thing: a local window.mp4 holding only the window's
// audio frames, decoded by the shared readAssetBins path afterwards.
// Design notes:
// - Cues (via SeekHead) locate Clusters; without Cues, a bounded header
//   walk skips known-size Clusters. Unknown-size Clusters without Cues
//   abstain rather than guess.
// - Block timestamps select the window; codec frame durations in samples
//   build stts exactly (no ms rounding: AAC 1024, EAC3/AC3 1536, MP3 1152,
//   Opus from its TOC packet header).
// - mvhd/tkhd/mdhd/hdlr are synthesized from scratch and fed through the
//   same two-pass buildMoov assembly as the MP4 path.

private fun ebmlVintLen(first: Int): Int {
    var mask = 0x80
    var len = 1
    while (len <= 8 && first and mask == 0) { mask = mask shr 1; len++ }
    return if (len > 8) -1 else len
}

private fun parseEbmlId(bytes: ByteArray, offset: Int): Pair<Long, Int>? {
    if (offset >= bytes.size) return null
    val len = ebmlVintLen(bytes[offset].toInt() and 0xFF)
    if (len < 1 || len > 4 || offset + len > bytes.size) return null
    var v = 0L
    for (i in 0 until len) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
    return Pair(v, len)
}

private fun parseEbmlSize(bytes: ByteArray, offset: Int): Pair<Long, Int>? {
    if (offset >= bytes.size) return null
    val first = bytes[offset].toInt() and 0xFF
    val len = ebmlVintLen(first)
    if (len < 1 || len > 8 || offset + len > bytes.size) return null
    var v = (first and (0xFF shr len)).toLong()
    for (i in 1 until len) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
    if (v == (1L shl (7 * len)) - 1) return Pair(-1L, len)
    return Pair(v, len)
}

private class EbmlHeader(val id: Long, val size: Long, val headerLen: Int)

private fun parseEbmlHeader(bytes: ByteArray, offset: Int): EbmlHeader? {
    val idPart = parseEbmlId(bytes, offset) ?: return null
    val sizePart = parseEbmlSize(bytes, offset + idPart.second) ?: return null
    return EbmlHeader(idPart.first, sizePart.first, idPart.second + sizePart.second)
}

private fun ByteArray.uintBe(offset: Int, len: Int): Long {
    var v = 0L
    for (i in 0 until len) v = (v shl 8) or (this[offset + i].toLong() and 0xFF)
    return v
}

private fun ByteArray.floatBe(offset: Int, len: Int): Double {
    return when (len) {
        4 -> Float.fromBits(uintBe(offset, 4).toInt()).toDouble()
        8 -> Double.fromBits(uintBe(offset, 8))
        else -> 0.0
    }
}

private fun ByteArray.asciiString(offset: Int, len: Int): String {
    if (len <= 0) return ""
    return decodeToString(offset, offset + len)
}

private fun ByteArray.u16le(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32le(offset: Int): Long =
    (this[offset].toLong() and 0xFF) or ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or ((this[offset + 3].toLong() and 0xFF) shl 24)

private fun ByteArray.i16le(offset: Int): Int {
    val u = u16le(offset)
    return if (u >= 0x8000) u - 0x10000 else u
}

private fun int32le(value: Long): ByteArray =
    byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

private fun forEachEbmlChild(bytes: ByteArray, start: Int, end: Int, strictEnd: Boolean = false, action: (Long, Int, Int) -> Unit) {
    var pos = start
    while (pos < end) {
        val h = parseEbmlHeader(bytes, pos) ?: return
        val dataStart = pos + h.headerLen
        if (strictEnd && h.size >= 0 && dataStart + h.size > end) return
        val dataEnd = if (h.size < 0) end else (dataStart + h.size.toInt()).coerceAtMost(end)
        if (dataEnd <= pos) return
        action(h.id, dataStart, dataEnd)
        pos = dataEnd
    }
}

private fun scaleTime(raw: Long, scale: Long): Long {
    return raw / 1_000_000L * scale + (raw % 1_000_000L) * scale / 1_000_000L
}

private class MkvTrack(
    val number: Long,
    val codecId: String,
    val codecPrivate: ByteArray?,
    val sampleRate: Double,
    val channels: Int,
    val isDefault: Boolean,
)

private fun parseMkvTrackEntry(bytes: ByteArray, start: Int, end: Int): MkvTrack? {
    var number = -1L
    var type = -1L
    var codecId = ""
    var codecPrivate: ByteArray? = null
    var rate = 0.0
    var channels = 0
    var isDefault = true
    forEachEbmlChild(bytes, start, end) { tid, tds, tde ->
        when (tid) {
            0xD7L -> number = bytes.uintBe(tds, tde - tds)
            0x83L -> type = bytes.uintBe(tds, tde - tds)
            0x86L -> codecId = bytes.asciiString(tds, tde - tds)
            0x63A2L -> codecPrivate = bytes.copyOfRange(tds, tde)
            0x88L -> isDefault = bytes.uintBe(tds, tde - tds) != 0L
            0xE1L -> forEachEbmlChild(bytes, tds, tde) { aid, ads, ade ->
                when (aid) {
                    0xB5L -> rate = bytes.floatBe(ads, ade - ads)
                    0x9FL -> channels = bytes.uintBe(ads, ade - ads).toInt()
                }
            }
        }
    }
    if (number < 0 || type != 2L || codecId.isEmpty()) return null
    return MkvTrack(number, codecId, codecPrivate, rate, channels, isDefault)
}

private fun parseMkvTracks(bytes: ByteArray, start: Int, end: Int): List<MkvTrack> {
    val out = mutableListOf<MkvTrack>()
    forEachEbmlChild(bytes, start, end) { tid, tds, tde ->
        if (tid == 0xAEL) {
            val track = parseMkvTrackEntry(bytes, tds, tde)
            if (track != null) out.add(track)
        }
    }
    return out
}

private fun parseMkvSeekHead(bytes: ByteArray, start: Int, end: Int): Map<Long, Long> {
    val out = mutableMapOf<Long, Long>()
    forEachEbmlChild(bytes, start, end) { tid, tds, tde ->
        if (tid == 0x4DBBL) {
            var seekId = -1L
            var seekPos = -1L
            forEachEbmlChild(bytes, tds, tde) { sid, sds, sde ->
                when (sid) {
                    0x53ABL -> seekId = bytes.uintBe(sds, sde - sds)
                    0x53ACL -> seekPos = bytes.uintBe(sds, sde - sds)
                }
            }
            if (seekId >= 0 && seekPos >= 0) out[seekId] = seekPos
        }
    }
    return out
}

private class MkvCue(val timeMs: Long, val clusterPos: Long)

private fun parseMkvCues(bytes: ByteArray, start: Int, end: Int, timeScale: Long): List<MkvCue> {
    val out = mutableListOf<MkvCue>()
    forEachEbmlChild(bytes, start, end) { tid, tds, tde ->
        if (tid == 0xBBL) {
            var cueTime = -1L
            var clusterPos = -1L
            forEachEbmlChild(bytes, tds, tde) { cid, cds, cde ->
                when (cid) {
                    0xB3L -> cueTime = bytes.uintBe(cds, cde - cds)
                    0xB7L -> forEachEbmlChild(bytes, cds, cde) { pid, pds, pde ->
                        if (pid == 0xF1L) clusterPos = bytes.uintBe(pds, pde - pds)
                    }
                }
            }
            if (cueTime >= 0 && clusterPos >= 0) out.add(MkvCue(scaleTime(cueTime, timeScale), clusterPos))
        }
    }
    return out
}


private class MkvFrame(val timeMs: Long, val bytes: ByteArray)

private fun opusPacketSamples(packet: ByteArray): Int {
    if (packet.isEmpty()) return -1
    val toc = packet[0].toInt() and 0xFF
    val config = toc shr 3
    val frameSamples = when (config) {
        0, 4, 8, 12, 14 -> 480
        1, 5, 9, 13, 15 -> 960
        2, 6, 10 -> 1920
        3, 7, 11 -> 2880
        16 -> 120
        17 -> 240
        18 -> 480
        19 -> 960
        else -> return -1
    }
    val code = toc and 0x03
    val frameCount = when (code) {
        0 -> 1
        1 -> 2
        2 -> 2
        else -> {
            if (packet.size < 2) return -1
            packet[1].toInt() and 0xFF
        }
    }
    return frameSamples * frameCount
}

private fun codecFrameSamples(codecId: String, frame: ByteArray): Int {
    return when (codecId) {
        "A_AAC" -> 1024
        "A_EAC3" -> 1536
        "A_AC3" -> 1536
        "A_MPEG/L3" -> 1152
        "A_OPUS" -> opusPacketSamples(frame)
        else -> -1
    }
}

private fun parseMkvBlockData(
    data: ByteArray,
    wantTrack: Long,
    clusterTimeMs: Long,
    timeScale: Long,
    trackRate: Double,
    codecId: String,
    out: MutableList<MkvFrame>,
): Boolean {
    var pos = 0
    val trackPart = parseEbmlSize(data, pos) ?: return false
    pos += trackPart.second
    if (trackPart.first != wantTrack) return true
    if (pos + 3 > data.size) return false
    val timecode = (data[pos].toInt() shl 8) or (data[pos + 1].toInt() and 0xFF)
    pos += 2
    val flags = data[pos].toInt() and 0xFF
    pos += 1
    val laceType = (flags shr 5) and 0x03
    val blockTimeMs = clusterTimeMs + scaleTime(timecode.toLong(), timeScale)
    if (laceType == 0) {
        out.add(MkvFrame(blockTimeMs, data.copyOfRange(pos, data.size)))
        return true
    }
    if (pos >= data.size) return false
    val laceCount = (data[pos].toInt() and 0xFF) + 1
    pos += 1
    val sizes = IntArray(laceCount)
    if (laceCount == 1) {
        sizes[0] = data.size - pos
    } else {
        when (laceType) {
            1 -> {
                for (i in 0 until laceCount - 1) {
                    var sz = 0
                    while (true) {
                        if (pos >= data.size) return false
                        val b = data[pos].toInt() and 0xFF
                        pos++
                        sz += b
                        if (b < 255) break
                    }
                    sizes[i] = sz
                }
                var rem = data.size - pos
                for (i in 0 until laceCount - 1) rem -= sizes[i]
                if (rem < 0) return false
                sizes[laceCount - 1] = rem
            }
            2 -> {
                val rem = data.size - pos
                if (rem % laceCount != 0) return false
                for (i in 0 until laceCount) sizes[i] = rem / laceCount
            }
            3 -> {
                val firstPart = parseEbmlSize(data, pos) ?: return false
                pos += firstPart.second
                sizes[0] = firstPart.first.toInt()
                var prev = sizes[0]
                for (i in 1 until laceCount - 1) {
                    if (pos >= data.size) return false
                    val fl = ebmlVintLen(data[pos].toInt() and 0xFF)
                    if (fl < 1 || fl > 8 || pos + fl > data.size) return false
                    var u = (data[pos].toInt() and 0xFF and (0xFF shr fl)).toLong()
                    for (j in 1 until fl) u = (u shl 8) or (data[pos + j].toLong() and 0xFF)
                    val bias = (1L shl (7 * fl - 1)) - 1
                    val diff = u - bias
                    pos += fl
                    val sz = prev + diff
                    if (sz < 0 || sz > Int.MAX_VALUE) return false
                    sizes[i] = sz.toInt()
                    prev = sizes[i]
                }
                var rem = data.size - pos
                for (i in 0 until laceCount - 1) rem -= sizes[i]
                if (rem < 0) return false
                sizes[laceCount - 1] = rem
            }
            else -> return false
        }
    }
    // Laced frame times use codec durations; the selection rate only needs
    // to be close (100ms bins), so an unknown rate falls back to 48k here.
    // stts itself always uses exact codec sample counts, never this.
    val selRate = if (trackRate > 0.0) trackRate else 48000.0
    var cumSamples = 0L
    for (i in 0 until laceCount) {
        val sz = sizes[i]
        if (sz < 0 || pos + sz > data.size) return false
        val frame = data.copyOfRange(pos, pos + sz)
        pos += sz
        val frameTimeMs = blockTimeMs + (cumSamples * 1000.0 / selRate).toLong()
        out.add(MkvFrame(frameTimeMs, frame))
        val s = codecFrameSamples(codecId, frame)
        if (s < 0) return false
        cumSamples += s
    }
    return true
}

private fun parseMkvClusterPayload(
    buf: ByteArray,
    start: Int,
    end: Int,
    wantTrack: Long,
    timeScale: Long,
    trackRate: Double,
    codecId: String,
    out: MutableList<MkvFrame>,
): Boolean {
    var clusterTimeMs = 0L
    var sawTimestamp = false
    var ok = true
    forEachEbmlChild(buf, start, end, strictEnd = true) { eid, eds, ede ->
        if (!ok) return@forEachEbmlChild
        when (eid) {
            0xE7L -> {
                clusterTimeMs = scaleTime(buf.uintBe(eds, ede - eds), timeScale)
                sawTimestamp = true
            }
            0xA3L -> {
                if (!sawTimestamp) InAppLogger.debug("Player/ProbeAudioReader", "mkv: block before cluster timestamp")
                val blockData = buf.copyOfRange(eds, ede)
                if (!parseMkvBlockData(blockData, wantTrack, clusterTimeMs, timeScale, trackRate, codecId, out)) ok = false
            }
            0xA0L -> {
                forEachEbmlChild(buf, eds, ede, strictEnd = true) { bid, bds, bde ->
                    if (bid == 0xA1L) {
                        if (!sawTimestamp) InAppLogger.debug("Player/ProbeAudioReader", "mkv: block before cluster timestamp")
                        val blockData = buf.copyOfRange(bds, bde)
                        if (!parseMkvBlockData(blockData, wantTrack, clusterTimeMs, timeScale, trackRate, codecId, out)) ok = false
                    }
                }
            }
        }
    }
    return ok
}

private class BitReader(val bytes: ByteArray, var bitPos: Int = 0) {
    fun bits(n: Int): Int {
        var v = 0
        for (i in 0 until n) {
            val byteIdx = bitPos / 8
            if (byteIdx >= bytes.size) return v
            val bit = (bytes[byteIdx].toInt() shr (7 - bitPos % 8)) and 1
            v = (v shl 1) or bit
            bitPos++
        }
        return v
    }
    fun skip(n: Int) {
        bitPos += n
    }
}

private class Ac3Header(
    val sampleRate: Long,
    val fscod: Int,
    val frmsizcod: Int,
    val bsid: Int,
    val bsmod: Int,
    val acmod: Int,
    val lfeon: Int,
)

private fun parseEac3Header(frame: ByteArray): Ac3Header? {
    if (frame.size < 8) return null
    if ((frame[0].toInt() and 0xFF) != 0x0B || (frame[1].toInt() and 0xFF) != 0x77) return null
    val r = BitReader(frame, 16)
    r.skip(2 + 3 + 11)
    val fscod = r.bits(2)
    val fscod2 = if (fscod == 3) r.bits(2) else -1
    val bsid = r.bits(5)
    val bsmod = r.bits(3)
    val acmod = r.bits(3)
    if (acmod == 2) r.skip(2)
    val lfeon = r.bits(1)
    val rate = when {
        fscod == 0 -> 48000L
        fscod == 1 -> 44100L
        fscod == 2 -> 32000L
        fscod2 == 0 -> 24000L
        fscod2 == 1 -> 22050L
        fscod2 == 2 -> 16000L
        else -> return null
    }
    return Ac3Header(rate, fscod, -1, bsid, bsmod, acmod, lfeon)
}

private fun parseAc3Header(frame: ByteArray): Ac3Header? {
    if (frame.size < 7) return null
    if ((frame[0].toInt() and 0xFF) != 0x0B || (frame[1].toInt() and 0xFF) != 0x77) return null
    val r = BitReader(frame, 16)
    r.skip(16)
    val fscod = r.bits(2)
    val frmsizcod = r.bits(6)
    val bsid = r.bits(5)
    val bsmod = r.bits(3)
    val acmod = r.bits(3)
    if (acmod == 2) r.skip(2)
    val lfeon = r.bits(1)
    val rate = when (fscod) {
        0 -> 48000L
        1 -> 44100L
        2 -> 32000L
        else -> return null
    }
    return Ac3Header(rate, fscod, frmsizcod, bsid, bsmod, acmod, lfeon)
}

private fun acmodChannels(acmod: Int, lfeon: Int): Int {
    val main = when (acmod) {
        0 -> 2
        1 -> 1
        2 -> 2
        3 -> 3
        4 -> 3
        5 -> 4
        6 -> 4
        7 -> 5
        else -> 2
    }
    return main + lfeon
}

private fun aacAscRateChannels(asc: ByteArray): Pair<Long, Int>? {
    if (asc.size < 2) return null
    val r = BitReader(asc, 0)
    r.bits(5)
    val srIndex = r.bits(4)
    val rates = longArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    if (srIndex < 0 || srIndex > 12) return null
    val ch = r.bits(4)
    return Pair(rates[srIndex], ch)
}

private fun mp3FrameRate(frame: ByteArray): Long {
    if (frame.size < 4) return 0
    if ((frame[0].toInt() and 0xFF) != 0xFF || (frame[1].toInt() and 0xE0) != 0xE0) return 0
    val version = (frame[1].toInt() shr 3) and 0x03
    val srIndex = (frame[2].toInt() shr 2) and 0x03
    if (srIndex == 3) return 0
    return when (version) {
        3 -> longArrayOf(44100, 48000, 32000)[srIndex]
        2 -> longArrayOf(22050, 24000, 16000)[srIndex]
        0 -> longArrayOf(11025, 12000, 8000)[srIndex]
        else -> 0
    }
}

private class OpusHead(
    val channels: Int,
    val preSkip: Int,
    val inputRate: Long,
    val outputGain: Int,
    val mappingFamily: Int,
    val streamCount: Int,
    val coupledCount: Int,
    val mapping: ByteArray,
)

private fun parseOpusHead(priv: ByteArray): OpusHead? {
    if (priv.size < 19) return null
    if (priv.decodeToString(0, 8) != "OpusHead") return null
    if ((priv[8].toInt() and 0xFF) != 0) return null
    val channels = priv[9].toInt() and 0xFF
    val preSkip = priv.u16le(10)
    val inputRate = priv.u32le(12)
    val outputGain = priv.i16le(16)
    val mappingFamily = priv[18].toInt() and 0xFF
    if (mappingFamily == 0) {
        return OpusHead(channels, preSkip, inputRate, outputGain, 0, 1, 0, ByteArray(0))
    }
    if (priv.size < 21 + channels) return null
    val streamCount = priv[19].toInt() and 0xFF
    val coupledCount = priv[20].toInt() and 0xFF
    return OpusHead(channels, preSkip, inputRate, outputGain, mappingFamily, streamCount, coupledCount, priv.copyOfRange(21, 21 + channels))
}

private fun resolveMkvRateChannels(track: MkvTrack, firstFrame: ByteArray): Pair<Long, Int>? {
    var rate = if (track.sampleRate > 0.0) track.sampleRate.toLong() else 0L
    var channels = track.channels
    when (track.codecId) {
        "A_AAC" -> {
            val ascInfo = track.codecPrivate?.let { aacAscRateChannels(it) }
            if (rate == 0L) rate = ascInfo?.first ?: 0L
            if (channels == 0) channels = ascInfo?.second ?: 0
        }
        "A_EAC3" -> {
            val h = parseEac3Header(firstFrame)
            if (rate == 0L) rate = h?.sampleRate ?: 0L
            if (channels == 0) channels = h?.let { acmodChannels(it.acmod, it.lfeon) } ?: 0
        }
        "A_AC3" -> {
            val h = parseAc3Header(firstFrame)
            if (rate == 0L) rate = h?.sampleRate ?: 0L
            if (channels == 0) channels = h?.let { acmodChannels(it.acmod, it.lfeon) } ?: 0
        }
        "A_OPUS" -> {
            if (rate == 0L) rate = 48000L
            if (channels == 0) channels = track.codecPrivate?.let { parseOpusHead(it)?.channels } ?: 0
        }
        "A_MPEG/L3" -> {
            if (rate == 0L) rate = mp3FrameRate(firstFrame)
        }
    }
    if (rate <= 0L) return null
    if (channels <= 0) channels = 2
    return Pair(rate, channels)
}

private fun unityMatrix(): ByteArray = byteArrayOf(
    0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00,
)

private fun synthMvhd(timescale: Long, durationMs: Long): ByteArray {
    val payload = concatBytes(
        listOf(
            int32be(0), int32be(0), int32be(timescale), int32be(durationMs),
            int32be(0x00010000), byteArrayOf(0x01, 0x00, 0x00, 0x00),
            ByteArray(8), unityMatrix(), ByteArray(24), int32be(2),
        ),
    )
    return fullBox("mvhd", payload)
}

private fun synthTkhd(durationMs: Long): ByteArray {
    val payload = concatBytes(
        listOf(
            int32be(0), int32be(0), int32be(1), int32be(0), int32be(durationMs),
            ByteArray(8),
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00),
            unityMatrix(), int32be(0), int32be(0),
        ),
    )
    return box("tkhd", concatBytes(listOf(byteArrayOf(0, 0, 0, 7), payload)))
}

private fun synthMdhd(timescale: Long, duration: Long): ByteArray {
    val payload = concatBytes(
        listOf(
            int32be(0), int32be(0), int32be(timescale), int32be(duration),
            byteArrayOf(0x55, 0xC4.toByte(), 0x00, 0x00),
        ),
    )
    return fullBox("mdhd", payload)
}

private fun synthHdlr(): ByteArray {
    val payload = concatBytes(
        listOf(
            int32be(0), "soun".encodeToByteArray(), ByteArray(12), byteArrayOf(0),
        ),
    )
    return fullBox("hdlr", payload)
}

private fun mp4AudioSampleEntry(sampleType: String, channels: Int, sampleRate: Long, child: ByteArray?): ByteArray {
    val parts = mutableListOf(
        ByteArray(6), byteArrayOf(0x00, 0x01),
        ByteArray(8),
        byteArrayOf((channels shr 8).toByte(), channels.toByte()),
        byteArrayOf(0x00, 0x10),
        byteArrayOf(0x00, 0x00, 0x00, 0x00),
        int32be(sampleRate shl 16),
    )
    if (child != null) parts.add(child)
    return box(sampleType, concatBytes(parts))
}

private fun stsdBox(entry: ByteArray): ByteArray {
    return fullBox("stsd", concatBytes(listOf(int32be(1), entry)))
}

private fun esdsBox(objectType: Int, asc: ByteArray?): ByteArray {
    val decSpecific = if (asc != null) byteArrayOf(0x05.toByte(), asc.size.toByte()) + asc else byteArrayOf(0x05.toByte(), 0x00.toByte())
    val decConfigBody = byteArrayOf(
        objectType.toByte(), 0x15.toByte(),
        0x00.toByte(), 0x02.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
    ) + decSpecific
    val esBody = byteArrayOf(0x00.toByte(), 0x01.toByte(), 0x00.toByte()) +
        byteArrayOf(0x04.toByte(), decConfigBody.size.toByte()) + decConfigBody +
        byteArrayOf(0x06.toByte(), 0x01.toByte(), 0x02.toByte())
    return fullBox("esds", byteArrayOf(0x03.toByte(), esBody.size.toByte()) + esBody)
}

private fun packBits(fields: List<Pair<Int, Int>>): ByteArray {
    var bits = 0L
    var nbits = 0
    for ((v, n) in fields) {
        bits = (bits shl n) or (v.toLong() and ((1L shl n) - 1))
        nbits += n
    }
    val totalBits = (nbits + 7) / 8 * 8
    bits = bits shl (totalBits - nbits)
    val bytes = ByteArray(totalBits / 8)
    for (i in bytes.indices) bytes[i] = (bits shr ((bytes.size - 1 - i) * 8)).toByte()
    return bytes
}

private fun dec3Box(h: Ac3Header, dataRateKbps: Int): ByteArray {
    val payload = packBits(
        listOf(
            Pair(dataRateKbps.coerceAtMost(8191), 13),
            Pair(1, 3),
            Pair(h.fscod, 2),
            Pair(h.bsid, 5),
            Pair(h.bsmod, 3),
            Pair(h.acmod, 3),
            Pair(h.lfeon, 1),
            Pair(0, 3),
            Pair(0, 4),
        ),
    )
    return box("dec3", payload)
}

private fun dac3Box(h: Ac3Header): ByteArray {
    val payload = packBits(
        listOf(
            Pair(h.fscod, 2),
            Pair(h.bsid, 5),
            Pair(h.bsmod, 3),
            Pair(h.acmod, 3),
            Pair(h.lfeon, 1),
            Pair(h.frmsizcod shr 1, 5),
            Pair(0, 5),
        ),
    )
    return box("dac3", payload)
}

private fun dOpsBox(head: OpusHead): ByteArray {
    val base = byteArrayOf(0x00.toByte(), head.channels.toByte()) +
        byteArrayOf((head.preSkip and 0xFF).toByte(), ((head.preSkip shr 8) and 0xFF).toByte()) +
        int32le(head.inputRate) +
        byteArrayOf((head.outputGain and 0xFF).toByte(), ((head.outputGain shr 8) and 0xFF).toByte(), head.mappingFamily.toByte())
    val extra = if (head.mappingFamily != 0) {
        byteArrayOf(head.streamCount.toByte(), head.coupledCount.toByte()) + head.mapping
    } else {
        ByteArray(0)
    }
    return box("dOps", base + extra)
}

private fun mkvStsd(track: MkvTrack, rate: Long, channels: Int, frames: List<MkvFrame>): ByteArray? {
    return when (track.codecId) {
        "A_AAC" -> {
            val asc = track.codecPrivate ?: return null
            stsdBox(mp4AudioSampleEntry("mp4a", channels, rate, esdsBox(0x40, asc)))
        }
        "A_EAC3" -> {
            val h = parseEac3Header(frames.first().bytes) ?: return null
            val avgBytes = frames.map { it.bytes.size }.average()
            val dataRate = (avgBytes * 8.0 * rate.toDouble() / 1536.0 / 1000.0).toInt()
            stsdBox(mp4AudioSampleEntry("ec-3", channels, rate, dec3Box(h, dataRate)))
        }
        "A_AC3" -> {
            val h = parseAc3Header(frames.first().bytes) ?: return null
            stsdBox(mp4AudioSampleEntry("ac-3", channels, rate, dac3Box(h)))
        }
        "A_OPUS" -> {
            val head = track.codecPrivate?.let { parseOpusHead(it) } ?: return null
            stsdBox(mp4AudioSampleEntry("Opus", head.channels, 48000L, dOpsBox(head)))
        }
        "A_MPEG/L3" -> {
            stsdBox(mp4AudioSampleEntry("mp4a", channels, rate, esdsBox(0x69, null)))
        }
        else -> null
    }
}


private suspend fun fetchMkvElementHeader(url: String, headers: Map<String, String>, filePos: Long): EbmlHeader? {
    val bytes = fetchRange(url, headers, filePos, filePos + 15) ?: return null
    return parseEbmlHeader(bytes, 0)
}

private suspend fun fetchRangesParallel(
    url: String,
    headers: Map<String, String>,
    ranges: List<Pair<Long, Long>>,
): List<ByteArray?> {
    val out = mutableListOf<ByteArray?>()
    var i = 0
    while (i < ranges.size) {
        val group = ranges.subList(i, minOf(i + MKV_PARALLELISM, ranges.size))
        val results = coroutineScope {
            group.map { range -> async { fetchRange(url, headers, range.first, range.second - 1) } }.awaitAll()
        }
        out.addAll(results)
        i += MKV_PARALLELISM
    }
    return out
}

private class MkvClusterRange(val fileStart: Long, val fileEnd: Long)

private suspend fun extractWindowMkv(
    sourceUrl: String,
    headers: Map<String, String>,
    windowStartMs: Long,
    windowMs: Long,
): String? {
    val windowEndMs = windowStartMs + windowMs
    val first = fetchRange(sourceUrl, headers, 0, MKV_FIRST_CHUNK_BYTES - 1)
    if (first == null) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv first chunk fetch failed")
        return null
    }
    val ebmlH = parseEbmlHeader(first, 0)
    if (ebmlH == null || ebmlH.id != 0x1A45DFA3L) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv ebml header missing")
        return null
    }
    val ebmlDataEnd = ebmlH.headerLen + if (ebmlH.size < 0) 0 else ebmlH.size.toInt()
    var docType = ""
    forEachEbmlChild(first, ebmlH.headerLen, ebmlDataEnd.coerceAtMost(first.size)) { eid, eds, ede ->
        if (eid == 0x4282L) docType = first.asciiString(eds, ede - eds)
    }
    val segH = parseEbmlHeader(first, ebmlDataEnd)
    if (segH == null || segH.id != 0x18538067L) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv segment header missing")
        return null
    }
    val segDataStart = (ebmlDataEnd + segH.headerLen).toLong()
    var timeScale = 1_000_000L
    var tracksBytes: ByteArray? = null
    var tracksStart = -1
    var tracksEnd = -1
    var seekHead: Map<Long, Long> = emptyMap()
    var firstClusterFilePos: Long = -1
    var chunk: ByteArray = first
    var chunkStart = 0L
    var walkPos = segDataStart
    var walkedChunks = 0
    var elementCount = 0
    walkLoop@ while (walkedChunks <= 8 && elementCount < 1000) {
        while (walkPos < chunkStart || walkPos >= chunkStart + chunk.size) {
            val nextStart = chunkStart + chunk.size
            val next = fetchRange(sourceUrl, headers, nextStart, nextStart + MKV_FIRST_CHUNK_BYTES - 1)
            if (next == null) break@walkLoop
            chunkStart = nextStart
            chunk = next
            walkedChunks++
            if (walkedChunks > 8) break@walkLoop
        }
        val localPos = (walkPos - chunkStart).toInt()
        val hParsed = parseEbmlHeader(chunk, localPos)
        val h = if (hParsed != null) {
            hParsed
        } else {
            val span = fetchRange(sourceUrl, headers, walkPos, walkPos + 31)
            span?.let { parseEbmlHeader(it, 0) } ?: break@walkLoop
        }
        elementCount++
        val dataStart = walkPos + h.headerLen
        val dataEndKnown = h.size >= 0
        val dataEnd = if (dataEndKnown) dataStart + h.size else -1L
        suspend fun payloadBytes(): ByteArray? {
            if (dataEndKnown && dataStart >= chunkStart && dataEnd <= chunkStart + chunk.size) {
                val s = (dataStart - chunkStart).toInt()
                val e = (dataEnd - chunkStart).toInt()
                return chunk.copyOfRange(s, e)
            }
            if (!dataEndKnown) return null
            if (h.size > MAX_MKV_TRACKS_BYTES) return null
            return fetchRange(sourceUrl, headers, dataStart, dataEnd - 1)
        }
        when (h.id) {
            0x1549A966L -> {
                val info = payloadBytes()
                if (info != null) {
                    forEachEbmlChild(info, 0, info.size) { iid, ids, ide ->
                        if (iid == 0x2AD7B1L) timeScale = info.uintBe(ids, ide - ids)
                    }
                }
            }
            0x1654AE6BL -> {
                val tb = payloadBytes()
                if (tb != null) {
                    tracksBytes = tb
                    tracksStart = 0
                    tracksEnd = tb.size
                }
            }
            0x114D9B74L -> {
                val sh = payloadBytes()
                if (sh != null) seekHead = parseMkvSeekHead(sh, 0, sh.size)
            }
            0x1F43B675L -> {
                firstClusterFilePos = walkPos
                break@walkLoop
            }
        }
        if (!dataEndKnown) break@walkLoop
        val nextPos = dataStart + h.size
        if (nextPos <= walkPos) break@walkLoop
        walkPos = nextPos
    }
    val tb = tracksBytes
    if (tb == null) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv tracks missing")
        return null
    }
    val tracksList = parseMkvTracks(tb, tracksStart, tracksEnd)
    val supported = setOf("A_AAC", "A_EAC3", "A_AC3", "A_OPUS", "A_MPEG/L3")
    val audioTracks = tracksList.filter { it.codecId in supported }
    val track = audioTracks.firstOrNull { it.isDefault } ?: audioTracks.firstOrNull()
    if (track == null) {
        val found = tracksList.map { it.codecId }.joinToString(",")
        InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv no supported audio track found=" + found)
        return null
    }
    var cues: List<MkvCue> = emptyList()
    val cuesPos = seekHead[0x1C53BB6BL]
    if (cuesPos != null) {
        val cuesFilePos = segDataStart + cuesPos
        val cuesHeader = fetchMkvElementHeader(sourceUrl, headers, cuesFilePos)
        if (cuesHeader != null && cuesHeader.id == 0x1C53BB6BL && cuesHeader.size >= 0 && cuesHeader.size <= MAX_MKV_CUES_BYTES) {
            val cuesBytes = fetchRange(sourceUrl, headers, cuesFilePos + cuesHeader.headerLen, cuesFilePos + cuesHeader.headerLen + cuesHeader.size - 1)
            if (cuesBytes != null) cues = parseMkvCues(cuesBytes, 0, cuesBytes.size, timeScale)
        }
    }
    InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv track codec=" + track.codecId + " number=" + track.number + " cues=" + cues.size)
    val ranges = mutableListOf<MkvClusterRange>()
    if (cues.isNotEmpty()) {
        var startIdx = cues.indexOfLast { it.timeMs <= windowStartMs }
        if (startIdx < 0) startIdx = 0
        var idx = startIdx
        while (idx < cues.size && cues[idx].timeMs < windowEndMs) {
            val start = segDataStart + cues[idx].clusterPos
            val end = if (idx + 1 < cues.size) segDataStart + cues[idx + 1].clusterPos else -1L
            ranges.add(MkvClusterRange(start, end))
            idx++
        }
        if (ranges.isEmpty()) {
            InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv no cue cluster covers window")
            return null
        }
    } else {
        if (firstClusterFilePos < 0) {
            InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv no cues and no cluster")
            return null
        }
        val scanned = mutableListOf<Triple<Long, Long, Long>>()
        var pos = firstClusterFilePos
        var guard = 0
        var prefixBytes = 0L
        while (guard < 10000) {
            guard++
            val h = fetchMkvElementHeader(sourceUrl, headers, pos) ?: break
            if (h.id != 0x1F43B675L) break
            if (h.size < 0) {
                InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv scan hit unknown-size cluster")
                return null
            }
            val prefix = fetchRange(sourceUrl, headers, pos + h.headerLen, pos + h.headerLen + 1023)
            if (prefix == null) break
            prefixBytes += 16 + 1024
            if (prefixBytes > 1024 * 1024L) {
                InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv scan prefix cap")
                return null
            }
            var ct: Long? = null
            forEachEbmlChild(prefix, 0, prefix.size) { eid, eds, ede ->
                if (eid == 0xE7L) ct = scaleTime(prefix.uintBe(eds, ede - eds), timeScale)
            }
            if (ct == null) break
            scanned.add(Triple(pos, h.headerLen + h.size, ct))
            if (ct >= windowEndMs) break
            pos = pos + h.headerLen + h.size
        }
        for (i in scanned.indices) {
            val ct = scanned[i].third
            val nextCt = if (i + 1 < scanned.size) scanned[i + 1].third else Long.MAX_VALUE
            if (nextCt > windowStartMs && ct < windowEndMs) {
                ranges.add(MkvClusterRange(scanned[i].first, scanned[i].first + scanned[i].second))
            }
        }
        if (ranges.isEmpty()) {
            InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv scan found no window cluster")
            return null
        }
    }
    val knownRanges = ranges.filter { it.fileEnd >= 0 }
    val tailRange = ranges.firstOrNull { it.fileEnd < 0 }
    val knownBufs = fetchRangesParallel(sourceUrl, headers, knownRanges.map { Pair(it.fileStart, it.fileEnd) })
    var fetchedBytes = 0L
    var fetchedSpans = 0
    var tailFetches = 0
    val allFrames = mutableListOf<MkvFrame>()
    for (i in knownRanges.indices) {
        val buf = knownBufs[i]
        if (buf == null) {
            InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv cluster fetch failed idx=" + i)
            return null
        }
        fetchedBytes += buf.size
        fetchedSpans++
        val h = parseEbmlHeader(buf, 0)
        if (h == null || h.id != 0x1F43B675L) {
            InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv cluster header bad idx=" + i)
            return null
        }
        if (!parseMkvClusterPayload(buf, h.headerLen, buf.size, track.number, timeScale, track.sampleRate, track.codecId, allFrames)) {
            InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv cluster parse failed idx=" + i)
            return null
        }
        if (fetchedBytes > MAX_MKV_FETCH_BYTES) {
            InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv fetch cap")
            return null
        }
    }
    if (tailRange != null) {
        val tailFirst = fetchRange(sourceUrl, headers, tailRange.fileStart, tailRange.fileStart + MKV_TAIL_PREFIX_BYTES - 1)
        if (tailFirst == null) {
            InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv tail fetch failed")
            return null
        }
        var tailBuf: ByteArray = tailFirst
        fetchedBytes += tailBuf.size
        fetchedSpans++
        tailFetches++
        var done = false
        var extensions = 0
        while (!done) {
            val h = parseEbmlHeader(tailBuf, 0)
            if (h == null || h.id != 0x1F43B675L) {
                InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv tail header bad")
                return null
            }
            val frames = mutableListOf<MkvFrame>()
            if (!parseMkvClusterPayload(tailBuf, h.headerLen, tailBuf.size, track.number, timeScale, track.sampleRate, track.codecId, frames)) {
                InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv tail parse failed")
                return null
            }
            if (frames.any { it.timeMs >= windowEndMs } || extensions >= 4) {
                allFrames.addAll(frames)
                done = true
            } else {
                val nextStart = tailRange.fileStart + tailBuf.size
                val next = fetchRange(sourceUrl, headers, nextStart, nextStart + MKV_TAIL_PREFIX_BYTES - 1)
                if (next == null) {
                    allFrames.addAll(frames)
                    done = true
                } else {
                    fetchedBytes += next.size
                    fetchedSpans++
                    tailFetches++
                    tailBuf = tailBuf + next
                    extensions++
                }
            }
        }
        if (fetchedBytes > MAX_MKV_FETCH_BYTES) {
            InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv fetch cap tail")
            return null
        }
    }
    val windowFrames = allFrames.filter { it.timeMs >= windowStartMs && it.timeMs < windowEndMs }
    if (windowFrames.isEmpty()) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv no frames in window total=" + allFrames.size)
        return null
    }
    val rateChannels = resolveMkvRateChannels(track, windowFrames.first().bytes)
    if (rateChannels == null) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv rate resolve failed")
        return null
    }
    val rate = rateChannels.first
    val channels = rateChannels.second
    val lastFrame = windowFrames.last()
    val lastDur = codecFrameSamples(track.codecId, lastFrame.bytes)
    val lastDurMs = if (lastDur > 0) (lastDur * 1000.0 / rate.toDouble()).toLong() else 0L
    val coverageMs = (lastFrame.timeMs + lastDurMs) - windowFrames.first().timeMs
    if (coverageMs < 30_000L) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv window coverage short coverageMs=" + coverageMs)
        return null
    }
    val stsd = mkvStsd(track, rate, channels, windowFrames)
    if (stsd == null) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv stsd failed codec=" + track.codecId)
        return null
    }
    val deltas = mutableListOf<Long>()
    val sizes = mutableListOf<Int>()
    for (f in windowFrames) {
        val s = codecFrameSamples(track.codecId, f.bytes)
        if (s < 0) {
            InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv frame duration unknown")
            return null
        }
        deltas.add(s.toLong())
        sizes.add(f.bytes.size)
    }
    val sttsEntries = mutableListOf<Pair<Long, Long>>()
    for (d in deltas) {
        val last = sttsEntries.lastOrNull()
        if (last != null && last.second == d) sttsEntries[sttsEntries.size - 1] = Pair(last.first + 1, d)
        else sttsEntries.add(Pair(1, d))
    }
    val deltaSum = deltas.sum()
    val ftypBytes = box("ftyp", concatBytes(listOf("isom".encodeToByteArray(), int32be(0), "isom".encodeToByteArray(), "iso2".encodeToByteArray())))
    val mvhdSynth = synthMvhd(1000L, windowMs)
    val tkhdSynth = synthTkhd(windowMs)
    val mdhdSynth = synthMdhd(rate, deltaSum)
    val hdlrSynth = synthHdlr()
    val moovPass1 = buildMoov(mvhdSynth, tkhdSynth, mdhdSynth, hdlrSynth, null, null, stsd, sttsEntries, sizes, 1000L, windowMs, deltaSum, 0L)
    val stcoValue = ftypBytes.size + moovPass1.size + 8L
    val moovFinal = buildMoov(mvhdSynth, tkhdSynth, mdhdSynth, hdlrSynth, null, null, stsd, sttsEntries, sizes, 1000L, windowMs, deltaSum, stcoValue)
    val fileBytes = ftypBytes + moovFinal + box("mdat", concatBytes(windowFrames.map { it.bytes }))
    val tmpDir = NSTemporaryDirectory() + "probe-audio-" + NSUUID().UUIDString + "/"
    if (!NSFileManager.defaultManager.createDirectoryAtPath(tmpDir, withIntermediateDirectories = true, attributes = null, error = null)) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv tmpdir failed")
        return null
    }
    val path = tmpDir + "window.mp4"
    if (!fileBytes.writeToSegmentFile(path)) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv write failed")
        return null
    }
    InAppLogger.debug("Player/ProbeAudioReader", "extract: mkv doctype=" + docType + " codec=" + track.codecId + " rate=" + rate + " clusters=" + ranges.size + " spans=" + fetchedSpans + " knownSpans=" + knownRanges.size + " tailSpans=" + tailFetches + " spanBytes=" + fetchedBytes + " frames=" + windowFrames.size)
    return path
}

private suspend fun extractWindowForContainer(
    sourceUrl: String,
    headers: Map<String, String>,
    windowStartMs: Long,
    windowMs: Long,
): String? {
    val probe = fetchRange(sourceUrl, headers, 0, 63)
    if (probe != null && probe.size >= 4 &&
        (probe[0].toInt() and 0xFF) == 0x1A && (probe[1].toInt() and 0xFF) == 0x45 &&
        (probe[2].toInt() and 0xFF) == 0xDF && (probe[3].toInt() and 0xFF) == 0xA3) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: container ebml")
        return extractWindowMkv(sourceUrl, headers, windowStartMs, windowMs)
    }
    return extractWindowMp4(sourceUrl, headers, windowStartMs, windowMs)
}

// Builds a local mp4 holding only the probe window's audio samples, or null.
private suspend fun extractWindowMp4(
    sourceUrl: String,
    headers: Map<String, String>,
    windowStartMs: Long,
    windowMs: Long,
): String? {
    val (ftypBox, moovBox) = fetchMoovAndFtyp(sourceUrl, headers)
    if (moovBox == null) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: moov fetch failed")
        return null
    }
    val parsed = parseAudioTrack(moovBox)
    if (parsed == null) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: audio trak parse failed")
        return null
    }
    val track = parsed.track
    val wStart = windowStartMs * track.timescale / 1000
    val wEnd = (windowStartMs + windowMs) * track.timescale / 1000
    val selected = mutableListOf<Int>()
    val selectedDeltas = mutableListOf<Long>()
    var t = 0L
    var idx = 0L
    for ((count, delta) in track.stts) {
        for (k in 0 until count) {
            val s = t
            val e = t + delta
            if (e > wStart && s < wEnd) {
                selected.add(idx.toInt())
                selectedDeltas.add(delta)
            }
            t = e
            idx++
        }
    }
    if (selected.isEmpty()) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: no samples in window")
        return null
    }
    val ranges = mutableListOf<Pair<Long, Long>>()
    var totalBytes = 0L
    for (s in selected) {
        val (chunk, firstInChunk) = chunkForSample(track.stsc, s.toLong())
        if (chunk < 1 || chunk > track.chunkOffsets.size) {
            InAppLogger.debug("Player/ProbeAudioReader", "extract: chunk map failed sample=${s} chunk=${chunk}")
            return null
        }
        var off = track.chunkOffsets[(chunk - 1).toInt()]
        for (i in firstInChunk until s.toLong()) off += track.sampleSizes[i.toInt()]
        val size = track.sampleSizes[s].toLong()
        totalBytes += size
        if (totalBytes > MAX_WINDOW_BYTES) {
            InAppLogger.debug("Player/ProbeAudioReader", "extract: window bytes cap total=${totalBytes}")
            return null
        }
        val start = off
        val end = off + size
        val last = ranges.lastOrNull()
        if (last != null && last.second == start) {
            ranges[ranges.size - 1] = Pair(last.first, end)
        } else {
            ranges.add(Pair(start, end))
        }
    }
    // Merge nearby ranges into spans: one fetch per span instead of one per
    // chunk. Files with thousands of tiny chunks (e.g. 2814 ranges for a 60s
    // window) took minutes fetching sequentially; merged spans fetch the
    // same bytes in a handful of requests. A span never exceeds
    // MAX_SPAN_BYTES; if ranges are not in ascending file order, fall back
    // to per-range fetching (original behavior).
    var ascending = true
    for (i in 1 until ranges.size) {
        if (ranges[i].first < ranges[i - 1].first) {
            ascending = false
            break
        }
    }
    val spans = mutableListOf<Pair<Long, Long>>()
    if (ascending) {
        for ((start, end) in ranges) {
            val last = spans.lastOrNull()
            if (last != null && start - last.second <= MERGE_GAP_BYTES && end - last.first <= MAX_SPAN_BYTES) {
                spans[spans.size - 1] = Pair(last.first, maxOf(last.second, end))
            } else {
                spans.add(Pair(start, end))
            }
        }
    } else {
        spans.addAll(ranges)
    }
    if (spans.size > MAX_WINDOW_RANGES) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: spans cap spans=${spans.size}")
        return null
    }
    val spanBytesTotal = spans.sumOf { it.second - it.first }
    InAppLogger.debug("Player/ProbeAudioReader", "extract: ranges=${ranges.size} spans=${spans.size} spanBytes=${spanBytesTotal} bytes=${totalBytes}")
    val sampleData = ByteArray(totalBytes.toInt())
    var pos = 0
    var rangeIdx = 0
    for ((spanStart, spanEnd) in spans) {
        val spanBytes = fetchRange(sourceUrl, headers, spanStart, spanEnd - 1)
        if (spanBytes == null) {
            InAppLogger.debug("Player/ProbeAudioReader", "extract: span fetch failed start=${spanStart} end=${spanEnd}")
            return null
        }
        while (rangeIdx < ranges.size) {
            val (start, end) = ranges[rangeIdx]
            if (start < spanStart || end > spanEnd) {
                break
            }
            spanBytes.copyInto(sampleData, pos, (start - spanStart).toInt(), (end - spanStart).toInt())
            pos += (end - start).toInt()
            rangeIdx++
        }
    }
    if (rangeIdx != ranges.size) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: span coverage mismatch covered=${rangeIdx} ranges=${ranges.size}")
        return null
    }
    if (pos != sampleData.size) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: sample size mismatch pos=${pos} total=${sampleData.size}")
        return null
    }
    val sttsEntries = mutableListOf<Pair<Long, Long>>()
    for (d in selectedDeltas) {
        val last = sttsEntries.lastOrNull()
        if (last != null && last.second == d) {
            sttsEntries[sttsEntries.size - 1] = Pair(last.first + 1, d)
        } else {
            sttsEntries.add(Pair(1, d))
        }
    }
    val sampleSizes = selected.map { track.sampleSizes[it] }
    val selectedDeltaSum = selectedDeltas.sum()
    val ftypBytes = ftypBox ?: box(
        "ftyp",
        concatBytes(
            listOf(
                "isom".encodeToByteArray(),
                int32be(0),
                "isom".encodeToByteArray(),
                "iso2".encodeToByteArray(),
            ),
        ),
    )
    val moovPass1 = buildMoov(
        mvhdOrig = parsed.mvhd,
        tkhdOrig = track.tkhd,
        mdhdOrig = track.mdhd,
        hdlr = track.hdlr,
        smhd = track.smhd,
        dinf = track.dinf,
        stsd = track.stsd,
        sttsEntries = sttsEntries,
        sampleSizes = sampleSizes,
        movieTimescale = parsed.movieTimescale,
        windowMs = windowMs,
        selectedDeltaSum = selectedDeltaSum,
        stcoValue = 0,
    )
    val stcoValue = ftypBytes.size + moovPass1.size + 8
    val moovFinal = buildMoov(
        mvhdOrig = parsed.mvhd,
        tkhdOrig = track.tkhd,
        mdhdOrig = track.mdhd,
        hdlr = track.hdlr,
        smhd = track.smhd,
        dinf = track.dinf,
        stsd = track.stsd,
        sttsEntries = sttsEntries,
        sampleSizes = sampleSizes,
        movieTimescale = parsed.movieTimescale,
        windowMs = windowMs,
        selectedDeltaSum = selectedDeltaSum,
        stcoValue = stcoValue.toLong(),
    )
    val fileBytes = concatBytes(listOf(ftypBytes, moovFinal, box("mdat", sampleData)))
    val dir = NSTemporaryDirectory() + "probe-audio-" + NSUUID().UUIDString + "/"
    if (!NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: temp dir failed")
        return null
    }
    val path = dir + "window.mp4"
    if (!fileBytes.writeToSegmentFile(path)) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: file write failed")
        return null
    }
    return path
}


private suspend fun readRemoteFileIntoBins(
    sourceUrl: String,
    headers: Map<String, String>,
    windowStartMs: Long,
    windowMs: Long,
    binCount: Int,
    sumSq: DoubleArray,
    counts: IntArray,
): Boolean {
    val windowPath = extractWindowForContainer(sourceUrl, headers, windowStartMs, windowMs)
    if (windowPath == null) {
        InAppLogger.warn("Player/ProbeAudioReader", "window extract failed")
        return false
    }
    val windowDir = windowPath.substringBeforeLast("/") + "/"
    try {
        val nsUrl = NSURL(string = "file://$windowPath") ?: return false
        val asset = AVURLAsset(uRL = nsUrl, options = null)
        val ok = readAssetBins(
            asset = asset,
            timeRange = null,
            absolutePts = false,
            baseMs = windowStartMs,
            windowStartMs = windowStartMs,
            binCount = binCount,
            sumSq = sumSq,
            counts = counts,
        )
        if (!ok) {
            InAppLogger.warn("Player/ProbeAudioReader", "window decode failed")
        }
        return ok
    } finally {
        NSFileManager.defaultManager.removeItemAtPath(windowDir, error = null)
    }
}

private suspend fun fetchDataParallel(urls: List<String>, headers: Map<String, String>): List<ByteArray?> {
    val out = mutableListOf<ByteArray?>()
    var i = 0
    while (i < urls.size) {
        val group = urls.subList(i, minOf(i + MKV_PARALLELISM, urls.size))
        val results = coroutineScope {
            group.map { url -> async { fetchData(url, headers) } }.awaitAll()
        }
        out.addAll(results)
        i += MKV_PARALLELISM
    }
    return out
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
        val fetched = fetchDataParallel(needed.map { it.url }, headers)
        var anySegment = false
        var fetchedBytes = 0L
        for ((index, segment) in needed.withIndex()) {
            val data = fetched[index] ?: continue
            fetchedBytes += data.size
            val path = tmpDir + "seg-$index.ts"
            if (!data.writeToSegmentFile(path)) continue
            val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
            if (readAssetBins(asset, null, false, segment.startMs, windowStartMs, binCount, sumSq, counts)) {
                anySegment = true
            }
        }
        InAppLogger.debug("Player/ProbeAudioReader", "extract: hls segments=" + needed.size + " fetched=" + fetched.count { it != null } + " segmentBytes=" + fetchedBytes)
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
    if (!asset.awaitTracks()) {
        InAppLogger.debug("Player/ProbeAudioReader", "readAssetBins failed: awaitTracks")
        return false
    }
    val outputSettings: Map<Any?, Any?> = mapOf(
        AV_FORMAT_ID_KEY to NSNumber(unsignedInt = LPCM_FORMAT_ID),
        AV_SAMPLE_RATE_KEY to PROBE_SAMPLE_RATE,
        AV_NUMBER_OF_CHANNELS_KEY to 1,
        AV_LINEAR_PCM_BIT_DEPTH_KEY to 16,
        AV_LINEAR_PCM_IS_FLOAT_KEY to false,
    )
    // AVAsset.tracks and AVAssetTrack.mediaType are not exposed to
    // Kotlin/Native, and NSObject KVC (valueForKey) is not either
    // (round 3 failed on both). Fetch both via core NSObject message
    // dispatch (performSelector) instead, after awaitTracks() loads
    // the "tracks" key above.
    val tracksSel = NSSelectorFromString("tracks") ?: return false
    val mediaTypeSel = NSSelectorFromString("mediaType") ?: return false
    val audioTracks = (asset.performSelector(tracksSel) as? List<*>)
        .orEmpty()
        .filterIsInstance<AVAssetTrack>()
        .filter { (it.performSelector(mediaTypeSel) as? String) == AVMediaTypeAudio }
    if (audioTracks.isEmpty()) {
        InAppLogger.debug("Player/ProbeAudioReader", "readAssetBins failed: no audio tracks")
        return false
    }
    val output = AVAssetReaderAudioMixOutput(audioTracks, outputSettings)
    val reader = memScoped {
        val error = alloc<ObjCObjectVar<platform.Foundation.NSError?>>()
        AVAssetReader(asset, error.ptr)
    }
    if (reader == null) {
        InAppLogger.debug("Player/ProbeAudioReader", "readAssetBins failed: reader null")
        return false
    }
    if (!reader.canAddOutput(output)) {
        InAppLogger.debug("Player/ProbeAudioReader", "readAssetBins failed: canAddOutput")
        return false
    }
    reader.addOutput(output)
    if (timeRange != null) {
        reader.timeRange = timeRange
    }
    if (!reader.startReading()) {
        InAppLogger.debug("Player/ProbeAudioReader", "readAssetBins failed: startReading")
        return false
    }
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
            val dataPointer = alloc<CPointerVar<ByteVar>>()
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
    if (!anySample) {
        InAppLogger.debug("Player/ProbeAudioReader", "readAssetBins failed: no samples")
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

private suspend fun fetchData(url: String, headers: Map<String, String>): ByteArray? {
    repeat(3) { attempt ->
        try {
            val response = probeHttpClient.get(url) {
                headers.forEach { (key, value) -> header(key, value) }
            }
            if (response.status.isSuccess()) return response.body<ByteArray>()
        } catch (_: Exception) {
        }
        if (attempt < 2) delay(1000)
    }
    return null
}

private suspend fun fetchText(url: String, headers: Map<String, String>): String? =
    fetchData(url, headers)?.decodeToString()

private fun ByteArray.writeToSegmentFile(path: String): Boolean {
    val file = fopen(path, "wb") ?: return false
    return try {
        if (isEmpty()) return true
        usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1.convert(), size.convert(), file).toLong() == size.toLong()
        }
    } finally {
        fclose(file)
    }
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

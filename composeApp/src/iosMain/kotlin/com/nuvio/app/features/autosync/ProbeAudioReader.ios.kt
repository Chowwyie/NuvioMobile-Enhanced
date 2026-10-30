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
// kind) so device failures can be told apart. Never logs the URL.
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
    val response = probeHttpClient.get(url) {
        headers.forEach { (key, value) -> header(key, value) }
        header("Range", "bytes=$start-$endInclusive")
    }
    if (!response.status.isSuccess()) {
        InAppLogger.debug("Player/ProbeAudioReader", "fetchRange: attempt ${attempt} status=${response.status.value} start=${start}")
        null
    } else {
        val bytes = response.body<ByteArray>()
        val expected = endInclusive - start + 1
        if (bytes.size.toLong() == expected) {
            bytes
        } else {
            InAppLogger.debug("Player/ProbeAudioReader", "fetchRange: attempt ${attempt} size mismatch expected=${expected} got=${bytes.size} start=${start}")
            null
        }
    }
} catch (e: Exception) {
    InAppLogger.debug("Player/ProbeAudioReader", "fetchRange: attempt ${attempt} exception kind=${e::class.simpleName} start=${start}")
    null
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
    if (ranges.size > MAX_WINDOW_RANGES) {
        InAppLogger.debug("Player/ProbeAudioReader", "extract: ranges cap ranges=${ranges.size}")
        return null
    }
    InAppLogger.debug("Player/ProbeAudioReader", "extract: ranges=${ranges.size} bytes=${totalBytes}")
    val sampleData = ByteArray(totalBytes.toInt())
    var pos = 0
    for ((start, end) in ranges) {
        val bytes = fetchRange(sourceUrl, headers, start, end - 1)
        if (bytes == null) {
            InAppLogger.debug("Player/ProbeAudioReader", "extract: sample range fetch failed start=${start} end=${end}")
            return null
        }
        bytes.copyInto(sampleData, pos)
        pos += bytes.size
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
    val windowPath = extractWindowMp4(sourceUrl, headers, windowStartMs, windowMs)
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
            if (!data.writeToSegmentFile(path)) continue
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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package probegate

import com.nuvio.app.features.autosync.ProbeAutoSyncAligner
import com.nuvio.app.features.autosync.ProbeAudioEnvelope
import com.nuvio.app.features.autosync.readProbeAudioEnvelope
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.player.SubtitleSyncCue
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderAudioMixOutput
import platform.AVFoundation.AVAssetReaderStatusReading
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVKeyValueStatusLoaded
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVURLAsset
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeRangeMake
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSURL
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.coroutines.resume
import kotlin.system.exitProcess
import kotlinx.cinterop.toKString
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSDate
import platform.posix.getenv

// Real public stream, 596s, so the gate uses the probe's real 180-240s window.
private const val STREAM_URL = "https://archive.org/download/BigBuckBunny_328/BigBuckBunny_512kb.mp4"
private const val HLS_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
private const val WINDOW_START_MS = 180_000L
private const val WINDOW_MS = 60_000L
private const val AV_URL_ASSET_HTTP_HEADER_FIELDS_KEY = "AVURLAssetHTTPHeaderFieldsKey"

// Modeled device time for the 5s perf gate. The gate runner's network is
// datacenter-fast, so wall time there proves nothing about a phone; the
// model charges every fetched byte at a normal (not optimal) 25 Mbps plus
// 250ms per request round, with 16-way parallelism where the reader fetches
// in parallel. MKV tail fetches are sequential, so they pay per fetch.
private const val MODEL_BYTES_PER_SEC = 3_125_000.0
private const val MODEL_ROUND_MS = 250.0
private const val MODEL_PARALLELISM = 16L

private fun modeledMs(bytes: Long, requests: Long): Double {
    val rounds = (requests + MODEL_PARALLELISM - 1) / MODEL_PARALLELISM
    return bytes / MODEL_BYTES_PER_SEC * 1000.0 + rounds * MODEL_ROUND_MS
}

private fun latestMetricsSince(mark: Int, prefix: String): String? {
    for (i in InAppLogger.lines.size - 1 downTo mark) {
        val line = InAppLogger.lines[i]
        if (line.contains(prefix)) return line
    }
    return null
}

private fun parseLongAfter(line: String, key: String): Long? {
    val idx = line.indexOf(key)
    if (idx < 0) return null
    val rest = line.substring(idx + key.length)
    val num = rest.takeWhile { it.isDigit() }
    return num.toLongOrNull()
}

private fun checkEnvelope(name: String, env: ProbeAudioEnvelope, windowStartMs: Long, windowMs: Long, expectedSpeech: Int, tolerance: Int): BooleanArray {
    val rms = env.rms
    val speech = ProbeAutoSyncAligner.speechEnvelope(rms)
    val speechCount = speech.count { it }
    println("GATE $name bins=${rms.size} speechBins=$speechCount")
    if (rms.size != (windowMs / 100).toInt()) {
        println("GATE RESULT: FAIL $name bins")
        exitProcess(1)
    }
    if (speechCount < rms.size * 0.15) {
        println("GATE RESULT: FAIL $name speech floor")
        exitProcess(1)
    }
    if (speechCount > expectedSpeech + tolerance || speechCount < expectedSpeech - tolerance) {
        println("GATE RESULT: FAIL $name speech mismatch expected=$expectedSpeech got=$speechCount")
        exitProcess(1)
    }
    val plain = cuesFromSpeech(speech, windowStartMs, 0L)
    val delay0 = ProbeAutoSyncAligner.bestDelayMs(speech, ProbeAutoSyncAligner.cueEnvelope(plain, windowStartMs, rms.size))
    println("GATE $name unshifted expected=0 got=$delay0")
    if (delay0 != 0) {
        println("GATE RESULT: FAIL $name unshifted")
        exitProcess(1)
    }
    val shifted = cuesFromSpeech(speech, windowStartMs, -2700L)
    val delayShift = ProbeAutoSyncAligner.bestDelayMs(speech, ProbeAutoSyncAligner.cueEnvelope(shifted, windowStartMs, rms.size))
    println("GATE $name shifted expected=2700 got=$delayShift")
    if (delayShift != 2700) {
        println("GATE RESULT: FAIL $name shifted")
        exitProcess(1)
    }
    return speech
}

private suspend fun runFormatTest(name: String, url: String, windowStartMs: Long, windowMs: Long, expectedSpeech: Int, tolerance: Int, expectedCodec: String): Int {
    val mark = InAppLogger.lines.size
    val env = try {
        readProbeAudioEnvelope(url, emptyMap(), windowStartMs, windowMs)
    } catch (e: Exception) {
        println("GATE $name threw ${e::class.simpleName}: ${e.message}")
        null
    }
    if (env == null) {
        println("GATE RESULT: FAIL $name envelope null")
        exitProcess(1)
    }
    val speech = checkEnvelope(name, env, windowStartMs, windowMs, expectedSpeech, tolerance)
    val metrics = latestMetricsSince(mark, "extract: mkv ")
    if (metrics == null || !metrics.contains("codec=$expectedCodec")) {
        println("GATE RESULT: FAIL $name metrics/codec metrics=$metrics")
        exitProcess(1)
    }
    val knownSpans = parseLongAfter(metrics, "knownSpans=")
    val tailSpans = parseLongAfter(metrics, "tailSpans=")
    val spanBytes = parseLongAfter(metrics, "spanBytes=")
    if (knownSpans == null || tailSpans == null || spanBytes == null) {
        println("GATE RESULT: FAIL $name perf metrics unparsable metrics=$metrics")
        exitProcess(1)
    }
    val knownRounds = (knownSpans + MODEL_PARALLELISM - 1) / MODEL_PARALLELISM
    val modeled = spanBytes / MODEL_BYTES_PER_SEC * 1000.0 + knownRounds * MODEL_ROUND_MS + tailSpans * MODEL_ROUND_MS
    println("GATE $name modeled=${modeled}ms knownSpans=$knownSpans tailSpans=$tailSpans spanBytes=$spanBytes")
    if (modeled > 5000.0) {
        println("GATE RESULT: FAIL $name modeled over 5s")
        exitProcess(1)
    }
    return speech.count { it }
}

private suspend fun runHlsTest(name: String, url: String, windowStartMs: Long, windowMs: Long, expectedSpeech: Int, tolerance: Int) {
    val mark = InAppLogger.lines.size
    val env = try {
        readProbeAudioEnvelope(url, emptyMap(), windowStartMs, windowMs)
    } catch (e: Exception) {
        println("GATE $name threw ${e::class.simpleName}: ${e.message}")
        null
    }
    if (env == null) {
        println("GATE RESULT: FAIL $name envelope null")
        exitProcess(1)
    }
    checkEnvelope(name, env, windowStartMs, windowMs, expectedSpeech, tolerance)
    val metrics = latestMetricsSince(mark, "extract: hls ")
    if (metrics == null) {
        println("GATE RESULT: FAIL $name no hls metrics")
        exitProcess(1)
    }
    val segments = parseLongAfter(metrics, "segments=")
    val segmentBytes = parseLongAfter(metrics, "segmentBytes=")
    if (segments == null || segmentBytes == null) {
        println("GATE RESULT: FAIL $name hls metrics unparsable metrics=$metrics")
        exitProcess(1)
    }
    val modeled = modeledMs(segmentBytes, segments)
    println("GATE $name modeled=${modeled}ms segments=$segments segmentBytes=$segmentBytes")
    if (modeled > 5000.0) {
        println("GATE RESULT: FAIL $name modeled over 5s")
        exitProcess(1)
    }
}

private fun sumMetricSince(mark: Int, prefix: String, key: String): Long {
    var total = 0L
    for (i in mark until InAppLogger.lines.size) {
        val line = InAppLogger.lines[i]
        if (line.contains(prefix)) {
            parseLongAfter(line, key)?.let { total += it }
        }
    }
    return total
}

private suspend fun runSparseSideBySide(name: String, url: String, isMkv: Boolean) {
    val wideEnv = try {
        readProbeAudioEnvelope(url, emptyMap(), 60_000, 240_000)
    } catch (e: Exception) {
        println("GATE sparse-$name scaffolding threw ${e::class.simpleName}")
        null
    }
    if (wideEnv == null) {
        println("GATE RESULT: FAIL sparse-$name scaffolding envelope null")
        exitProcess(1)
    }
    val wideSpeech = ProbeAutoSyncAligner.speechEnvelope(wideEnv.rms.take(2400))
    val cuesW = cuesFromSpeech(wideSpeech, 60_000, 0L)
    val cuesWShifted = cuesFromSpeech(wideSpeech, 60_000, -2700L)
    val chunks = ProbeAutoSyncAligner.pickSparseChunks(cuesW)
    println("GATE sparse-$name picked=$chunks")
    if (chunks.size < 2) {
        println("GATE RESULT: FAIL sparse-$name chunks=${chunks.size}")
        exitProcess(1)
    }
    val chunkBins = 50
    val (spanStart, spanBins) = ProbeAutoSyncAligner.sparseSpan(chunks)
    val spanEnd = spanStart + spanBins * 100L
    val contigMark = InAppLogger.lines.size
    val contigEnv = try {
        readProbeAudioEnvelope(url, emptyMap(), WINDOW_START_MS, WINDOW_MS)
    } catch (e: Exception) {
        println("GATE sparse-$name contiguous threw ${e::class.simpleName}")
        null
    }
    if (contigEnv == null) {
        println("GATE RESULT: FAIL sparse-$name contiguous envelope null")
        exitProcess(1)
    }
    val contigModeled: Double
    if (isMkv) {
        val contigMetrics = latestMetricsSince(contigMark, "extract: mkv ")
        val known = parseLongAfter(contigMetrics ?: "", "knownSpans=")
        val tail = parseLongAfter(contigMetrics ?: "", "tailSpans=")
        val bytes = parseLongAfter(contigMetrics ?: "", "spanBytes=")
        if (known == null || tail == null || bytes == null) {
            println("GATE RESULT: FAIL sparse-$name contiguous mkv metrics metrics=$contigMetrics")
            exitProcess(1)
        }
        val knownRounds = (known + MODEL_PARALLELISM - 1) / MODEL_PARALLELISM
        contigModeled = bytes / MODEL_BYTES_PER_SEC * 1000.0 + knownRounds * MODEL_ROUND_MS + tail * MODEL_ROUND_MS
    } else {
        val contigMetrics = latestMetricsSince(contigMark, "extract: ranges=")
        val spans = parseLongAfter(contigMetrics ?: "", "spans=")
        val bytes = parseLongAfter(contigMetrics ?: "", "spanBytes=")
        if (spans == null || bytes == null) {
            println("GATE RESULT: FAIL sparse-$name contiguous mp4 metrics metrics=$contigMetrics")
            exitProcess(1)
        }
        contigModeled = modeledMs(bytes, spans)
    }
    val sparseMark = InAppLogger.lines.size
    val speech = BooleanArray(spanBins)
    val known = BooleanArray(spanBins)
    var readChunks = 0
    for (chunkStart in chunks) {
        val chunkEnv = try {
            readProbeAudioEnvelope(url, emptyMap(), chunkStart, 5_000)
        } catch (e: Exception) {
            println("GATE sparse-$name chunk $chunkStart threw ${e::class.simpleName}")
            null
        }
        if (chunkEnv == null) {
            println("GATE sparse-$name chunk $chunkStart envelope null")
            continue
        }
        val chunkSpeech = ProbeAutoSyncAligner.speechEnvelope(chunkEnv.rms.take(chunkBins))
        val wideIdx = ((chunkStart - 60_000L) / 100L).toInt()
        val wideCount = (0 until chunkBins).count { wideIdx + it in wideSpeech.indices && wideSpeech[wideIdx + it] }
        println("GATE sparse-$name chunk $chunkStart rms=${chunkEnv.rms.size} speechBins=${chunkSpeech.count { it }} wideSpeechBins=$wideCount")
        val offset = ((chunkStart - spanStart) / 100L).toInt()
        for (b in chunkSpeech.indices) {
            if (offset + b < spanBins) {
                speech[offset + b] = chunkSpeech[b]
                known[offset + b] = true
            }
        }
        readChunks++
    }
    if (readChunks < 2) {
        println("GATE RESULT: FAIL sparse-$name chunks read=$readChunks")
        exitProcess(1)
    }
    val sparseModeled: Double
    val chunkBytes: Long
    if (isMkv) {
        val totalKnown = sumMetricSince(sparseMark, "extract: mkv ", "knownSpans=")
        val totalTail = sumMetricSince(sparseMark, "extract: mkv ", "tailSpans=")
        chunkBytes = sumMetricSince(sparseMark, "extract: mkv ", "spanBytes=")
        val knownRounds = (totalKnown + MODEL_PARALLELISM - 1) / MODEL_PARALLELISM
        sparseModeled = chunkBytes / MODEL_BYTES_PER_SEC * 1000.0 + knownRounds * MODEL_ROUND_MS + totalTail * MODEL_ROUND_MS
    } else {
        val totalSpans = sumMetricSince(sparseMark, "extract: ranges=", "spans=")
        chunkBytes = sumMetricSince(sparseMark, "extract: ranges=", "spanBytes=")
        sparseModeled = modeledMs(chunkBytes, totalSpans)
    }
    val cueEnv = ProbeAutoSyncAligner.cueEnvelope(cuesW, spanStart, spanBins)
    val delay0 = ProbeAutoSyncAligner.bestDelayMsSparse(speech, known, cueEnv)
    val cueEnvShifted = ProbeAutoSyncAligner.cueEnvelope(cuesWShifted, spanStart, spanBins)
    val delayShift = ProbeAutoSyncAligner.bestDelayMsSparse(speech, known, cueEnvShifted)
    println("GATE sparse-$name unshifted expected=0 got=$delay0 shifted expected=2700 got=$delayShift chunks=$readChunks spanMs=${spanEnd - spanStart}")
    val scoreAt = { lagMs: Int, cueArr: BooleanArray ->
        val lagBins = lagMs / 100
        var s = 0
        for (i in speech.indices) {
            if (!known[i]) continue
            val ci = i - lagBins
            if (ci in cueArr.indices && speech[i] && cueArr[ci]) s++
        }
        s
    }
    println("GATE sparse-$name scores shifted best=$delayShift score=${scoreAt(delayShift ?: 0, cueEnvShifted)} expected2700=${scoreAt(2700, cueEnvShifted)} unshifted best=$delay0 score=${scoreAt(delay0 ?: 0, cueEnv)}")
    if (delay0 != 0 || delayShift != 2700) {
        println("GATE RESULT: FAIL sparse-$name alignment")
        exitProcess(1)
    }
    println("GATE sparse-$name modeled=${sparseModeled}ms chunkBytes=$chunkBytes contiguousModeled=${contigModeled}ms")
    if (sparseModeled >= contigModeled) {
        println("GATE RESULT: FAIL sparse-$name modeled not below contiguous")
        exitProcess(1)
    }
}

fun main() = runBlocking {
    println("GATE direct-file start url=$STREAM_URL window=$WINDOW_START_MS..${WINDOW_START_MS + WINDOW_MS}")
    val env = try {
        readProbeAudioEnvelope(STREAM_URL, emptyMap(), WINDOW_START_MS, WINDOW_MS)
    } catch (e: Exception) {
        println("GATE envelope threw ${e::class.simpleName}: ${e.message}")
        null
    }
    if (env == null) {
        println("GATE envelope NULL, running stage diagnostics")
        try {
            runStageDiagnostics(STREAM_URL, WINDOW_START_MS, WINDOW_MS)
        } catch (e: Exception) {
            println("GATE diagnostics threw simple=${e::class.simpleName} msg=${e.message}")
        }
        println("GATE local-file test: downloading stream")
        val localDir = NSTemporaryDirectory() + "probe-gate-local-" + NSUUID().UUIDString + "/"
        if (!NSFileManager.defaultManager.createDirectoryAtPath(localDir, withIntermediateDirectories = true, attributes = null, error = null)) {
            println("GATE local temp dir failed")
        } else {
            val localPath = localDir + "probe.mp4"
            val bytes = downloadBytes(STREAM_URL)
            if (bytes == null || !bytes.writeToLocalFile(localPath)) {
                println("GATE local download/write failed")
            } else {
                println("GATE local-file diagnostics url=file://$localPath")
                try {
                    runStageDiagnostics("file://$localPath", WINDOW_START_MS, WINDOW_MS)
                } catch (e: Exception) {
                    println("GATE local diagnostics threw simple=${e::class.simpleName} msg=${e.message}")
                }
            }
            NSFileManager.defaultManager.removeItemAtPath(localDir, error = null)
        }
        println("GATE RESULT: FAIL direct envelope null")
        exitProcess(1)
    }
    val rms = env.rms
    val speech = ProbeAutoSyncAligner.speechEnvelope(rms)
    val speechCount = speech.count { it }
    val maxRms = rms.maxOrNull() ?: 0.0
    println("GATE envelope bins=${rms.size} nonzero=${rms.count { it > 0.0 }} maxRms=$maxRms speechBins=$speechCount")
    if (speechCount < rms.size * 0.15) {
        println("GATE RESULT: FAIL speech floor")
        exitProcess(1)
    }
    val plain = cuesFromSpeech(speech, WINDOW_START_MS, 0L)
    val delay0 = ProbeAutoSyncAligner.bestDelayMs(
        speech,
        ProbeAutoSyncAligner.cueEnvelope(plain, WINDOW_START_MS, rms.size)
    )
    println("GATE unshifted expected=0 got=$delay0")
    if (delay0 != 0) {
        println("GATE RESULT: FAIL unshifted alignment")
        exitProcess(1)
    }
    val shifted = cuesFromSpeech(speech, WINDOW_START_MS, -2700L)
    val delayShift = ProbeAutoSyncAligner.bestDelayMs(
        speech,
        ProbeAutoSyncAligner.cueEnvelope(shifted, WINDOW_START_MS, rms.size)
    )
    println("GATE shifted expected=2700 got=$delayShift")
    if (delayShift != 2700) {
        println("GATE RESULT: FAIL shifted alignment")
        exitProcess(1)
    }
    val directSpeech = speechCount
    val directMetrics = latestMetricsSince(0, "extract: ranges=")
    if (directMetrics == null) {
        println("GATE RESULT: FAIL direct perf metrics missing")
        exitProcess(1)
    }
    val directSpans = parseLongAfter(directMetrics, "spans=")
    val directSpanBytes = parseLongAfter(directMetrics, "spanBytes=")
    if (directSpans == null || directSpanBytes == null) {
        println("GATE RESULT: FAIL direct perf metrics unparsable metrics=$directMetrics")
        exitProcess(1)
    }
    val directModeled = modeledMs(directSpanBytes, directSpans)
    println("GATE direct modeled=${directModeled}ms spans=$directSpans spanBytes=$directSpanBytes")
    if (directModeled > 5000.0) {
        println("GATE RESULT: FAIL direct modeled over 5s")
        exitProcess(1)
    }
    // Redirect test: the same file behind a 307. The probe client must follow
    // it (this is the device failure mechanism); the window content must match
    // the direct fetch exactly.
    // Local redirect server started by the workflow: a single 307 hop to the
    // same file the direct test fetches. No external test dependency.
    val redirectMark = InAppLogger.lines.size
    val redirectUrl = "http://127.0.0.1:8931/redirect"
    val redirectEnv = try {
        readProbeAudioEnvelope(redirectUrl, emptyMap(), WINDOW_START_MS, WINDOW_MS)
    } catch (e: Exception) {
        println("GATE redirect threw ${e::class.simpleName}: ${e.message}")
        null
    }
    if (redirectEnv == null) {
        println("GATE RESULT: FAIL redirect envelope null")
        exitProcess(1)
    }
    val redirectSpeech = ProbeAutoSyncAligner.speechEnvelope(redirectEnv.rms)
    val redirectSpeechCount = redirectSpeech.count { it }
    println("GATE redirect envelope bins=${redirectEnv.rms.size} speechBins=$redirectSpeechCount directSpeechBins=$speechCount")
    if (redirectEnv.rms.size != rms.size || redirectSpeechCount != speechCount) {
        println("GATE RESULT: FAIL redirect content mismatch")
        exitProcess(1)
    }
    val redirectMetrics = latestMetricsSince(redirectMark, "extract: ranges=")
    if (redirectMetrics == null) {
        println("GATE RESULT: FAIL redirect perf metrics missing")
        exitProcess(1)
    }
    val redirectSpans = parseLongAfter(redirectMetrics, "spans=")
    val redirectSpanBytes = parseLongAfter(redirectMetrics, "spanBytes=")
    if (redirectSpans == null || redirectSpanBytes == null) {
        println("GATE RESULT: FAIL redirect perf metrics unparsable metrics=$redirectMetrics")
        exitProcess(1)
    }
    val redirectModeled = modeledMs(redirectSpanBytes, redirectSpans)
    println("GATE redirect modeled=${redirectModeled}ms spans=$redirectSpans spanBytes=$redirectSpanBytes")
    if (redirectModeled > 5000.0) {
        println("GATE RESULT: FAIL redirect modeled over 5s")
        exitProcess(1)
    }
    // ---- MKV / HLS suite (local files generated by the workflow) ----
    val localBase = "http://127.0.0.1:8931"
    // MKV AAC (remuxed copy: same audio as the MP4 direct run, but the
    // synth + AVFoundation decode path is not sample-exact -- measured
    // 1-frame priming offset + near-threshold bin flips, so it gets the
    // same tolerance 10 as the transcoded codecs; alignment checks stay exact).
    runFormatTest("mkv-aac", "$localBase/bbb-aac.mkv", WINDOW_START_MS, WINDOW_MS, directSpeech, 10, "A_AAC")
    // MKV EAC3 + Opus (transcoded audio: timing preserved, bins may shift a little).
    runFormatTest("mkv-eac3", "$localBase/bbb-eac3.mkv", WINDOW_START_MS, WINDOW_MS, directSpeech, 10, "A_EAC3")
    runFormatTest("mkv-opus", "$localBase/bbb-opus.mkv", WINDOW_START_MS, WINDOW_MS, directSpeech, 10, "A_OPUS")
    // MKV behind a 307 (redirect + EBML dispatch together).
    runFormatTest("mkv-redirect", "$localBase/redirect-mkv", WINDOW_START_MS, WINDOW_MS, directSpeech, 10, "A_AAC")
    // MKV without Cues: the scan fallback must run (cues=0 in the log).
    val nocuesMark = InAppLogger.lines.size
    runFormatTest("mkv-nocues", "$localBase/bbb-aac-nocues.mkv", WINDOW_START_MS, WINDOW_MS, directSpeech, 10, "A_AAC")
    val nocuesTrack = latestMetricsSince(nocuesMark, "extract: mkv track")
    if (nocuesTrack == null || !nocuesTrack.contains("cues=0")) {
        println("GATE RESULT: FAIL mkv-nocues did not take the scan path track=$nocuesTrack")
        exitProcess(1)
    }
    println("GATE mkv-nocues scan path confirmed cues=0")
    // HLS, generated locally: TS segments and fMP4 segments (both -c copy,
    // so the audio is identical to the MP4 direct run; the segment decode
    // path is not sample-exact either -- measured hls-ts 110 vs direct 106 --
    // so HLS gets the same tolerance 10 as the MKV codecs; alignment exact).
    runHlsTest("hls-ts", "$localBase/hls-ts/index.m3u8", WINDOW_START_MS, WINDOW_MS, directSpeech, 10)
    runHlsTest("hls-fmp4", "$localBase/hls-fmp4/index.m3u8", WINDOW_START_MS, WINDOW_MS, directSpeech, 10)
    // MP3 in MKV: informational only; if it decodes, MP3 can be promoted
    // from stretch to supported. Never fails the gate.
    try {
        val mp3Env = readProbeAudioEnvelope("$localBase/bbb-mp3.mkv", emptyMap(), WINDOW_START_MS, WINDOW_MS)
        if (mp3Env == null) {
            println("GATE mkv-mp3 envelope null (informational)")
        } else {
            val mp3Speech = ProbeAutoSyncAligner.speechEnvelope(mp3Env.rms)
            println("GATE mkv-mp3 envelope bins=${mp3Env.rms.size} speechBins=${mp3Speech.count { it }} (informational)")
        }
    } catch (e: Exception) {
        println("GATE mkv-mp3 threw ${e::class.simpleName} (informational)")
    }
    val hlsEnv = try {
        readProbeAudioEnvelope(HLS_URL, emptyMap(), WINDOW_START_MS, WINDOW_MS)
    } catch (e: Exception) {
        println("GATE hls threw ${e::class.simpleName}: ${e.message}")
        null
    }
    if (hlsEnv == null) {
        println("GATE hls envelope null (informational only, gate still passes)")
    } else {
        val hlsSpeech = ProbeAutoSyncAligner.speechEnvelope(hlsEnv.rms)
        println("GATE hls envelope bins=${hlsEnv.rms.size} speechBins=${hlsSpeech.count { it }} (informational)")
    }
    runSparseSideBySide("mp4", "$localBase/bbb.mp4", false)
    runSparseSideBySide("mkv-aac", "$localBase/bbb-aac.mkv", true)
    runLiveAddonSection()
    println("GATE RESULT: PASS")
}

private fun cuesFromSpeech(speech: BooleanArray, windowStartMs: Long, shiftMs: Long): List<SubtitleSyncCue> {
    val cues = mutableListOf<SubtitleSyncCue>()
    var i = 0
    while (i < speech.size) {
        if (!speech[i]) {
            i++
            continue
        }
        val start = i
        while (i < speech.size && speech[i]) i++
        val end = i
        cues.add(
            SubtitleSyncCue(
                windowStartMs + start * 100L + shiftMs,
                windowStartMs + end * 100L + shiftMs,
                "x"
            )
        )
    }
    return cues
}

// Stage-by-stage rerun of the reader's AVFoundation steps, printing each one,
// so a null envelope still names the failing stage instead of just failing.
private val gateHttpClient by lazy { HttpClient(Darwin) }

private suspend fun downloadBytes(url: String): ByteArray? = try {
    val response = gateHttpClient.get(url)
    if (!response.status.isSuccess()) null else response.body<ByteArray>()
} catch (_: Exception) {
    null
}

private fun ByteArray.writeToLocalFile(path: String): Boolean {
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

private suspend fun runStageDiagnostics(url: String, windowStartMs: Long, windowMs: Long) {
    val nsUrl = NSURL(string = url)
    if (nsUrl == null) {
        println("DIAG nsUrl null")
        return
    }
    val options: Map<Any?, Any?> = mapOf(AV_URL_ASSET_HTTP_HEADER_FIELDS_KEY to emptyMap<String, String>())
    val asset = AVURLAsset(uRL = nsUrl, options = options)
    val loaded = suspendCancellableCoroutine { cont ->
        asset.loadValuesAsynchronouslyForKeys(listOf("tracks")) {
            val ok = memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                asset.statusOfValueForKey("tracks", err.ptr) == AVKeyValueStatusLoaded
            }
            cont.resume(ok)
        }
    }
    println("DIAG awaitTracks=$loaded")
    if (!loaded) return
    val tracksSel = NSSelectorFromString("tracks")
    val mediaTypeSel = NSSelectorFromString("mediaType")
    if (tracksSel == null || mediaTypeSel == null) {
        println("DIAG selectors null")
        return
    }
    val all = (asset.performSelector(tracksSel) as? List<*>).orEmpty()
    println("DIAG tracks total=${all.size}")
    val audio = all.filterIsInstance<AVAssetTrack>()
        .filter { (it.performSelector(mediaTypeSel) as? String) == AVMediaTypeAudio }
    println("DIAG audioTracks=${audio.size}")
    if (audio.isEmpty()) return
    val settings: Map<Any?, Any?> = mapOf(
        "AVFormatIDKey" to NSNumber(unsignedInt = 1819304813u),
        "AVSampleRateKey" to 16000.0,
        "AVNumberOfChannelsKey" to 1,
        "AVLinearPCMBitDepthKey" to 16,
        "AVLinearPCMIsFloatKey" to false
    )
    val output = try {
        AVAssetReaderAudioMixOutput(audio, settings)
    } catch (e: Exception) {
        println("DIAG outputInitFailed ")
        return
    }
    val reader = try {
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            AVAssetReader(asset, err.ptr)
        }
    } catch (e: Exception) {
        println("DIAG readerInitFailed simple=${e::class.simpleName} msg=${e.message}")
        return
    }
    println("DIAG readerNull=${reader == null}")
    if (reader == null) return
    val canAdd = reader.canAddOutput(output)
    println("DIAG canAddOutput=$canAdd")
    if (!canAdd) return
    reader.addOutput(output)
    reader.timeRange = CMTimeRangeMake(
        CMTimeMakeWithSeconds(windowStartMs / 1000.0, 1000),
        CMTimeMakeWithSeconds(windowMs / 1000.0, 1000)
    )
    val started = reader.startReading()
    println("DIAG startReading=$started")
    if (!started) return
    var samples = 0
    var firstPts: Double? = null
    while (reader.status == AVAssetReaderStatusReading) {
        val sb = output.copyNextSampleBuffer() ?: break
        if (firstPts == null) firstPts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sb))
        samples++
    }
    println("DIAG samples=$samples firstPtsSeconds=$firstPts status=${reader.status}")
}

// ---- Live-addon section (gate-only code; NOT app code) ----
// Fetches ONE real stream list from a Stremio-compatible addon and runs the
// REAL reader against the first http(s) stream URL, reporting WALL-CLOCK
// per-stage timings: the numbers the byte-model cannot see (redirect
// latency, per-chunk header rescans, host speed). This is what the
// deterministic BBB fixture could not show: it has no redirects and a fast
// host, so redirect latency and rescan costs were invisible until devices.
// The manifest URL comes from env LIVE_PENGU_MANIFEST_URL (a repo secret).
// The gate NEVER prints the manifest URL or any stream URL: only hosts,
// the fixed public title id, counts, kinds, and timings. Stream-list JSON is
// scanned with a small regex (gate-only parsing, not app code) because the
// gate project has no serialization dependency.
// This section NEVER fails the gate: a live host is flaky by nature, so its
// outcome is reported on its own LIVE RESULT line, separate from GATE RESULT.
private fun hostOnly(url: String): String {
    val afterScheme = url.substringAfter("://", url)
    return afterScheme.substringBefore("/").substringBefore("?").ifBlank { "unknown-host" }
}

private fun nowSec(): Double = NSDate().timeIntervalSince1970

private fun fmtSec(s: Double): String {
    val tenths = (s * 10).toInt()
    return "${tenths / 10}.${tenths % 10}"
}

private suspend fun fetchListText(url: String): String? = try {
    val client = HttpClient(Darwin)
    try {
        val resp = client.get(url)
        if (!resp.status.isSuccess()) null else resp.body<ByteArray>().decodeToString()
    } finally {
        client.close()
    }
} catch (_: Exception) {
    null
}

private fun firstHttpUrl(json: String): String? {
    val idx = json.indexOf("\"streams\"")
    if (idx < 0) return null
    val re = Regex("\"url\"\\s*:\\s*\"(https?://[^\"]+)\"")
    return re.find(json, idx)?.groupValues?.getOrNull(1)
}

private suspend fun runLiveAddonSection() {
    val manifest = getenv("LIVE_PENGU_MANIFEST_URL")?.toKString().orEmpty()
    if (manifest.isBlank()) {
        println("LIVE RESULT: SKIPPED no manifest configured")
        return
    }
    if (!manifest.endsWith("/manifest.json")) {
        println("LIVE RESULT: SKIPPED manifest url does not end in manifest.json")
        return
    }
    val base = manifest.removeSuffix("/manifest.json")
    // Shows Alan picked (E1 of each): Our Beloved Summer, When I Fly Towards You.
    val titles = listOf("tt15026724:1:1", "tt28036189:1:1")
    try {
        withTimeout(900_000) {
            for (title in titles) {
                runLiveTitle(base, title)
            }
        }
    } catch (e: TimeoutCancellationException) {
        println("LIVE RESULT: TIMEOUT after 900s")
    }
}

private suspend fun runLiveTitle(base: String, title: String) {
    val listBody = fetchListText("$base/stream/series/$title.json")
    if (listBody == null) {
        println("LIVE RESULT: HOST_UNAVAILABLE host=${hostOnly(base)} title=$title stage=stream-list kind=fetch-failed")
        return
    }
    val streamUrl = firstHttpUrl(listBody)
    if (streamUrl == null) {
        println("LIVE RESULT: HOST_UNAVAILABLE host=${hostOnly(base)} title=$title stage=stream-list kind=no-http-stream")
        return
    }
    val streamHost = hostOnly(streamUrl)
    println("LIVE stream host=$streamHost title=$title")
    val wideT0 = nowSec()
    val wideEnv = try {
        readProbeAudioEnvelope(streamUrl, emptyMap(), 60_000, 240_000)
    } catch (e: Exception) {
        println("LIVE wide threw ${e::class.simpleName}")
        null
    }
    val wideT1 = nowSec()
    if (wideEnv == null) {
        println("LIVE RESULT: FAIL host=$streamHost title=$title stage=wide-envelope wallSec=${fmtSec(wideT1 - wideT0)}")
        return
    }
    val wideSpeech = ProbeAutoSyncAligner.speechEnvelope(wideEnv.rms.take(2400))
    println("LIVE wide host=$streamHost title=$title wallSec=${fmtSec(wideT1 - wideT0)} bins=${wideEnv.rms.size} speechBins=${wideSpeech.count { it }}")
    val cues = cuesFromSpeech(wideSpeech, 60_000, 0L)
    val chunks = ProbeAutoSyncAligner.pickSparseChunks(cues)
    println("LIVE sparse picked=$chunks title=$title")
    if (chunks.size < 2) {
        println("LIVE RESULT: ABSTAINED host=$streamHost title=$title reason=chunks-${chunks.size}")
        return
    }
    var readChunks = 0
    var sparseSec = 0.0
    for (chunkStart in chunks) {
        val cT0 = nowSec()
        val chunkEnv = try {
            readProbeAudioEnvelope(streamUrl, emptyMap(), chunkStart, 5_000)
        } catch (e: Exception) {
            println("LIVE chunk $chunkStart threw ${e::class.simpleName}")
            null
        }
        val cT1 = nowSec()
        sparseSec += (cT1 - cT0)
        if (chunkEnv == null) {
            println("LIVE chunk $chunkStart envelope null wallSec=${fmtSec(cT1 - cT0)}")
            continue
        }
        val cs = ProbeAutoSyncAligner.speechEnvelope(chunkEnv.rms.take(50))
        println("LIVE chunk $chunkStart wallSec=${fmtSec(cT1 - cT0)} speechBins=${cs.count { it }}")
        readChunks++
    }
    val kT0 = nowSec()
    val contigEnv = try {
        readProbeAudioEnvelope(streamUrl, emptyMap(), WINDOW_START_MS, WINDOW_MS)
    } catch (e: Exception) {
        println("LIVE contiguous threw ${e::class.simpleName}")
        null
    }
    val kT1 = nowSec()
    if (contigEnv == null) {
        println("LIVE RESULT: FAIL host=$streamHost title=$title stage=contiguous-envelope wallSec=${fmtSec(kT1 - kT0)} chunksRead=$readChunks")
        return
    }
    val contigSpeech = ProbeAutoSyncAligner.speechEnvelope(contigEnv.rms)
    println("LIVE contiguous host=$streamHost title=$title wallSec=${fmtSec(kT1 - kT0)} bins=${contigEnv.rms.size} speechBins=${contigSpeech.count { it }}")
    println("LIVE RESULT: OK host=$streamHost title=$title wideSec=${fmtSec(wideT1 - wideT0)} sparseSec=${fmtSec(sparseSec)} chunksRead=$readChunks contigSec=${fmtSec(kT1 - kT0)}")
}

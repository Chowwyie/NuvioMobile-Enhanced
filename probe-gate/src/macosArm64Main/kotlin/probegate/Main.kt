@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package probegate

import com.nuvio.app.features.autosync.ProbeAutoSyncAligner
import com.nuvio.app.features.autosync.readProbeAudioEnvelope
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

// Real public stream, 596s, so the gate uses the probe's real 180-240s window.
private const val STREAM_URL = "https://archive.org/download/BigBuckBunny_328/BigBuckBunny_512kb.mp4"
private const val HLS_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
private const val WINDOW_START_MS = 180_000L
private const val WINDOW_MS = 60_000L
private const val AV_URL_ASSET_HTTP_HEADER_FIELDS_KEY = "AVURLAssetHTTPHeaderFieldsKey"

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

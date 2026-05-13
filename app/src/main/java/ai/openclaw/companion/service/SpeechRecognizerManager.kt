package ai.openclaw.companion.service

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Manages ML Kit GenAI Speech Recognition lifecycle.
 *
 * Supports two modes:
 * - MODE_ADVANCED: Uses on-device Gemini Nano model (Pixel 10+ only)
 * - MODE_BASIC: Uses traditional on-device speech recognition (API 31+)
 *
 * Important: Advanced mode is currently only available on Pixel 10 devices.
 * On other devices (like iQOO 13), Basic mode is used.
 *
 * File transcription requires raw 16-bit PCM mono 16kHz audio.
 * For audio files in other formats (OGG/Opus, MP3, etc.), the audio must be
 * converted to PCM before being sent to the recognizer. This is handled via
 * Android's MediaCodec/MediaExtractor.
 *
 * Usage from HTTP API:
 * 1. POST /stt/transcribe — transcribe an audio file (blocking, returns final text)
 * 2. GET /stt/status — check model availability and readiness
 * 3. POST /stt/download — trigger model download if needed
 */
class SpeechRecognizerManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechRecognizerMgr"

        @Volatile
        @JvmStatic
        var instance: SpeechRecognizerManager? = null
            internal set
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Current recognizer instance
    @Volatile
    private var recognizer: SpeechRecognizer? = null
    @Volatile
    private var currentMode: String = "unknown"

    // Model state
    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Unknown)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus

    sealed class ModelStatus {
        data object Unknown : ModelStatus()
        data object Checking : ModelStatus()
        data object Available : ModelStatus()
        data object Downloadable : ModelStatus()
        data class Downloading(val progress: Int) : ModelStatus()
        data class DownloadFailed(val error: String) : ModelStatus()
        data class NotSupported(val reason: String) : ModelStatus()
    }

    data class TranscriptionResult(
        val text: String,
        val mode: String
    )

    /**
     * Initialize the speech recognizer. Tries Advanced mode first, falls back to Basic.
     * Must be called after service is created.
     */
    fun initialize() {
        _modelStatus.value = ModelStatus.Checking
        scope.launch {
            tryInitialize()
        }
    }

    private suspend fun tryInitialize() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            _modelStatus.value = ModelStatus.NotSupported("Requires API 31+ (Android 12+)")
            return
        }

        // Try Advanced mode first (Gemini Nano) — only works on Pixel 10+
        try {
            val advancedOpts = SpeechRecognizerOptions.builder().apply {
                locale = Locale.getDefault()
                preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
            }.build()

            val advancedRecognizer = SpeechRecognition.getClient(advancedOpts)
            val status = advancedRecognizer.checkStatus()

            when (status) {
                FeatureStatus.AVAILABLE -> {
                    recognizer = advancedRecognizer
                    currentMode = "advanced"
                    _modelStatus.value = ModelStatus.Available
                    Log.i(TAG, "Speech recognizer initialized: ADVANCED mode (Gemini Nano)")
                    return
                }
                FeatureStatus.DOWNLOADABLE -> {
                    Log.i(TAG, "Advanced model downloadable, falling back to Basic")
                    advancedRecognizer.close()
                }
                else -> {
                    Log.w(TAG, "Advanced mode status: $status, falling back to Basic")
                    advancedRecognizer.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Advanced mode not available: ${e.message}")
        }

        // Fall back to Basic mode
        try {
            val basicOpts = SpeechRecognizerOptions.builder().apply {
                locale = Locale.getDefault()
                preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
            }.build()

            val basicRecognizer = SpeechRecognition.getClient(basicOpts)
            val status = basicRecognizer.checkStatus()

            when (status) {
                FeatureStatus.AVAILABLE -> {
                    recognizer = basicRecognizer
                    currentMode = "basic"
                    _modelStatus.value = ModelStatus.Available
                    Log.i(TAG, "Speech recognizer initialized: BASIC mode")
                }
                FeatureStatus.DOWNLOADABLE -> {
                    recognizer = basicRecognizer
                    currentMode = "basic"
                    _modelStatus.value = ModelStatus.Downloadable
                    Log.i(TAG, "Basic model needs download")
                }
                else -> {
                    _modelStatus.value = ModelStatus.NotSupported("Status: $status")
                    Log.e(TAG, "Basic mode not available, status: $status")
                }
            }
        } catch (e: Exception) {
            _modelStatus.value = ModelStatus.NotSupported(e.message ?: "Unknown error")
            Log.e(TAG, "Failed to initialize Basic mode: ${e.message}")
        }
    }

    /**
     * Trigger model download for the current recognizer.
     */
    suspend fun downloadModel(): ModelStatus {
        val currentRecognizer = recognizer
            ?: return ModelStatus.NotSupported("No recognizer initialized")

        return try {
            _modelStatus.value = ModelStatus.Downloading(0)
            @OptIn(kotlin.concurrent.ExperimentalAtomicApi::class)
            currentRecognizer.download().collect { downloadStatus ->
                when (downloadStatus) {
                    is DownloadStatus.DownloadProgress -> {
                        _modelStatus.value = ModelStatus.Downloading((downloadStatus.totalBytesDownloaded / 1024).toInt())
                    }
                    is DownloadStatus.DownloadStarted -> {
                        _modelStatus.value = ModelStatus.Downloading(0)
                    }
                    is DownloadStatus.DownloadCompleted -> {
                        _modelStatus.value = ModelStatus.Available
                        Log.i(TAG, "Model download completed")
                    }
                    is DownloadStatus.DownloadFailed -> {
                        _modelStatus.value = ModelStatus.DownloadFailed(
                            downloadStatus.e.message ?: "Download failed"
                        )
                        Log.e(TAG, "Model download failed: ${downloadStatus.e.message}")
                    }
                }
            }
            _modelStatus.value
        } catch (e: Exception) {
            val status = ModelStatus.DownloadFailed(e.message ?: "Download error")
            _modelStatus.value = status
            status
        }
    }

    /**
     * Get the current status info for the /stt/status endpoint.
     */
    fun getStatusInfo(): Map<String, Any?> {
        val mode = currentMode
        val status = _modelStatus.value

        return mapOf(
            "ready" to (status is ModelStatus.Available),
            "mode" to mode,
            "modelStatus" to when (status) {
                is ModelStatus.Unknown -> "unknown"
                is ModelStatus.Checking -> "checking"
                is ModelStatus.Available -> "available"
                is ModelStatus.Downloadable -> "downloadable"
                is ModelStatus.Downloading -> "downloading(${status.progress}%)"
                is ModelStatus.DownloadFailed -> "download_failed: ${status.error}"
                is ModelStatus.NotSupported -> "not_supported: ${status.reason}"
            }
        )
    }

    /**
     * Transcribe an audio file. Saves the uploaded bytes to a temp file,
     * converts to PCM if needed, then runs speech recognition.
     *
     * IMPORTANT: ML Kit GenAI Speech Recognition requires raw 16-bit PCM mono 16kHz audio
     * when using AudioSource.fromPfd(). If the input is in another format (OGG/Opus, MP3, etc.),
     * it will be converted to PCM using Android's MediaCodec before being sent to the recognizer.
     *
     * Returns the final transcription text.
     */
    suspend fun transcribeFile(audioBytes: ByteArray, mimeType: String?): TranscriptionResult {
        val currentRecognizer = recognizer
            ?: return TranscriptionResult("", "none")

        if (_modelStatus.value !is ModelStatus.Available) {
            return TranscriptionResult("", currentMode)
        }

        // Determine if input is already PCM or needs conversion
        val isRawPcm = mimeType?.contains("pcm") == true || mimeType?.contains("raw") == true

        // Save uploaded bytes to a temp file
        val ext = when {
            mimeType?.contains("webm") == true -> "webm"
            mimeType?.contains("ogg") == true || mimeType?.contains("opus") == true -> "ogg"
            mimeType?.contains("mp4") == true || mimeType?.contains("m4a") == true -> "m4a"
            mimeType?.contains("wav") == true -> "wav"
            mimeType?.contains("mp3") == true -> "mp3"
            mimeType?.contains("amr") == true -> "amr"
            mimeType?.contains("pcm") == true || mimeType?.contains("raw") == true -> "pcm"
            else -> "wav"
        }

        val tempFile = File(context.cacheDir, "stt_input_${System.currentTimeMillis()}.$ext")
        try {
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            if (isRawPcm) {
                // Already PCM, use directly
                return transcribeWithPfd(currentRecognizer, tempFile)
            } else {
                // Convert to PCM first
                val pcmFile = File(context.cacheDir, "stt_pcm_${System.currentTimeMillis()}.pcm")
                try {
                    convertToPcmFromFile(tempFile, pcmFile)
                    return transcribeWithPfd(currentRecognizer, pcmFile)
                } finally {
                    pcmFile.delete()
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Transcribe an audio file given a content URI (e.g. from MediaRecorder output).
     */
    suspend fun transcribeUri(uri: Uri): TranscriptionResult {
        val currentRecognizer = recognizer
            ?: return TranscriptionResult("", "none")

        if (_modelStatus.value !is ModelStatus.Available) {
            return TranscriptionResult("", currentMode)
        }

        // Convert to PCM and write to a temp file, then use PFD
        val tempPcmFile = File(context.cacheDir, "stt_pcm_${System.currentTimeMillis()}.pcm")
        try {
            convertToPcm(uri, tempPcmFile)
            return transcribeWithPfd(currentRecognizer, tempPcmFile)
        } finally {
            tempPcmFile.delete()
        }
    }

    /**
     * Transcribe using a ParcelFileDescriptor from a file.
     * The file must be raw 16-bit PCM mono 16kHz.
     */
    private suspend fun transcribeWithPfd(
        currentRecognizer: SpeechRecognizer,
        audioFile: File
    ): TranscriptionResult = coroutineScope {
        val resultText = MutableStateFlow("")
        val completed = CompletableDeferred<Boolean>()

        try {
            val pfd = ParcelFileDescriptor.open(audioFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val audioSource = AudioSource.fromPfd(pfd)

            val request = SpeechRecognizerRequest.builder().apply {
                audioSource = audioSource
            }.build()

            @OptIn(kotlin.concurrent.ExperimentalAtomicApi::class)
            currentRecognizer.startRecognition(request).collect { response ->
                when (response) {
                    is SpeechRecognizerResponse.PartialTextResponse -> {
                        resultText.value = response.text
                        Log.d(TAG, "Partial: ${response.text}")
                    }
                    is SpeechRecognizerResponse.FinalTextResponse -> {
                        resultText.value = response.text
                        Log.i(TAG, "Final: ${response.text}")
                    }
                    is SpeechRecognizerResponse.ErrorResponse -> {
                        Log.e(TAG, "Recognition error: ${response.e.message}")
                        completed.complete(false)
                    }
                    is SpeechRecognizerResponse.CompletedResponse -> {
                        Log.i(TAG, "Recognition completed")
                        completed.complete(true)
                    }
                }
            }

            // If we get here, the flow completed without a CompletedResponse
            if (!completed.isCompleted) {
                completed.complete(true)
            }

            pfd.close()
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error: ${e.message}", e)
            if (!completed.isCompleted) {
                completed.complete(false)
            }
        }

        TranscriptionResult(
            text = resultText.value,
            mode = currentMode
        )
    }

    /**
     * Convert audio from a content URI to raw PCM (16-bit, mono, 16kHz) using MediaCodec.
     * Required because ML Kit GenAI Speech Recognition only accepts PCM input via fromPfd().
     */
    private fun convertToPcm(inputUri: Uri, outputFile: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, inputUri, null)
            convertToPcmWithExtractor(extractor, outputFile)
        } finally {
            extractor.release()
        }
    }

    /**
     * Convert audio from a local file to raw PCM using MediaCodec.
     */
    private fun convertToPcmFromFile(inputFile: File, outputFile: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputFile.absolutePath)
            convertToPcmWithExtractor(extractor, outputFile)
        } finally {
            extractor.release()
        }
    }

    /**
     * Shared conversion logic using a configured MediaExtractor.
     * Decodes compressed audio to raw 16-bit PCM using MediaCodec.
     * If the track is already raw (audio/raw), the extractor data is read
     * and written directly to the output file.
     */
    private fun convertToPcmWithExtractor(extractor: MediaExtractor, outputFile: File) {
        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            val format = extractor.getTrackFormat(i)
            format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("No audio track found")

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/raw"

        if (mime == "audio/raw") {
            // Already raw audio, read samples and write directly
            FileOutputStream(outputFile).use { output ->
                val sampleBuffer = ByteArray(8192)
                while (true) {
                    val sampleSize = extractor.readSampleData(sampleBuffer, 0)
                    if (sampleSize <= 0) break
                    output.write(sampleBuffer, 0, sampleSize)
                    extractor.advance()
                }
            }
            return
        }

        // Use MediaCodec to decode compressed audio to PCM
        val decoder = android.media.MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val bufferInfo = android.media.MediaCodec.BufferInfo()
        val outputStream = FileOutputStream(outputFile)

        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                        val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputBufferIndex, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                // Read output
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0) {
                    if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.get(data)
                        outputStream.write(data)
                    }
                    decoder.releaseOutputBuffer(outputBufferIndex, false)
                }
            }

            outputStream.flush()
        } finally {
            outputStream.close()
            decoder.stop()
            decoder.release()
        }
    }

    /**
     * Release resources.
     */
    fun shutdown() {
        recognizer?.close()
        recognizer = null
        scope.cancel()
    }
}
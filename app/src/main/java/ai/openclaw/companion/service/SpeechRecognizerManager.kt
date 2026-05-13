package ai.openclaw.companion.service

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.mlkit.genai.speechrecognition.DownloadStatus
import com.google.mlkit.genai.speechrecognition.FeatureStatus
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages ML Kit GenAI Speech Recognition lifecycle.
 *
 * Supports two modes:
 * - MODE_ADVANCED: Uses on-device Gemini Nano model (iQOO 13 supported)
 * - MODE_BASIC: Uses traditional on-device speech recognition (API 31+)
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
        var instance: SpeechRecognizerManager? = null
            private set
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Current recognizer instance (Advanced mode preferred)
    private val recognizerRef = AtomicReference<SpeechRecognizer?>(null)
    private val currentMode = AtomicReference("unknown")

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
        val isFinal: Boolean,
        val confidence: Float? = null,
        val mode: String
    )

    /**
     * Initialize the speech recognizer. Tries Advanced mode first, falls back to Basic.
     * Must be called after service is created.
     */
    fun initialize() {
        scope.launch {
            tryInitialize()
        }
    }

    private suspend fun tryInitialize() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            _modelStatus.value = ModelStatus.NotSupported("Requires API 31+ (Android 12+)")
            return
        }

        // Try Advanced mode first (Gemini Nano)
        try {
            val advancedOpts = SpeechRecognizerOptions.Builder()
                .setLocale(Locale.getDefault())
                .setPreferredMode(SpeechRecognizerOptions.Mode.MODE_ADVANCED)
                .build()

            val advancedRecognizer = SpeechRecognition.getClient(advancedOpts)
            val status = advancedRecognizer.checkStatus()

            when (status) {
                FeatureStatus.AVAILABLE -> {
                    recognizerRef.set(advancedRecognizer)
                    currentMode.set("advanced")
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
            val basicOpts = SpeechRecognizerOptions.Builder()
                .setLocale(Locale.getDefault())
                .setPreferredMode(SpeechRecognizerOptions.Mode.MODE_BASIC)
                .build()

            val basicRecognizer = SpeechRecognition.getClient(basicOpts)
            val status = basicRecognizer.checkStatus()

            when (status) {
                FeatureStatus.AVAILABLE -> {
                    recognizerRef.set(basicRecognizer)
                    currentMode.set("basic")
                    _modelStatus.value = ModelStatus.Available
                    Log.i(TAG, "Speech recognizer initialized: BASIC mode")
                }
                FeatureStatus.DOWNLOADABLE -> {
                    recognizerRef.set(basicRecognizer)
                    currentMode.set("basic")
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
        val recognizer = recognizerRef.get()
            ?: return ModelStatus.NotSupported("No recognizer initialized")

        return try {
            _modelStatus.value = ModelStatus.Downloading(0)
            recognizer.download().collect { downloadStatus ->
                when (downloadStatus) {
                    is DownloadStatus.DownloadProgress -> {
                        _modelStatus.value = ModelStatus.Downloading(downloadStatus.progress)
                    }
                    is DownloadStatus.DownloadCompleted -> {
                        _modelStatus.value = ModelStatus.Available
                        Log.i(TAG, "Model download completed")
                    }
                    is DownloadStatus.DownloadFailed -> {
                        _modelStatus.value = ModelStatus.DownloadFailed(
                            downloadStatus.error?.message ?: "Download failed"
                        )
                        Log.e(TAG, "Model download failed: ${downloadStatus.error?.message}")
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
        val recognizer = recognizerRef.get()
        val mode = currentMode.get()
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
     * then runs speech recognition on it.
     *
     * Returns the final transcription text.
     */
    suspend fun transcribeFile(audioBytes: ByteArray, mimeType: String?): TranscriptionResult {
        val recognizer = recognizerRef.get()
            ?: return TranscriptionResult("", true, mode = "none")

        if (_modelStatus.value !is ModelStatus.Available) {
            return TranscriptionResult("", true, mode = currentMode.get())
                .also { /* model not ready */ }
        }

        // Save audio bytes to a temp file
        val ext = when {
            mimeType?.contains("webm") == true -> "webm"
            mimeType?.contains("ogg") == true || mimeType?.contains("opus") == true -> "ogg"
            mimeType?.contains("mp4") == true || mimeType?.contains("m4a") == true -> "m4a"
            mimeType?.contains("wav") == true -> "wav"
            mimeType?.contains("mp3") == true -> "mp3"
            mimeType?.contains("amr") == true -> "amr"
            else -> "wav"
        }

        val tempFile = File(context.cacheDir, "stt_input_${System.currentTimeMillis()}.$ext")
        try {
            FileOutputStream(tempFile).use { it.write(audioBytes) }
            return transcribeFileInternal(recognizer, tempFile, Uri.fromFile(tempFile))
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Transcribe an audio file given a content URI (e.g. from MediaRecorder output).
     */
    suspend fun transcribeUri(uri: Uri): TranscriptionResult {
        val recognizer = recognizerRef.get()
            ?: return TranscriptionResult("", true, mode = "none")

        return transcribeFileInternal(recognizer, null, uri)
    }

    private suspend fun transcribeFileInternal(
        recognizer: SpeechRecognizer,
        tempFile: File?,
        uri: Uri
    ): TranscriptionResult {
        return coroutineScope {
            val result = MutableStateFlow<TranscriptionResult?>(null)
            val job = scope.launch {
                try {
                    recognizer.transcribe(uri)
                        .collect { speechResult ->
                            val transcription = TranscriptionResult(
                                text = speechResult.text,
                                isFinal = speechResult.isFinal,
                                confidence = speechResult.confidence,
                                mode = currentMode.get()
                            )
                            result.value = transcription
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Transcription error: ${e.message}", e)
                    result.value = TranscriptionResult(
                        text = "",
                        isFinal = true,
                        mode = currentMode.get()
                    )
                }
            }
            job.join()
            result.value ?: TranscriptionResult("", true, mode = currentMode.get())
        }
    }

    /**
     * Release resources.
     */
    fun shutdown() {
        recognizerRef.get()?.close()
        recognizerRef.set(null)
        scope.cancel()
    }
}
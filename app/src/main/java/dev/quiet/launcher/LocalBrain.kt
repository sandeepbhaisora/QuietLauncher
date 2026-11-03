package dev.quiet.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Locale

/**
 * Actual local GGUF inference via llama.cpp, not a keyword/heuristic answerer.
 * A single model is cached in process; native inference is serialized.
 * The language model has NO Android privileges and never executes a tool.
 */
object LocalBrain {
    private const val TAG = "QuietLocalBrain"
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private var model: LlamaModel? = null
    @Volatile private var loaded = false

    interface Callback {
        fun onResult(text: String, action: String?, fallback: Boolean, speed: Float)
        fun onError(reason: String)
    }

    @JvmStatic fun isLoaded(): Boolean = loaded

    @JvmStatic fun ask(context: Context, question: String, recentContext: String, callback: Callback) {
        val app = context.applicationContext
        worker.launch {
            try {
                val safety = app.getSharedPreferences("quiet_local_brain", Context.MODE_PRIVATE)
                if (safety.getBoolean("inference_in_progress", false)) {
                    app.getSharedPreferences("quiet_local_v1", Context.MODE_PRIVATE)
                        .edit().putBoolean("londe_local_brain_enabled", false).commit()
                    safety.edit().remove("inference_in_progress").commit()
                    main.post { callback.onError("Previous local model session ended unexpectedly; local AI disabled for safety. Re-enable in Settings to retry.") }
                    return@launch
                }
                if (!LocalBrainModels.installed(app)) {
                    main.post { callback.onError("Download the Qwen3 model in Quiet Settings first.") }
                    return@launch
                }
                // Do not pretend a 0.6B offline model can know live/current facts.
                val lower = question.lowercase(Locale.ROOT)
                if (Regex("\\b(today|latest|currently|news|weather|score|stock price|live|recent|tomorrow)\\b")
                        .containsMatchIn(lower) && !lower.contains("what day is")) {
                    main.post { callback.onResult("", null, true, 0f) }
                    return@launch
                }
                val result = mutex.withLock {
                    // Persistent guard also catches native SIGABRT/out-of-memory
                    // process death, which bypasses Kotlin/Java exception handlers.
                    safety.edit().putBoolean("inference_in_progress", true).commit()
                    try {
                    if (model == null) {
                        val config = LlamaConfig(
                            contextSize = 2048,
                            threads = 3,
                            gpuLayers = 0,
                            temperature = 0.25f,
                            topP = 0.8f,
                            topK = 30
                        )
                        model = Llama.loadModel(LocalBrainModels.model(app).absolutePath, config)
                        loaded = true
                    }
                    val instructions = """
                        You are Londe, a concise, warm Android assistant speaking natural Hindi/Hinglish or English.
                        /no_think
                        Answer using ONLY what you know. You have no internet, no personal calendar, no access to the phone screen.
                        Never claim you performed any Android operation.
                        Return ONLY one JSON object on one line:
                        {"type":"answer","text":"..."} for a confident factual or conversational reply.
                        {"type":"action","command":"..."} ONLY for explicit commands by the user, formatted as one command:
                        open <installed app name>; start focus for <N> minutes; stop focus.
                        {"type":"fallback"} when you are uncertain, need live/current data, or can't answer.
                        Do not invent facts, app names, access permissions, or command results.
                        Keep answers under 65 words. Never return markdown or reasoning.
                    """.trimIndent()
                    val fullQuestion = (if (recentContext.isBlank()) "" else
                            "Earlier turns for context:\n" + recentContext.takeLast(600) + "\n") +
                            "Current user request: " + question.take(750)
                    Llama.complete(model!!, fullQuestion, instructions, 180)
                    } finally {
                        safety.edit().remove("inference_in_progress").commit()
                    }
                }
                val body = result.text.trim().replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
                val start = body.indexOf('{')
                val end = body.lastIndexOf('}')
                if (start < 0 || end <= start) {
                    main.post { callback.onResult("", null, true, result.tokensPerSecond) }
                    return@launch
                }
                val json = JSONObject(body.substring(start, end + 1))
                when (json.optString("type")) {
                    "answer" -> {
                        val answer = json.optString("text").trim().take(700)
                        main.post {
                            callback.onResult(answer, null, answer.length < 3,
                                result.tokensPerSecond)
                        }
                    }
                    "action" -> {
                        val command = json.optString("command").trim()
                        // A model-generated action never executes automatically.
                        // The caller must re-parse, re-validate and show action review.
                        val explicit = Regex("(?i)^\\s*(?:please |hey londe |londe )*(?:open|launch|start|stop|focus|.*\\s+kholo\\s*)")
                            .containsMatchIn(question)
                        main.post {
                            callback.onResult("", if (explicit) command else null,
                                !explicit || command.length > 140,
                                result.tokensPerSecond)
                        }
                    }
                    else -> main.post { callback.onResult("", null, true, result.tokensPerSecond) }
                }
            } catch (ex: Throwable) {
                Log.e(TAG, "On-device llama.cpp inference failed", ex)
                main.post { callback.onError("Offline brain error: " + ex.javaClass.simpleName) }
            }
        }
    }
}

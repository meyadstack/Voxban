package com.voicekanban.app.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.voicekanban.app.data.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class Turn(val fromUser: Boolean, val text: String)

class CloudException(message: String) : Exception(message)

/**
 * Talks to ChatGPT, Gemini and Claude. Every call surfaces the provider's
 * real error text so a bad key / missing quota / retired model is obvious,
 * and [listModels] lets the user pick a model their key can actually use.
 */
object CloudProviders {

    val DEFAULT_MODELS = mapOf(
        Engine.OPENAI to "gpt-4o-mini",
        Engine.GEMINI to "gemini-flash-latest",
        Engine.CLAUDE to "claude-opus-5-5",
        Engine.OPENROUTER to "openai/gpt-4o-mini",
    )

    private const val OPENROUTER = "https://openrouter.ai/api/v1"
    /** OpenRouter's optional attribution headers (shown on openrouter.ai rankings). */
    private val OPENROUTER_HEADERS = mapOf("X-Title" to "Voxban", "HTTP-Referer" to "https://github.com/meyadstack/Voxban")

    /** Model ids the user picked that cost nothing on OpenRouter (filled by [listModels]). */
    val freeModels = mutableSetOf<String>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------- chat

    suspend fun complete(engine: Engine, apiKey: String, model: String?, system: String, history: List<Turn>, maxTokens: Int = 1200): String =
        withContext(Dispatchers.IO) {
            val key = apiKey.trim()
            if (key.isEmpty()) throw CloudException("No ${engine.label} API key set. Add one in Settings → AI engine.")
            val m = model?.takeIf { it.isNotBlank() } ?: DEFAULT_MODELS.getValue(engine)
            when (engine) {
                Engine.OPENAI -> openAi(key, m, system, history, maxTokens)
                Engine.GEMINI -> gemini(key, m, system, history, maxTokens)
                Engine.CLAUDE -> claude(key, m, system, history, maxTokens)
                Engine.OPENROUTER -> openRouter(key, m, system, history, maxTokens)
                else -> throw CloudException("${engine.label} is not a cloud engine.")
            }
        }

    private fun post(url: String, body: JsonObject, headers: Map<String, String>): JsonObject {
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(req).execute().use { res ->
            val text = res.body.string()
            if (!res.isSuccessful) throw CloudException(describeHttpError(res.code, text))
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private fun get(url: String, headers: Map<String, String>): JsonObject {
        val req = Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(req).execute().use { res ->
            val text = res.body.string()
            if (!res.isSuccessful) throw CloudException(describeHttpError(res.code, text))
            return json.parseToJsonElement(text).jsonObject
        }
    }

    /** Turns provider error bodies into a one-line, actionable message. */
    private fun describeHttpError(code: Int, body: String): String {
        val msg = runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            (o["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                ?: o["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: body.take(160)
        val hint = when (code) {
            400 -> if (msg.contains("model", true)) "Pick another model in Settings." else ""
            401, 403 -> "Check the API key in Settings."
            404 -> "This model isn’t available to your key - pick another in Settings."
            429 -> "Rate limit or out of credit/quota on this key."
            in 500..599 -> "The provider is having problems; try again shortly."
            else -> ""
        }
        return "HTTP $code: $msg${if (hint.isNotEmpty()) " - $hint" else ""}"
    }

    private fun openAi(key: String, model: String, system: String, history: List<Turn>, maxTokens: Int): String {
        val reasoning = model.startsWith("o") || model.startsWith("gpt-5")
        val body = buildJsonObject {
            put("model", model)
            // Newer reasoning models reject max_tokens; max_completion_tokens works everywhere.
            put("max_completion_tokens", if (reasoning) maxOf(maxTokens, 4000) else maxTokens)
            putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                history.forEach { t -> add(buildJsonObject { put("role", if (t.fromUser) "user" else "assistant"); put("content", t.text) }) }
            }
        }
        val res = post("https://api.openai.com/v1/chat/completions", body, mapOf("Authorization" to "Bearer $key"))
        return res["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw CloudException("ChatGPT returned an empty response.")
    }

    /**
     * OpenRouter: OpenAI-compatible chat completions for hundreds of models.
     * Not every model supports JSON mode, so a 400 about response_format
     * retries once without it (the prompt still asks for JSON).
     */
    private fun openRouter(key: String, model: String, system: String, history: List<Turn>, maxTokens: Int): String {
        fun body(jsonMode: Boolean) = buildJsonObject {
            put("model", model)
            put("max_tokens", maxOf(maxTokens, 1200)) // reasoning models need headroom
            if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                history.forEach { t -> add(buildJsonObject { put("role", if (t.fromUser) "user" else "assistant"); put("content", t.text) }) }
            }
        }
        val headers = OPENROUTER_HEADERS + ("Authorization" to "Bearer $key")
        val res = try {
            post("$OPENROUTER/chat/completions", body(true), headers)
        } catch (e: CloudException) {
            if (e.message.orEmpty().contains("HTTP 400") && e.message.orEmpty().contains(Regex("response_format|json", RegexOption.IGNORE_CASE))) {
                post("$OPENROUTER/chat/completions", body(false), headers)
            } else throw e
        }
        // OpenRouter can return 200 with an error object (e.g. upstream provider failure).
        (res["error"] as? JsonObject)?.let { err ->
            throw CloudException("OpenRouter: ${err["message"]?.jsonPrimitive?.contentOrNull ?: "provider error"} - try another model in Settings.")
        }
        return res["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: throw CloudException("OpenRouter returned an empty response from $model. Try another model.")
    }

    private fun gemini(key: String, model: String, system: String, history: List<Turn>, maxTokens: Int): String {
        fun buildBody(useSystemInstruction: Boolean, jsonMime: Boolean) = buildJsonObject {
            if (useSystemInstruction) {
                putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            }
            putJsonArray("contents") {
                // Gemini requires the conversation to start with a user turn.
                val turns = history.dropWhile { !it.fromUser }
                turns.forEachIndexed { idx, t ->
                    val content = if (!useSystemInstruction && idx == 0 && t.fromUser) {
                        "$system\n\nUser request:\n${t.text}"
                    } else t.text
                    add(buildJsonObject {
                        put("role", if (t.fromUser) "user" else "model")
                        putJsonArray("parts") { add(buildJsonObject { put("text", content) }) }
                    })
                }
            }
            putJsonObject("generationConfig") {
                if (jsonMime) put("responseMimeType", "application/json")
                put("maxOutputTokens", maxOf(maxTokens, 2048)) // thinking models spend tokens before answering
                put("temperature", 0.2)
            }
        }
        val name = model.removePrefix("models/")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$name:generateContent"
        val headers = mapOf("x-goog-api-key" to key)
        val isGemma = name.startsWith("gemma", ignoreCase = true)
        val res = try {
            post(url, buildBody(useSystemInstruction = !isGemma, jsonMime = !isGemma), headers)
        } catch (e: CloudException) {
            if (e.message.orEmpty().contains("HTTP 400")) {
                post(url, buildBody(useSystemInstruction = false, jsonMime = false), headers)
            } else throw e
        }
        val cand = res["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw CloudException("Gemini returned no answer${res["promptFeedback"]?.let { " (blocked: $it)" } ?: ""}.")
        return cand["content"]?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }?.joinToString("")
            ?.takeIf { it.isNotBlank() }
            ?: throw CloudException("Gemini returned an empty response (finish reason: ${cand["finishReason"]?.jsonPrimitive?.contentOrNull}).")
    }

    private fun claudeClient(key: String): AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(key)
        .timeout(java.time.Duration.ofSeconds(120))
        .maxRetries(1)
        .build()

    private fun claude(key: String, model: String, system: String, history: List<Turn>, maxTokens: Int): String {
        val client = claudeClient(key)
        try {
            val builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxOf(maxTokens, 4000).toLong())
                .system(system)
            // Claude Opus 5.5+: thinking is always on; low effort keeps replies fast.
            if (model.startsWith("claude-opus-5") || model.startsWith("claude-sonnet-5") || model.startsWith("claude-fable")) {
                builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            }
            // Conversation must start with a user turn and alternate.
            history.dropWhile { !it.fromUser }.forEach { t ->
                builder.addMessage(if (t.fromUser) MessageParam.ofUser(t.text) else MessageParam.ofAssistant(t.text))
            }
            val message = client.messages().create(builder.build())
            if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
                return """{"reply":"Sorry, I can’t help with that request.","actions":[]}"""
            }
            val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            if (text.isBlank()) throw CloudException("Claude returned an empty response.")
            return text
        } catch (e: UnauthorizedException) {
            throw CloudException("Claude rejected the API key - check it in Settings.")
        } catch (e: RateLimitException) {
            throw CloudException("Claude rate limit or out of credit - try again shortly.")
        } catch (e: AnthropicServiceException) {
            throw CloudException("Claude HTTP ${e.statusCode()}: ${e.message?.take(160)}")
        } catch (e: AnthropicIoException) {
            throw CloudException("Couldn’t reach Claude: ${e.message?.take(120)}")
        } finally {
            client.close()
        }
    }

    // ---------------------------------------------------------- models / test

    /** Chat-capable models the key can use, best first. */
    suspend fun listModels(engine: Engine, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) throw CloudException("Enter an API key first.")
        when (engine) {
            Engine.OPENAI -> get("https://api.openai.com/v1/models", mapOf("Authorization" to "Bearer $key"))["data"]!!.jsonArray
                .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                .filter { id -> (id.startsWith("gpt-") || Regex("""^o\d""").containsMatchIn(id)) &&
                    listOf("audio", "realtime", "tts", "transcribe", "image", "search", "instruct", "embedding").none { id.contains(it) } }
                .sortedWith(compareBy<String> { !it.contains("mini") }.thenByDescending { it })
            Engine.GEMINI -> get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", mapOf("x-goog-api-key" to key))["models"]!!.jsonArray
                .map { it.jsonObject }
                .filter { m -> m["supportedGenerationMethods"]?.jsonArray?.any { it.jsonPrimitive.contentOrNull == "generateContent" } == true }
                .mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") }
                .filter { (it.startsWith("gemini") || it.startsWith("gemma-4") || it.startsWith("gemma")) && listOf("tts", "image", "embedding", "live", "audio").none { x -> it.contains(x) } }
                .sortedWith(compareBy<String> { !it.contains("flash") && !it.startsWith("gemma-4") }.thenBy { it.contains("preview") || it.contains("exp") }.thenByDescending { it })
            Engine.OPENROUTER -> {
                // Validates the key first (the model list itself is public).
                get("$OPENROUTER/key", mapOf("Authorization" to "Bearer $key"))
                val all = get("$OPENROUTER/models", OPENROUTER_HEADERS)["data"]!!.jsonArray.map { it.jsonObject }
                    .filter { m ->
                        val out = m["architecture"]?.jsonObject?.get("output_modalities")?.jsonArray?.map { it.jsonPrimitive.content }
                        out == null || "text" in out
                    }
                freeModels.clear()
                all.forEach { m ->
                    val p = m["pricing"]?.jsonObject
                    val free = p?.get("prompt")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() == 0.0 &&
                        p["completion"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() == 0.0
                    if (free) freeModels += m["id"]!!.jsonPrimitive.content
                }
                val popular = listOf("openai/gpt-4o-mini", "google/gemini-2.5-flash", "anthropic/claude-haiku-4.5", "openai/gpt-5-mini", "meta-llama/llama-3.3-70b-instruct")
                all.map { it["id"]!!.jsonPrimitive.content }
                    .sortedWith(compareBy<String> { id -> popular.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }.thenBy { it !in freeModels }.thenBy { it })
            }
            Engine.CLAUDE -> {
                val client = claudeClient(key)
                try {
                    client.models().list().autoPager().map { it.id() }.toList()
                } catch (e: UnauthorizedException) {
                    throw CloudException("Claude rejected the API key.")
                } catch (e: AnthropicServiceException) {
                    throw CloudException("Claude HTTP ${e.statusCode()}: ${e.message?.take(160)}")
                } finally { client.close() }
            }
            else -> emptyList()
        }
    }

    /** Sends a tiny request; returns a human-readable success line or throws. */
    suspend fun test(engine: Engine, apiKey: String, model: String?): String {
        val start = System.currentTimeMillis()
        val reply = complete(
            engine, apiKey, model,
            system = """Reply with ONLY this JSON: {"reply":"ok","actions":[]}""",
            history = listOf(Turn(true, "ping")),
            maxTokens = 200,
        )
        val ok = runCatching { json.parseToJsonElement(stripFences(reply)).jsonObject.containsKey("reply") }.getOrDefault(false)
        val ms = System.currentTimeMillis() - start
        return if (ok) "Connected to ${model ?: DEFAULT_MODELS[engine]} · ${ms} ms" else "Connected, but the model didn’t return JSON. Try another model."
    }

    fun stripFences(raw: String): String = raw.trim()
        .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        .removeSuffix("```").trim()
        .let { s -> // models sometimes wrap JSON in prose: take the outermost object
            val a = s.indexOf('{'); val b = s.lastIndexOf('}')
            if (a >= 0 && b > a) s.substring(a, b + 1) else s
        }
}

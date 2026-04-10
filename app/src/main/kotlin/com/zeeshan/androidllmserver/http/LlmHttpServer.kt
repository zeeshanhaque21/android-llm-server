package com.zeeshan.androidllmserver.http

import android.util.Log
import com.zeeshan.androidllmserver.llm.LlmBridge
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

private const val TAG = "LlmHttpServer"

/**
 * Manages the Ktor embedded HTTP server that exposes OpenAI-compatible routes.
 *
 * Lifecycle:
 * - [start] spawns the Netty server on a background thread, binding to `0.0.0.0:<port>`.
 * - [stop] gracefully shuts it down with a 2-second grace period.
 *
 * This class does NOT own the [LlmBridge]. The caller (typically the foreground
 * service) is responsible for loading/freeing the model.
 */
class LlmHttpServer(
    private val bridge: LlmBridge,
    private val modelName: String,
    private val port: Int = 8080,
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    fun start() {
        if (server != null) {
            Log.w(TAG, "start() called but server is already running")
            return
        }

        Log.i(TAG, "Starting HTTP server on 0.0.0.0:$port")
        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            configureSerialization()
            configureCors()
            configureStatusPages()
            configureRouting()
        }.also { it.start(wait = false) }
        Log.i(TAG, "HTTP server started on port $port")
    }

    fun stop() {
        server?.let {
            Log.i(TAG, "Stopping HTTP server")
            it.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)
            server = null
            Log.i(TAG, "HTTP server stopped")
        }
    }

    // ── Ktor configuration ──────────────────────────────────────────────────

    private fun Application.configureSerialization() {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    private fun Application.configureCors() {
        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
        }
    }

    private fun Application.configureStatusPages() {
        install(StatusPages) {
            exception<IllegalStateException> { call, cause ->
                Log.e(TAG, "Bad request: ${cause.message}", cause)
                call.respondText(
                    text = """{"error":{"message":"${cause.message?.replace("\"", "\\\"")}","type":"invalid_request_error"}}""",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.BadRequest,
                )
            }
            exception<Throwable> { call, cause ->
                Log.e(TAG, "Internal error", cause)
                call.respondText(
                    text = """{"error":{"message":"Internal server error","type":"server_error"}}""",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.InternalServerError,
                )
            }
        }
    }

    private fun Application.configureRouting() {
        routing {
            installOpenAiRoutes(bridge, modelName)
        }
    }
}

package com.zeeshan.androidllmserver.http

import android.util.Log
import com.zeeshan.androidllmserver.auth.AuthManager
import com.zeeshan.androidllmserver.llm.LlmBridge
import com.zeeshan.androidllmserver.sd.SdBridge
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

private const val TAG = "LlmHttpServer"

class LlmHttpServer(
    private val bridge: LlmBridge?,
    private val modelName: String,
    private val port: Int = 8085,
    private val authManager: AuthManager? = null,
    private val sdBridge: SdBridge? = null,
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
            configureAuth()
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

    private fun Application.configureAuth() {
        val manager = authManager ?: return

        val bearerAuthPlugin = createApplicationPlugin("BearerAuth") {
            onCall { call ->
                val path = call.request.path()
                if (!path.startsWith("/v1")) return@onCall
                if (!manager.authEnabled) return@onCall

                val header = call.request.header(HttpHeaders.Authorization)
                val token = header?.removePrefix("Bearer ")?.trim()

                if (token.isNullOrBlank() || !manager.validateToken(token)) {
                    call.respondText(
                        text = """{"error":{"message":"Invalid or missing bearer token","type":"authentication_error"}}""",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.Unauthorized,
                    )
                }
            }
        }
        install(bearerAuthPlugin)
    }

    private fun Application.configureRouting() {
        routing {
            if (bridge != null) {
                installOpenAiRoutes(bridge, modelName)
                installOllamaRoutes(bridge, modelName)
            }
            installImageRoutes(sdBridge)
        }
    }
}

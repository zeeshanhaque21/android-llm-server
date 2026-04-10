package com.zeeshan.androidllmserver.http

import com.zeeshan.androidllmserver.sd.SdBridge
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImageGenerationRequest(
    val prompt: String,
    @SerialName("negative_prompt") val negativePrompt: String = "",
    val n: Int = 1,
    val size: String = "512x512",
    val model: String = "",
    val steps: Int = 20,
    @SerialName("cfg_scale") val cfgScale: Float = 7.0f,
    val seed: Long = -1,
)

@Serializable
data class ImageGenerationResponse(
    val created: Long,
    val data: List<ImageData>,
)

@Serializable
data class ImageData(
    @SerialName("b64_json") val b64Json: String = "",
    val url: String = "",
    @SerialName("revised_prompt") val revisedPrompt: String = "",
)

fun Routing.installImageRoutes(sdBridge: SdBridge?) {
    post("/v1/images/generations") {
        if (sdBridge == null || !sdBridge.isLoaded) {
            call.respondText(
                """{"error":{"message":"No image generation model loaded","type":"model_not_loaded"}}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.ServiceUnavailable,
            )
            return@post
        }

        val request = call.receive<ImageGenerationRequest>()
        val sizeParts = request.size.split("x")
        val width = sizeParts.getOrNull(0)?.toIntOrNull() ?: 512
        val height = sizeParts.getOrNull(1)?.toIntOrNull() ?: 512

        val base64Image = sdBridge.generate(
            prompt = request.prompt,
            negativePrompt = request.negativePrompt,
            width = width,
            height = height,
            steps = request.steps,
            cfgScale = request.cfgScale,
            seed = request.seed,
        )

        if (base64Image.isEmpty()) {
            call.respondText(
                """{"error":{"message":"Image generation failed","type":"generation_error"}}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.InternalServerError,
            )
            return@post
        }

        call.respond(
            ImageGenerationResponse(
                created = System.currentTimeMillis() / 1000,
                data = listOf(ImageData(b64Json = base64Image)),
            ),
        )
    }
}

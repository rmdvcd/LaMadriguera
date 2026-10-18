package com.lamadriguera.app.data.moderation

import com.lamadriguera.app.BuildConfig
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.repository.ImageModerationScanner
import com.lamadriguera.app.domain.repository.ImageScanResult
import com.lamadriguera.app.domain.repository.ImageVerdict
import com.lamadriguera.app.domain.usecase.NsfwHeuristics
import com.lamadriguera.app.domain.usecase.NsfwScoring
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Escáner NSFW en cliente, ANTES de subir la imagen.
 *
 * Motor principal: NSFW.js (TensorFlow.js) ejecutado dentro de un WebView, con
 * el bundle/modelo configurable por la variable de entorno `NSFWJS_MODEL_URL`.
 * Respaldo offline: [NsfwHeuristics] sobre los píxeles decodificados.
 * Si ambos motores fallan → DUBIOUS («si es dudoso, no se sube»).
 */
@Singleton
class NsfwJsImageScanner @Inject constructor(
    @ApplicationContext private val context: Context
) : ImageModerationScanner {

    override suspend fun scan(bytes: ByteArray): Result<ImageScanResult> {
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        val heuristics: ImageScanResult? = decoded?.let { bitmap ->
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            NsfwHeuristics.verdictFor(pixels, bitmap.width, bitmap.height)
        }

        val modelUrl = BuildConfig.NSFWJS_MODEL_URL.trim()
        if (modelUrl.isNotEmpty() && decoded != null) {
            val engineResult = runCatching {
                withTimeout(SCAN_TIMEOUT_MS) { runNsfwJs(bytes, modelUrl) }
            }.getOrNull()
            if (engineResult != null) return Result.Success(engineResult)
        }

        return Result.Success(
            heuristics ?: ImageScanResult(ImageVerdict.DUBIOUS, engine = "unavailable")
        )
    }

    private suspend fun runNsfwJs(bytes: ByteArray, modelUrl: String): ImageScanResult =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val bridge = object {
                    @JavascriptInterface
                    fun onResult(json: String) {
                        val predictions = JSONObject(json)
                        val scores = mutableMapOf<String, Float>()
                        for (i in 0 until predictions.length()) {
                            val name = predictions.names()?.getString(i) ?: continue
                            scores[name] = predictions.getDouble(name).toFloat()
                        }
                        if (continuation.isActive) {
                            continuation.resume(NsfwScoring.verdictForScores(scores, engine = "nsfwjs"))
                        }
                    }

                    @JavascriptInterface
                    fun onError(message: String) {
                        if (continuation.isActive) {
                            continuation.resume(
                                ImageScanResult(ImageVerdict.DUBIOUS, engine = "nsfwjs-error")
                            )
                        }
                    }
                }
                val webView = WebView(context)
                continuation.invokeOnCancellation { webView.destroy() }
                webView.settings.javaScriptEnabled = true
                webView.settings.allowFileAccess = false
                webView.settings.allowContentAccess = false
                webView.addJavascriptInterface(bridge, "LaMadrigueraNsfw")
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript("classifyBase64('$base64');", null)
                    }
                }
                webView.loadDataWithBaseURL(
                    modelUrl,
                    nsfwHtml(modelUrl),
                    "text/html",
                    "utf-8",
                    null
                )
            }
        }

    private fun nsfwHtml(modelUrl: String): String = """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <script src="$modelUrl"></script>
        </head>
        <body>
        <script>
          async function classifyBase64(base64) {
            try {
              const image = new Image();
              image.onload = async () => {
                try {
                  const model = await window.nsfwjs.load();
                  const predictions = await model.classify(image);
                  const scores = {};
                  for (const prediction of predictions) scores[prediction.className] = prediction.probability;
                  window.LaMadrigueraNsfw.onResult(JSON.stringify(scores));
                } catch (error) {
                  window.LaMadrigueraNsfw.onError(String(error));
                }
              };
              image.onerror = () => window.LaMadrigueraNsfw.onError('decode');
              image.src = 'data:image/jpeg;base64,' + base64;
            } catch (error) {
              window.LaMadrigueraNsfw.onError(String(error));
            }
          }
        </script>
        </body>
        </html>
    """.trimIndent()

    private companion object {
        const val SCAN_TIMEOUT_MS = 8_000L
    }
}

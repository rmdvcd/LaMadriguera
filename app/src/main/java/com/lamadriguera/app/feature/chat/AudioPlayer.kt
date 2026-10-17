package com.lamadriguera.app.feature.chat

import android.content.Context
import android.media.MediaPlayer
import java.io.File

/**
 * Reproductor efímero para audios del chat: escribe los bytes descifrados en
 * caché, reproduce y borra el archivo al terminar o al cambiar de audio.
 */
object AudioPlayer {

    private var player: MediaPlayer? = null
    private var tempFile: File? = null

    @Synchronized
    fun play(context: Context, bytes: ByteArray, mimeType: String) {
        stop()
        val extension = when (mimeType) {
            "audio/mpeg", "audio/mp3" -> ".mp3"
            "audio/ogg" -> ".ogg"
            "audio/wav", "audio/x-wav" -> ".wav"
            "audio/aac" -> ".aac"
            else -> ".m4a"
        }
        val file = File.createTempFile("chat_audio_", extension, context.cacheDir).apply {
            writeBytes(bytes)
        }
        tempFile = file
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            setOnCompletionListener { stop() }
            start()
        }
    }

    @Synchronized
    fun stop() {
        runCatching { player?.release() }
        player = null
        tempFile?.delete()
        tempFile = null
    }
}

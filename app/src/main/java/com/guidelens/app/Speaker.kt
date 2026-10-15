package com.guidelens.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * TextToSpeech wrapper enforcing blind-user requirements:
 *  - queue cap (2 pending): fresh info beats stale info — speech can never lag
 *    minutes behind reality (the old "it doesn't stop talking" bug)
 *  - priority speak: flushes the queue and cuts off any non-priority utterance
 *  - coalescing: the same sentence is never queued twice
 *  - repeat(): last message on demand (double-tap anywhere)
 *  - TTS language follows the system locale (Urdu device -> Urdu voice) with
 *    an automatic fallback to English if the engine lacks the voice data
 */
class Speaker(private val context: Context, private val prefs: Prefs) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech = TextToSpeech(context, this)
    @Volatile private var ready = false
    private val lock = Any()
    private val queue = ArrayDeque<Pair<String, Boolean>>()
    private var current: Pair<String, Boolean>? = null
    private var lastText: String? = null
    private var seq = 0L

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        applyLanguage()
        ready = true
        synchronized(lock) { pumpLocked() }
    }

    private fun applyLanguage() {
        val target = if (Locale.getDefault().language == "ur") Locale("ur", "PK") else Locale.US
        val r = tts.setLanguage(target)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US)
        }
        tts.setSpeechRate(prefs.rate)
    }

    fun speak(text: String, priority: Boolean = false) {
        if (!prefs.audio) return
        synchronized(lock) {
            if (priority) {
                queue.clear()
                val cur = current
                if (cur != null && !cur.second) {
                    try { tts.stop() } catch (e: Exception) { }
                    current = null
                }
            }
            if (current?.first == text) return
            if (queue.any { it.first == text }) return
            if (queue.size >= 2) queue.removeFirst()
            queue.addLast(text to priority)
            pumpLocked()
        }
    }

    private fun pumpLocked() {
        if (!ready || current != null || queue.isEmpty()) return
        val item = queue.removeFirst()
        current = item
        lastText = item.first
        val id = "gl${++seq}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { done() }
            override fun onError(utteranceId: String?, errorCode: Int) { done() }
            override fun onDone(id: String?) { done() }
            private fun done() {
                synchronized(lock) { current = null; pumpLocked() }
            }
        })
        tts.speak(item.first, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun repeat() {
        lastText?.let { speak(it) }
    }

    fun setRate(r: Float) {
        try { tts.setSpeechRate(r) } catch (e: Exception) { }
    }

    fun shutdown() {
        try { tts.stop(); tts.shutdown() } catch (e: Exception) { }
    }
}

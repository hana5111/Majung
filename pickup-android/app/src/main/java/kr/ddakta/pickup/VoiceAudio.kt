package kr.ddakta.pickup

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Foreground, user-started turns only. The appointment server never receives audio. */
class VoiceAudio(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onHeard: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    val available: Boolean = runCatching { SpeechRecognizer.isRecognitionAvailable(app) }.getOrDefault(false)
    private val manager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                stop(); if (!closed) onError("다른 음성 안내가 시작되어 대화를 멈췄어요. 이어가기를 눌러 주세요.")
            }
        }.build()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var initialized = false
    private var canSpeak = false
    private var active = false
    private var closed = false
    private var token = 0
    private var pending: Pair<Int, String>? = null
    private var speakingId: String? = null

    init {
        tts = TextToSpeech(app) { status -> main.post {
            if (!closed) {
                initialized = true
                canSpeak = status == TextToSpeech.SUCCESS && (tts?.setLanguage(Locale.KOREAN) ?: -1) >= 0
                tts?.setAudioAttributes(attributes)
                pending?.let { (id, text) -> pending = null; speakOrListen(id, text) }
            }
        } }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { main.post {
                if (utteranceId == speakingId && active && !closed) {
                    val id = token
                    speakingId = null
                    main.postDelayed({ if (valid(id)) listen(id) }, 250)
                }
            } }
            @Deprecated("Android legacy callback")
            override fun onError(utteranceId: String?) { main.post {
                if (utteranceId == speakingId && active && !closed) { speakingId = null; canSpeak = false; listen(token) }
            } }
        })
    }

    private fun valid(id: Int) = !closed && active && id == token
    fun ask(text: String) {
        if (closed) return
        stop()
        active = true
        val id = token
        if (!available) { fail(id, "이 기기의 음성 인식 서비스를 사용할 수 없어요. 아래에서 대화를 직접 입력할 수 있어요."); return }
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail(id, "음성을 사용 중인 다른 기능이 있어요. 잠시 후 이어가기를 눌러 주세요."); return
        }
        if (initialized) speakOrListen(id, text)
        else {
            pending = id to text
            onStatus("음성 안내 준비 중")
            main.postDelayed({
                if (valid(id) && pending?.first == id) {
                    pending = null
                    // An unavailable TTS engine must not leave the conversation stuck.
                    listen(id)
                }
            }, 4000)
        }
    }

    private fun speakOrListen(id: Int, text: String) {
        if (!valid(id)) return
        if (!canSpeak) { listen(id); return }
        onStatus("안내하고 있어요")
        val utterance = "ddakta-$id"
        speakingId = utterance
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utterance)
        if (result != TextToSpeech.SUCCESS) { speakingId = null; listen(id) }
        else main.postDelayed({
            if (valid(id) && speakingId == utterance) {
                tts?.stop(); speakingId = null
                fail(id, "음성 안내가 응답하지 않아 대화를 멈췄어요. 화면 내용을 확인하고 이어가 주세요.")
            }
        }, 45000)
    }

    private fun listen(id: Int) {
        if (!valid(id)) return
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(app)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if(valid(id)) onStatus("듣고 있어요") }
                override fun onBeginningOfSpeech() { if(valid(id)) onStatus("듣고 있어요") }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if(valid(id)) onStatus("말씀을 확인하고 있어요") }
                override fun onError(error: Int) {
                    if (!valid(id)) return
                    fail(id, when(error) {
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> "말씀을 듣지 못했어요. 이어가기를 누르고 다시 말씀해 주세요."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요해요. 다시 시작하거나 직접 입력해 주세요."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "음성 인식 연결이 끊겼어요. 연결 후 이어가기를 눌러 주세요."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "이 기기에서 한국어 음성 인식을 사용할 수 없어요. 직접 입력해 주세요."
                        else -> "기기의 음성 인식 서비스가 응답하지 않아요. 다시 시작하거나 직접 입력해 주세요."
                    })
                }
                override fun onResults(results: Bundle?) {
                    if (!valid(id)) return
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (heard.isNullOrBlank()) { fail(id, "말씀을 듣지 못했어요. 이어가기를 눌러 주세요."); return }
                    active = false
                    recognizer?.destroy(); recognizer = null
                    manager.abandonAudioFocusRequest(focus)
                    onStatus("내용을 정리하고 있어요")
                    onHeard(heard)
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit // Partial text can never confirm an invitation.
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            onStatus("마이크 연결 중")
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
            main.postDelayed({ if(valid(id)) fail(id, "음성 인식 응답이 지연되어 대화를 멈췄어요. 이어가기를 눌러 주세요.") }, 35000)
        } catch (_: SecurityException) { fail(id, "마이크 권한을 확인해 주세요. 직접 입력도 가능해요.") }
        catch (_: Exception) { fail(id, "음성 인식을 시작하지 못했어요. 다시 시작하거나 직접 입력해 주세요.") }
    }

    private fun fail(id: Int, message: String) {
        if (!valid(id)) return
        stop(); onError(message)
    }
    fun stop() {
        token++; active = false; pending = null; speakingId = null
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        tts?.stop()
        manager.abandonAudioFocusRequest(focus)
    }
    fun close() {
        closed = true; stop(); main.removeCallbacksAndMessages(null); tts?.shutdown(); tts = null
    }
}

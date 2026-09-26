package com.charleshartman.porchlightpress.ui.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.BreakIterator
import java.util.Locale

data class SpeechState(
    val playing: Boolean = false,
    val title: String = "",
    val sentence: String = "",
    val index: Int = -1,
    val count: Int = 0,
    val totalSeconds: Int = 0,
    val remainingSeconds: Int = 0,
    val error: String? = null,
) {
    constructor(
        playing: Boolean,
        title: String,
        sentence: String,
        index: Int,
        count: Int,
        error: String? = null,
    ) : this(playing, title, sentence, index, count, 0, 0, error)
}

data class SpeechItem(val title: String, val text: String)

object SpeechController {
    private val mutable = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = mutable
    internal fun publish(value: SpeechState) { mutable.value = value }

    fun play(context: Context, title: String, body: String, language: String, speed: Float = 1f) {
        playQueue(context, listOf(SpeechItem(title, body)), language, speed)
    }

    fun playQueue(context: Context, stories: List<SpeechItem>, language: String, speed: Float = 1f) {
        if (stories.isEmpty()) return
        val intent = Intent(context, SpeechService::class.java).apply {
            action = SpeechService.PLAY
            putStringArrayListExtra("titles", ArrayList(stories.map(SpeechItem::title)))
            putStringArrayListExtra("texts", ArrayList(stories.map(SpeechItem::text)))
            putExtra("language", language)
            putExtra("speed", speed)
        }
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    fun pause(context: Context) {
        command(context, SpeechService.PAUSE)
    }

    fun resume(context: Context) {
        command(context, SpeechService.RESUME)
    }

    fun seekTo(context: Context, sentenceIndex: Int) {
        val intent = Intent(context, SpeechService::class.java).apply {
            action = SpeechService.SEEK_TO
            putExtra("index", sentenceIndex)
        }
        context.startService(intent)
    }

    fun stop(context: Context) {
        command(context, SpeechService.STOP)
    }

    fun command(context: Context, actionName: String) {
        context.startService(Intent(context, SpeechService::class.java).apply { action = actionName })
    }
}

class SpeechService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val PLAY = "com.charleshartman.porchlightpress.speech.PLAY"
        const val PAUSE = "com.charleshartman.porchlightpress.speech.PAUSE"
        const val RESUME = "com.charleshartman.porchlightpress.speech.RESUME"
        const val NEXT = "com.charleshartman.porchlightpress.speech.NEXT"
        const val PREVIOUS = "com.charleshartman.porchlightpress.speech.PREVIOUS"
        const val STOP = "com.charleshartman.porchlightpress.speech.STOP"
        const val SEEK_TO = "com.charleshartman.porchlightpress.speech.SEEK_TO"
        private const val CHANNEL = "read_aloud"
    }

    private lateinit var session: MediaSession
    private var tts: TextToSpeech? = null
    private var ready = false
    private var sentences = emptyList<String>()
    private var stories = emptyList<SpeechItem>()
    private var storyIndex = 0
    private var index = 0
    private var title = ""
    private var language = Locale.ENGLISH
    private var speed = 1f
    private var playing = false
    private var focus: AudioFocusRequest? = null
    private lateinit var audio: AudioManager

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Read aloud", NotificationManager.IMPORTANCE_LOW),
        )
        session = MediaSession(this, "Porchlight speech").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onStop() { stopPlayback() }
                override fun onSkipToNext() { next() }
                override fun onSkipToPrevious() { previous() }
                override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
            })
            isActive = true
        }
        tts = TextToSpeech(this, this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PLAY -> {
                val titles = intent.getStringArrayListExtra("titles").orEmpty()
                val texts = intent.getStringArrayListExtra("texts").orEmpty()
                stories = titles.zip(texts).map { SpeechItem(it.first, it.second) }
                storyIndex = 0
                title = stories.firstOrNull()?.title.orEmpty()
                language = Locale.forLanguageTag(intent.getStringExtra("language") ?: "en")
                speed = intent.getFloatExtra("speed", 1f).coerceIn(0.5f, 2f)
                sentences = splitSentences(stories.firstOrNull()?.text.orEmpty(), language)
                index = 0
                playing = sentences.isNotEmpty()
                startForeground(1305, notification())
                update()
                if (ready && playing) speak()
            }
            PAUSE -> pause()
            RESUME -> resume()
            NEXT -> next()
            PREVIOUS -> previous()
            SEEK_TO -> {
                val target = intent.getIntExtra("index", index)
                seekTo(target)
            }
            STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) { stopPlayback(); return }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) {
                Log.w("Porchlight", "Speech failed: $utteranceId")
                finishSentence()
            }
            override fun onDone(utteranceId: String?) { finishSentence() }
        })
        if (playing) speak()
    }

    private fun finishSentence() {
        if (!playing) return
        index++
        if (index < sentences.size) speak() else next()
    }

    private fun speak() {
        if (!ready || !playing || index !in sentences.indices) return
        if (focus == null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setOnAudioFocusChangeListener { change ->
                    if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
                }.build()
        }
        if (audio.requestAudioFocus(focus!!) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            pause()
            return
        }
        val offlineVoice = tts?.voices?.firstOrNull { voice ->
            !voice.isNetworkConnectionRequired && voice.locale.language == language.language
        } ?: tts?.voices?.firstOrNull { voice -> voice.locale.language == language.language }
          ?: tts?.defaultVoice
        if (offlineVoice != null) {
            tts?.voice = offlineVoice
        } else {
            val res = tts?.setLanguage(language)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                playing = false
                SpeechController.publish(
                    SpeechState(
                        error = "No offline voice is installed for ${language.displayLanguage}. Install one in Android text-to-speech settings.",
                    ),
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
        }
        tts?.setSpeechRate(speed)
        tts?.speak(sentences[index], TextToSpeech.QUEUE_FLUSH, null, "sentence-$index")
        update()
    }

    private fun pause() {
        playing = false
        tts?.stop()
        focus?.let(audio::abandonAudioFocusRequest)
        update()
    }

    private fun resume() {
        if (sentences.isEmpty()) return
        playing = true
        speak()
    }

    private fun seekTo(targetIndex: Int) {
        if (sentences.isEmpty()) return
        index = targetIndex.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
        if (ready && playing) {
            tts?.stop()
            speak()
        } else {
            update()
        }
    }

    private fun next() {
        if (index + 1 < sentences.size) {
            index++
            if (ready && playing) {
                tts?.stop()
                speak()
            } else {
                update()
            }
        } else if (storyIndex + 1 < stories.size) {
            storyIndex++
            title = stories[storyIndex].title
            sentences = splitSentences(stories[storyIndex].text, language)
            index = 0
            if (ready && playing) {
                tts?.stop()
                speak()
            } else {
                update()
            }
        } else {
            stopPlayback()
        }
    }

    private fun previous() {
        if (index > 0) {
            index--
            if (ready && playing) {
                tts?.stop()
                speak()
            } else {
                update()
            }
        } else if (storyIndex > 0) {
            storyIndex--
            title = stories[storyIndex].title
            sentences = splitSentences(stories[storyIndex].text, language)
            index = 0
            if (ready && playing) {
                tts?.stop()
                speak()
            } else {
                update()
            }
        } else {
            index = 0
            if (ready && playing) {
                tts?.stop()
                speak()
            } else {
                update()
            }
        }
    }

    private fun update() {
        val totalSec = calculateSpeechSeconds(sentences, 0, speed)
        val remainingSec = calculateSpeechSeconds(sentences, index, speed)
        SpeechController.publish(
            SpeechState(
                playing = playing,
                title = title,
                sentence = sentences.getOrNull(index).orEmpty(),
                index = index,
                count = sentences.size,
                totalSeconds = totalSec,
                remainingSeconds = remainingSec,
                error = null,
            ),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO,
                )
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, index.toLong(), speed)
                .build(),
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1305, notification())
    }

    private fun stopPlayback() {
        playing = false
        tts?.stop()
        sentences = emptyList()
        stories = emptyList()
        index = 0
        focus?.let(audio::abandonAudioFocusRequest)
        SpeechController.publish(SpeechState())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pending(actionName: String, request: Int): PendingIntent = PendingIntent.getService(
        this,
        request,
        Intent(this, SpeechService::class.java).apply { action = actionName },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle(title.ifBlank { "Porchlight Press" })
        .setContentText(sentences.getOrNull(index) ?: "Read aloud")
        .setOnlyAlertOnce(true)
        .setOngoing(playing)
        .addAction(android.R.drawable.ic_media_previous, "Previous", pending(PREVIOUS, 1))
        .addAction(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) "Pause" else "Play",
            pending(if (playing) PAUSE else RESUME, 2),
        )
        .addAction(android.R.drawable.ic_media_next, "Next", pending(NEXT, 3))
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pending(STOP, 4))
        .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
        .build()

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        session.release()
        focus?.let(audio::abandonAudioFocusRequest)
        super.onDestroy()
    }
}

fun calculateSpeechSeconds(sentencesList: List<String>, fromIndex: Int, speechSpeed: Float): Int {
    if (fromIndex >= sentencesList.size) return 0
    val effectiveSpeed = speechSpeed.coerceIn(0.5f, 2.0f)
    var words = 0
    for (i in fromIndex until sentencesList.size) {
        val s = sentencesList[i]
        var inWord = false
        var count = 0
        for (c in s) {
            if (c.isWhitespace()) {
                inWord = false
            } else {
                if (!inWord) {
                    inWord = true
                    count++
                }
            }
        }
        words += maxOf(1, count)
    }
    return kotlin.math.max(1, (words / (2.5f * effectiveSpeed)).toInt())
}

fun formatTimeMmSs(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val m = s / 60
    val remS = s % 60
    return "$m:${if (remS < 10) "0$remS" else "$remS"}"
}

fun splitSentences(text: String, locale: Locale): List<String> {
    val iterator = BreakIterator.getSentenceInstance(locale)
    iterator.setText(text)
    val result = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        val sentence = text.substring(start, end).trim()
        if (sentence.isNotBlank()) result.add(sentence)
        start = end
        end = iterator.next()
    }
    return result
}

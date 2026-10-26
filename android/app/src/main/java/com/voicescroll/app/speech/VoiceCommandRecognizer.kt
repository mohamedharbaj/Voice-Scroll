package com.voicescroll.app.speech

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.voicescroll.app.data.PreferencesManager
import com.voicescroll.app.engine.CommandEngine
import com.voicescroll.app.engine.CommandMatchResult
import com.voicescroll.app.model.CommandType

/**
 * Continuous Speech Recognition Manager (Android SpeechRecognizer) + matching محلي ضد القاموس ديال الأوامر.
 *
 * الإصلاحات الأساسية فهاد النسخة:
 *  1. أمر واحد = تنفيذ واحد: من بعد ما كنلقاو الأمر فـ partial result كنحبسو الجلسة (cancel) ونعاودو نبداو،
 *     باش النتيجة النهائية (final) ما تعاودش تنفذ نفس الأمر مرة أخرى (كان كيدوز فيديوهين).
 *  2. ما بقيناش كنفرضو PREFER_OFFLINE (ar-MA ما عندوش موديل offline فأغلب الهواتف -> أخطاء 12/13 كتعاود بلا نهاية
 *     والتطبيق كيبان "ما كيسمعش").
 *  3. حيدنا extra "android.speech.extra.AUDIO_SOURCE" (فأندرويد 13+ كيتسناو ParcelFileDescriptor ماشي int).
 *  4. معالجة أخطاء اللغة (12/13) بـ fallback أوتوماتيكي (ar-MA -> ar-SA -> لغة النظام).
 *  5. Backoff فإعادة التشغيل (بلا حلقة كل 25ms) + إعادة إنشاء الـ recognizer عند أخطاء CLIENT/BUSY/DISCONNECTED.
 */
class VoiceCommandRecognizer(
    private val context: Context,
    private val listener: Callback
) : RecognitionListener {

    companion object {
        private const val TAG = "VoiceRecognizer"
        private const val RESTART_NORMAL_DELAY_MS = 60L
        private const val RESTART_AFTER_COMMAND_MS = 120L
        private const val RESTART_BUSY_DELAY_MS = 350L
        private const val MAX_BACKOFF_MS = 3000L
        private const val DUPLICATE_WINDOW_MS = 350L

        // فالوضع متعدد اللغات: إلا ما بدا حتى كلام فهاد المدة نبدلو للغة الموالية
        private const val IDLE_LANGUAGE_SLICE_MS = 2200L

        // اللغات اللي كندورو عليها فالوضع "كل اللغات"
        private val ALL_LANGUAGES = listOf("ar-MA", "en-US", "fr-FR")

        private val BIASING_WORDS = arrayListOf(
            // دارجة / فصحى
            "دوز", "طلع", "هبط", "رجع", "وقف", "حبس", "صافي", "كمل", "تابع", "شغل",
            "التالي", "السابق", "دوزها", "طلعها", "هبطها", "رجعها", "ايقاف مؤقت",
            // English
            "next", "up", "previous", "down", "back", "stop", "resume", "pause", "play",
            "scroll up", "scroll down", "next video", "previous video",
            // Français
            "suivant", "precedent", "monte", "descends", "arrete", "continue", "pause"
        )

        // ثوابت SpeechRecognizer الجديدة (API 31+) كنستعملوها كأرقام باش نبقاو متوافقين مع minSdk 24
        private const val ERROR_TOO_MANY_REQUESTS = 10
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }

    interface Callback {
        fun onListeningStateChanged(isListening: Boolean, statusMessage: String)
        fun onRmsChanged(rmsdB: Float)
        fun onCommandMatched(result: CommandMatchResult)
        fun onUnrecognizedSpeech(rawText: String)
        fun onError(errorCode: Int, errorMessage: String)
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefsManager = PreferencesManager(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile
    private var isIntentionallyListening = false
    private var isRecognizing = false
    private var isInitialStart = true
    private var beepsMuted = false

    // true من بعد ما تنفذ أمر فالجلسة الحالية -> نتجاهلو أي نتيجة أخرى حتى تبدأ جلسة جديدة
    private var commandFiredThisSession = false
    private var lastDispatchedCommand: CommandType = CommandType.NONE
    private var lastDispatchedTime: Long = 0L

    // عدد الأخطاء الحقيقية المتتالية (للـ backoff)
    private var errorStreak = 0

    // اللغات اللي الخدمة قالت ما كتدعمهاش (خطأ 12/13) -> كنحيدوها أو كنعوضوها (ar-MA -> ar-SA)
    private val unsupportedLanguages = mutableSetOf<String>()
    private var useSystemDefaultLanguage = false

    // الـ recognizer كيسمع بلغة وحدة فكل جلسة -> كندورو بين اللغات باش كل الأوامر (دارجة/عربية/English/Français) تتنفذ
    private var languageIndex = 0
    private var currentLanguage: String? = null
    private var speechBegun = false
    private val idleLanguageRunnable = Runnable { onIdleSliceElapsed() }

    init {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ensureRecognizerInstance()
        } else {
            mainHandler.post { ensureRecognizerInstance() }
        }
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition is NOT available on this device!")
            listener.onError(
                -1,
                "خدمة التعرف على الصوت غير متوفرة. تأكد أن تطبيق Google مثبت ومحدّث ومفعّل"
            )
            return
        }

        isIntentionallyListening = true
        isInitialStart = true
        errorStreak = 0
        unsupportedLanguages.clear()
        useSystemDefaultLanguage = false
        languageIndex = 0
        Log.i(TAG, "startListening requested")

        if (Looper.myLooper() == Looper.getMainLooper()) {
            startListeningInternal()
        } else {
            mainHandler.post { startListeningInternal() }
        }
    }

    fun stopListening() {
        Log.i(TAG, "stopListening requested")
        isIntentionallyListening = false
        isInitialStart = true
        mainHandler.removeCallbacksAndMessages(null)

        val action = {
            try {
                if (isRecognizing) {
                    speechRecognizer?.cancel()
                }
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying speech recognizer", e)
            } finally {
                speechRecognizer = null
                isRecognizing = false
                unmuteBeepStreams()
                listener.onListeningStateChanged(false, "التحكم الصوتي متوقف")
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun startListeningInternal() {
        if (!isIntentionallyListening) return

        try {
            beginSession()
            listener.onListeningStateChanged(true, "يستمع إلى صوتك…")
        } catch (e: Exception) {
            Log.e(TAG, "Error invoking startListening, recovering...", e)
            recreateAndRestart(RESTART_BUSY_DELAY_MS)
        }
    }

    private fun ensureRecognizerInstance() {
        if (speechRecognizer == null) {
            Log.i(TAG, "Creating SpeechRecognizer instance")
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(this@VoiceCommandRecognizer)
            }
        }
    }

    /** نمحيو الـ recognizer القديم ونخلقو واحد جديد من بعد delay. */
    private fun recreateAndRestart(delayMs: Long) {
        if (!isIntentionallyListening) return
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
        isRecognizing = false

        mainHandler.postDelayed({
            if (isIntentionallyListening) startListeningInternal()
        }, delayMs)
    }

    /** نعاودو الجلسة بنفس الـ recognizer (أسرع، وما كيدير بيب بزاف). */
    private fun scheduleRestart(delayMs: Long = RESTART_NORMAL_DELAY_MS) {
        if (!isIntentionallyListening) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!isIntentionallyListening) return@postDelayed
            try {
                muteBeepStreams()
                beginSession()
            } catch (e: Exception) {
                Log.w(TAG, "Failed quick restart, doing full re-init: ${e.message}")
                recreateAndRestart(RESTART_BUSY_DELAY_MS)
            }
        }, delayMs)
    }

    /** كيبدا جلسة استماع جديدة باللغة الحالية ويفعّل مؤقت تبديل اللغة. */
    private fun beginSession() {
        ensureRecognizerInstance()
        commandFiredThisSession = false
        speechBegun = false
        currentLanguage = pickLanguage()
        isRecognizing = true
        speechRecognizer?.startListening(createRecognizerIntent(currentLanguage))
        armIdleWatchdog()
    }

    /** اللغات النشطة حسب اختيار المستخدم، بعد حذف/تعويض اللغات غير المدعومة. */
    private fun activeLanguages(): List<String> {
        if (useSystemDefaultLanguage) return emptyList()
        val chosen = prefsManager.commandLanguage
        val base = when (chosen) {
            PreferencesManager.LANG_AUTO -> ALL_LANGUAGES
            else -> listOf(chosen)
        }
        val result = LinkedHashSet<String>()
        for (lang in base) {
            when {
                lang !in unsupportedLanguages -> result.add(lang)
                lang == "ar-MA" && "ar-SA" !in unsupportedLanguages -> result.add("ar-SA")
            }
        }
        return result.toList()
    }

    private fun pickLanguage(): String? {
        val langs = activeLanguages()
        if (langs.isEmpty()) return null
        return langs[languageIndex % langs.size]
    }

    private fun advanceLanguage() {
        if (activeLanguages().size > 1) languageIndex++
    }

    private fun armIdleWatchdog() {
        mainHandler.removeCallbacks(idleLanguageRunnable)
        if (activeLanguages().size > 1) {
            mainHandler.postDelayed(idleLanguageRunnable, IDLE_LANGUAGE_SLICE_MS)
        }
    }

    /** ما تكلم حتى واحد فهاد اللغة: نوقفو الجلسة ونبداو بالغة الموالية. */
    private fun onIdleSliceElapsed() {
        if (!isIntentionallyListening || speechBegun || commandFiredThisSession) return
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }
        isRecognizing = false
        advanceLanguage()
        scheduleRestart(40L)
    }

    /** Backoff تدريجي: 100ms, 200ms, 400ms ... حتى 3s. */
    private fun backoffDelay(): Long {
        val shift = (errorStreak - 1).coerceIn(0, 5)
        return (100L shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun muteBeepStreams() {
        if (beepsMuted) return
        try {
            audioManager?.let { am ->
                am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                beepsMuted = true
            }
        } catch (_: Exception) {
            // بعض الهواتف (DND) كتمنع هادشي، ماشي مشكل
        }
    }

    private fun unmuteBeepStreams() {
        if (!beepsMuted) return
        try {
            audioManager?.let { am ->
                am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            }
        } catch (_: Exception) {
        } finally {
            beepsMuted = false
        }
    }

    private fun createRecognizerIntent(lang: String?): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

            // كنطلبو بزاف فرضيات: إلا كان صوت الفيديو هو الأول، أمرنا يبقى فـ الفرضيات الأخرى
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)

            // ملاحظة: ما كنفرضوش PREFER_OFFLINE. الموديل offline ديال ar-MA غالبا ماشي مثبت،
            // وفرضو كان كيسبب أخطاء لغة كتعاود بلا نهاية.

            // بيب/أصوات النظام
            putExtra("android.speech.extra.SUPPRESS_START_SOUND", true)
            putExtra("android.speech.extra.SUPPRESS_STOP_SOUND", true)
            putExtra("android.speech.extras.SPEECH_INPUT_QUIET_MODE", true)

            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 100L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)

            // لغات إضافية (كتعتمدها بعض خدمات Google؛ وإلا ما عرفاتهاش كتتجاهلها)
            putStringArrayListExtra(
                "android.speech.extra.ADDITIONAL_LANGUAGES",
                ArrayList(ALL_LANGUAGES.filter { it != lang })
            )

            // Biasing: EXTRA_BIASING_STRINGS كاين من أندرويد 13 (API 33)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, BIASING_WORDS)
            }

            if (lang != null) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            }
        }
    }

    // --- RecognitionListener ---

    override fun onReadyForSpeech(params: Bundle?) {
        if (isInitialStart) {
            isInitialStart = false
            muteBeepStreams()
        }
        if (isIntentionallyListening) {
            listener.onListeningStateChanged(true, "يستمع إلى صوتك…")
        }
    }

    override fun onBeginningOfSpeech() {
        speechBegun = true
        mainHandler.removeCallbacks(idleLanguageRunnable)
        errorStreak = 0
        if (isIntentionallyListening) {
            listener.onListeningStateChanged(true, "جارٍ التقاط الصوت…")
        }
    }

    override fun onRmsChanged(rmsdB: Float) {
        listener.onRmsChanged(rmsdB)
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        isRecognizing = false
    }

    override fun onError(error: Int) {
        isRecognizing = false
        Log.d(TAG, "SpeechRecognizer onError: code=$error")

        if (!isIntentionallyListening) return

        // نتيجة الأمر تنفذات من قبل (partial) -> الخطأ ديال الجلسة القديمة ما يهمناش
        if (commandFiredThisSession) {
            scheduleRestart(RESTART_AFTER_COMMAND_MS)
            return
        }

        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                isIntentionallyListening = false
                unmuteBeepStreams()
                listener.onError(error, "يرجى منح إذن الميكروفون")
            }

            // أخطاء عادية (صمت / ما فهمش) : نعاودو بسرعة بلا ما نعتبروها مشكل
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                errorStreak = 0
                advanceLanguage()
                scheduleRestart(RESTART_NORMAL_DELAY_MS)
            }

            // اللغة ما مدعومة / ما محملاش: نحيدوها (ar-MA كنعوضوها بـ ar-SA) ونكملو بالباقي
            ERROR_LANGUAGE_NOT_SUPPORTED,
            ERROR_LANGUAGE_UNAVAILABLE -> {
                currentLanguage?.let { unsupportedLanguages.add(it) }
                if (activeLanguages().isEmpty()) {
                    if (!useSystemDefaultLanguage) {
                        Log.w(TAG, "No supported language left -> system default")
                        useSystemDefaultLanguage = true
                    } else {
                        errorStreak++
                        listener.onError(error, "لغة التعرف على الصوت غير مدعومة على هذا الجهاز")
                    }
                } else {
                    Log.w(TAG, "Language $currentLanguage unsupported -> using ${activeLanguages()}")
                }
                recreateAndRestart(maxOf(RESTART_BUSY_DELAY_MS, backoffDelay()))
            }

            // الـ recognizer تعطل / مشغول: نخلقو من جديد
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            ERROR_SERVER_DISCONNECTED -> {
                errorStreak++
                recreateAndRestart(maxOf(RESTART_BUSY_DELAY_MS, backoffDelay()))
            }

            ERROR_TOO_MANY_REQUESTS -> {
                errorStreak++
                recreateAndRestart(1500L)
            }

            // شبكة / سيرفر / ميكروفون
            else -> {
                errorStreak++
                if (errorStreak >= 3) {
                    listener.onError(error, errorMessageFor(error))
                }
                recreateAndRestart(backoffDelay())
            }
        }
    }

    override fun onResults(results: Bundle?) {
        isRecognizing = false
        errorStreak = 0
        if (!commandFiredThisSession) {
            val fired = processSpeechResults(results, isPartial = false)
            // ما تفهمش الأمر بهاد اللغة -> الجلسة الجاية بلغة أخرى
            if (!fired) advanceLanguage()
        }
        mainHandler.removeCallbacks(idleLanguageRunnable)
        if (isIntentionallyListening) {
            scheduleRestart(RESTART_NORMAL_DELAY_MS)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (commandFiredThisSession) return
        val fired = processSpeechResults(partialResults, isPartial = true)
        if (fired && isIntentionallyListening) {
            // الأمر تنفذ: نحبسو هاد الجلسة (باش النتيجة النهائية ما تعاودش تنفذو) ونبداو جلسة جديدة
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {
            }
            isRecognizing = false
            scheduleRestart(RESTART_AFTER_COMMAND_MS)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    /**
     * كيفحص النتائج ويدير dispatch للأمر. كيرجع true إلا تنفذ أمر.
     */
    private fun processSpeechResults(bundle: Bundle?, isPartial: Boolean): Boolean {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        if (matches.isEmpty()) return false

        val customAliases = prefsManager.getCustomAliasMap()
        val now = System.currentTimeMillis()

        for (candidate in matches) {
            val trimmed = candidate.trim()
            if (trimmed.isEmpty()) continue

            val result = CommandEngine.match(trimmed, customAliases)
            if (result.isValid) {
                commandFiredThisSession = true

                // حماية إضافية ضد تكرار نفس الأمر فوقت قصير بين جلستين متتاليتين
                if (result.command == lastDispatchedCommand && now - lastDispatchedTime < DUPLICATE_WINDOW_MS) {
                    return true
                }

                lastDispatchedCommand = result.command
                lastDispatchedTime = now
                Log.i(TAG, "Command: ${result.command} (alias='${result.matchedAlias}', raw='$trimmed', partial=$isPartial)")
                listener.onCommandMatched(result)
                return true
            }
        }

        if (!isPartial) {
            listener.onUnrecognizedSpeech(matches[0])
        }
        return false
    }

    private fun errorMessageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "خطأ في التقاط الصوت"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK -> "تعذر الاتصال بخدمة الصوت"
        SpeechRecognizer.ERROR_SERVER -> "خطأ في خادم الصوت"
        else -> "كود الخطأ: $error"
    }
}

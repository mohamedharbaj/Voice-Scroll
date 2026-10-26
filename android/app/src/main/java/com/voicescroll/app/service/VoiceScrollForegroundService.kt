package com.voicescroll.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.voicescroll.app.R
import com.voicescroll.app.data.PreferencesManager
import com.voicescroll.app.engine.CommandMatchResult
import com.voicescroll.app.model.CommandType
import com.voicescroll.app.speech.VoiceCommandRecognizer
import com.voicescroll.app.ui.MainActivity

/**
 * Foreground Service that keeps speech recognition alive while user interacts with
 * external video apps (TikTok, YouTube, Instagram, Facebook).
 *
 * النوتيفيكاسيو (بحال VPN):
 *  - التطبيق خدام  -> نوتيفيكاسيو فيها زر "إيقاف".
 *  - التطبيق موقوف -> نوتيفيكاسيو فيها زر "تشغيل" (كتبقى باش تقدر تشغلو بلا ما تفتح التطبيق).
 *    إلا كانت صلاحية الوصول/الميكروفون ناقصة، الزر كيفتح التطبيق باش تعطيها.
 */
class VoiceScrollForegroundService : Service(), VoiceCommandRecognizer.Callback {

    companion object {
        private const val TAG = "VoiceForegroundService"
        const val CHANNEL_ID = "voice_scroll_service_channel"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_ID_STOPPED = 1002

        const val ACTION_START = "com.voicescroll.app.ACTION_START"
        const val ACTION_STOP = "com.voicescroll.app.ACTION_STOP"
        const val ACTION_BROADCAST_STATUS = "com.voicescroll.app.BROADCAST_STATUS"

        const val EXTRA_STATUS_TEXT = "extra_status_text"
        const val EXTRA_LAST_COMMAND = "extra_last_command"
        const val EXTRA_LAST_PHRASE = "extra_last_phrase"
        const val EXTRA_IS_LISTENING = "extra_is_listening"
        const val EXTRA_IS_SERVICE_RUNNING = "extra_is_service_running"

        var isRunning = false
            internal set

        fun updateRunningState(running: Boolean) {
            isRunning = running
        }

        // ---------------- helpers مشتركة (كيستعملهم حتى MainActivity) ----------------

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_desc)
                    setShowBadge(false)
                }
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            }
        }

        private fun newBuilder(context: Context): Notification.Builder {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
        }

        private fun hasMicPermission(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }

        private fun openAppIntent(context: Context, autoStart: Boolean): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (autoStart) putExtra(MainActivity.EXTRA_AUTO_START, true)
            }
            return PendingIntent.getActivity(
                context,
                if (autoStart) 3 else 0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        /** نوتيفيكاسيو "التطبيق موقوف" فيها زر تشغيل. */
        private fun buildStoppedNotification(context: Context): Notification {
            val ready = hasMicPermission(context) && VoiceScrollAccessibilityService.isServiceEnabled(context)

            val startPendingIntent: PendingIntent = if (ready) {
                val startIntent = Intent(context, VoiceScrollForegroundService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(
                        context, 2, startIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                } else {
                    PendingIntent.getService(
                        context, 2, startIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                }
            } else {
                // الصلاحيات ناقصة: نفتحو التطبيق باش المستخدم يعطيها ويتشغل أوتوماتيكيا
                openAppIntent(context, autoStart = true)
            }

            val text = if (ready) {
                context.getString(R.string.notification_stopped_text)
            } else {
                context.getString(R.string.notification_needs_access_text)
            }

            return newBuilder(context)
                .setContentTitle(context.getString(R.string.notification_stopped_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(openAppIntent(context, autoStart = false))
                .addAction(
                    android.R.drawable.ic_media_play,
                    context.getString(R.string.action_start),
                    startPendingIntent
                )
                .setOngoing(false)
                .setAutoCancel(false)
                .build()
        }

        /** كتبان النوتيفيكاسيو ديال "موقوف + زر تشغيل". */
        fun showStoppedNotification(context: Context) {
            try {
                ensureChannel(context)
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID_STOPPED, buildStoppedNotification(context))
            } catch (e: Exception) {
                Log.w(TAG, "Could not show stopped notification: ${e.message}")
            }
        }

        fun cancelStoppedNotification(context: Context) {
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIFICATION_ID_STOPPED)
            } catch (_: Exception) {
            }
        }
    }

    private var recognizer: VoiceCommandRecognizer? = null
    private lateinit var prefsManager: PreferencesManager

    override fun onCreate() {
        super.onCreate()
        prefsManager = PreferencesManager(this)
        ensureChannel(this)
        recognizer = VoiceCommandRecognizer(this, this)
        Log.i(TAG, "VoiceScrollForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            stopServiceAndControl()
            return START_NOT_STICKY
        }

        val notification = buildNotification(getString(R.string.notification_running_text))

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // أندرويد 14+: بلا إذن الميكروفون startForeground كيرمي SecurityException
            Log.e(TAG, "startForeground failed: ${e.message}")
            isRunning = false
            prefsManager.isVoiceControlActive = false
            showStoppedNotification(this)
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        prefsManager.isVoiceControlActive = true
        cancelStoppedNotification(this)

        recognizer?.startListening()
        broadcastStatus(isListening = true, statusText = getString(R.string.status_listening))

        return START_STICKY
    }

    private fun stopServiceAndControl() {
        isRunning = false
        prefsManager.isVoiceControlActive = false
        recognizer?.stopListening()
        broadcastStatus(isListening = false, statusText = getString(R.string.status_stopped))
        stopForeground(STOP_FOREGROUND_REMOVE)
        // نخليو نوتيفيكاسيو فيها زر "تشغيل" باش المستخدم يقدر يشغل التطبيق من جديد
        showStoppedNotification(this)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        recognizer?.stopListening()
        recognizer = null
        Log.i(TAG, "VoiceScrollForegroundService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- VoiceCommandRecognizer.Callback ---

    override fun onListeningStateChanged(isListening: Boolean, statusMessage: String) {
        broadcastStatus(isListening = isListening, statusText = statusMessage)
    }

    override fun onRmsChanged(rmsdB: Float) {
        // Can be routed to UI visualizer
    }

    override fun onCommandMatched(result: CommandMatchResult) {
        Log.i(TAG, "onCommandMatched received: ${result.command} from '${result.rawText}'")

        // STOP كيتنفذ غير بعبارة صريحة بحال "stop listening" / "وقف الاستماع".
        // "وقف / حبس / stop" عاديين = PAUSE (إيقاف الفيديو غير).
        if (result.command == CommandType.STOP) {
            stopServiceAndControl()
            return
        }

        val accessibility = VoiceScrollAccessibilityService.instance
        if (accessibility == null) {
            Log.w(TAG, "VoiceScrollAccessibilityService.instance is NULL! Accessibility is not connected.")
            broadcastStatus(
                isListening = true,
                statusText = getString(R.string.msg_accessibility_required),
                command = result.command.name,
                phrase = result.rawText
            )
            return
        }

        val executed = accessibility.performAction(result.command)
        Log.i(TAG, "Accessibility performAction result for ${result.command}: $executed")

        val statusText = if (executed) {
            getString(R.string.status_gesture_dispatched, result.command.displayNameAr)
        } else {
            getString(R.string.status_command_recognized, result.command.displayNameAr)
        }

        broadcastStatus(
            isListening = true,
            statusText = statusText,
            command = result.command.name,
            phrase = result.rawText
        )
    }

    override fun onUnrecognizedSpeech(rawText: String) {
        broadcastStatus(
            isListening = true,
            statusText = getString(R.string.status_not_understood),
            phrase = rawText
        )
    }

    override fun onError(errorCode: Int, errorMessage: String) {
        broadcastStatus(
            isListening = false,
            statusText = getString(R.string.status_error, errorMessage)
        )
    }

    private fun broadcastStatus(
        isListening: Boolean,
        statusText: String,
        command: String? = null,
        phrase: String? = null
    ) {
        val intent = Intent(ACTION_BROADCAST_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_SERVICE_RUNNING, isRunning)
            putExtra(EXTRA_IS_LISTENING, isListening)
            putExtra(EXTRA_STATUS_TEXT, statusText)
            command?.let { putExtra(EXTRA_LAST_COMMAND, it) }
            phrase?.let { putExtra(EXTRA_LAST_PHRASE, it) }
        }
        sendBroadcast(intent)
    }

    /** نوتيفيكاسيو "التطبيق خدام" فيها زر إيقاف. */
    private fun buildNotification(contentText: String): Notification {
        val stopIntent = Intent(this, VoiceScrollForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = newBuilder(this)
            .setContentTitle(getString(R.string.notification_running_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent(this, autoStart = false))
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.action_stop),
                stopPendingIntent
            )
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }
}

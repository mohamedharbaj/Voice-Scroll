package com.voicescroll.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.voicescroll.app.R
import com.voicescroll.app.data.PreferencesManager
import com.voicescroll.app.service.VoiceScrollAccessibilityService
import com.voicescroll.app.service.VoiceScrollForegroundService

class MainActivity : Activity() {

    companion object {
        private const val REQ_CODE_RECORD_AUDIO = 201
        private const val REQ_CODE_POST_NOTIFICATIONS = 202

        /** كيتبعث من زر "تشغيل" فالنوتيفيكاسيو إلا كانت الصلاحيات ناقصة */
        const val EXTRA_AUTO_START = "extra_auto_start"
    }

    private lateinit var prefsManager: PreferencesManager

    // true بعد ما نفتحو صفحة الخدمة ديال إمكانية الوصول، باش فـ onResume نعرفو واش رجع المستخدم منها
    private var awaitingAccessibility = false
    // true إلا كان المستخدم ضغط على "تشغيل التحكم الصوتي" -> نكملو التشغيل أوتوماتيكيا من بعد التفعيل
    private var startAfterAccessibility = false

    private lateinit var tvStatusDot: TextView
    private lateinit var tvStatusText: TextView
    private lateinit var tvLastCommandInfo: TextView
    private lateinit var btnToggleVoice: Button
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnFixAccessibility: Button
    private lateinit var spinnerLanguage: Spinner

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == VoiceScrollForegroundService.ACTION_BROADCAST_STATUS) {
                val isServiceRunning = intent.getBooleanExtra(
                    VoiceScrollForegroundService.EXTRA_IS_SERVICE_RUNNING,
                    VoiceScrollForegroundService.isRunning
                )
                val isListening = intent.getBooleanExtra(VoiceScrollForegroundService.EXTRA_IS_LISTENING, false)
                val statusText = intent.getStringExtra(VoiceScrollForegroundService.EXTRA_STATUS_TEXT) ?: ""
                val phrase = intent.getStringExtra(VoiceScrollForegroundService.EXTRA_LAST_PHRASE)
                VoiceScrollForegroundService.updateRunningState(isServiceRunning)
                updateControlState()
                updateStatusDisplay(isListening, statusText, phrase)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefsManager = PreferencesManager(this)

        initViews()
        setupListeners()
        setupLanguageSpinner()
        requestNotificationPermissionIfNeeded()

        // جا من زر "تشغيل" فالنوتيفيكاسيو (والصلاحيات ناقصة): نكملو التشغيل
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
            attemptStartVoiceControl()
        }
    }

    /**
     * أندرويد 13+: بلا إذن الإشعارات النوتيفيكاسيو (وزر التشغيل/الإيقاف) ما كتبانش نهائياً.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_CODE_POST_NOTIFICATIONS)
        }
    }

    /**
     * منين خدمة إمكانية الوصول تكون مفعلة والتطبيق موقوف، كنبينو نوتيفيكاسيو فيها زر "تشغيل"
     * (بحال VPN) باش تشغل التطبيق بلا ما تفتحو.
     */
    private fun refreshControlNotification() {
        if (!VoiceScrollForegroundService.isRunning && VoiceScrollAccessibilityService.isServiceEnabled(this)) {
            VoiceScrollForegroundService.showStoppedNotification(this)
        }
    }

    private fun initViews() {
        tvStatusDot = findViewById(R.id.tvStatusDot)
        tvStatusText = findViewById(R.id.tvStatusText)
        tvLastCommandInfo = findViewById(R.id.tvLastCommandInfo)
        btnToggleVoice = findViewById(R.id.btnToggleVoice)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnFixAccessibility = findViewById(R.id.btnFixAccessibility)
        spinnerLanguage = findViewById(R.id.spinnerLanguage)
    }

    private fun setupListeners() {
        btnToggleVoice.setOnClickListener {
            val isCurrentRunning = VoiceScrollForegroundService.isRunning || prefsManager.isVoiceControlActive
            if (isCurrentRunning) {
                stopVoiceControl()
            } else {
                attemptStartVoiceControl()
            }
        }

        btnFixAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        findViewById<ImageButton>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenTestVoice).setOnClickListener {
            startActivity(Intent(this, TestVoiceActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenCustomAliases).setOnClickListener {
            startActivity(Intent(this, CustomAliasesActivity::class.java))
        }

        findViewById<TextView>(R.id.tvPrivacyLink).setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }

        findViewById<TextView>(R.id.tvAboutLink).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        checkAccessibilityStatus()
        handleReturnFromAccessibility()
        val isServiceActive = VoiceScrollForegroundService.isRunning || prefsManager.isVoiceControlActive
        VoiceScrollForegroundService.updateRunningState(isServiceActive)
        updateControlState()
        refreshControlNotification()

        val filter = IntentFilter(VoiceScrollForegroundService.ACTION_BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {}
    }

    private fun setupLanguageSpinner() {
        val languages = listOf(
            "الدارجة المغربية (ar-MA)" to PreferencesManager.LANG_AR_MOROCCO,
            "العربية الفصحى (ar-SA) – لغة الأوامر" to PreferencesManager.LANG_AR_STANDARD,
            "Français (fr-FR)" to PreferencesManager.LANG_FRENCH,
            "English (en-US)" to PreferencesManager.LANG_ENGLISH,
            "كل اللغات (دارجة + English + Français)" to PreferencesManager.LANG_AUTO
        )

        val adapter = object : ArrayAdapter<String>(this, R.layout.item_spinner_language, languages.map { it.first }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.textAlignment = View.TEXT_ALIGNMENT_CENTER
                view.gravity = android.view.Gravity.CENTER
                view.isSingleLine = true
                view.ellipsize = android.text.TextUtils.TruncateAt.END
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.textAlignment = View.TEXT_ALIGNMENT_CENTER
                view.gravity = android.view.Gravity.CENTER
                view.isSingleLine = true
                view.ellipsize = android.text.TextUtils.TruncateAt.END
                return view
            }
        }
        adapter.setDropDownViewResource(R.layout.item_spinner_language_dropdown)
        spinnerLanguage.adapter = adapter

        val currentLang = prefsManager.commandLanguage
        val selectedIndex = languages.indexOfFirst { it.second == currentLang }.coerceAtLeast(0)
        spinnerLanguage.setSelection(selectedIndex)

        spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = languages[position].second
                if (prefsManager.commandLanguage != chosen) {
                    prefsManager.commandLanguage = chosen
                    if (VoiceScrollForegroundService.isRunning) {
                        Toast.makeText(this@MainActivity, "تم تغيير لغة الأوامر بنجاح", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun attemptStartVoiceControl() {
        if (!VoiceScrollAccessibilityService.isServiceEnabled(this)) {
            // مباشرة نوديو المستخدم لصفحة تفعيل الخدمة بلا ديالوغ وسيط
            startAfterAccessibility = true
            openAccessibilitySettings()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_CODE_RECORD_AUDIO)
                return
            }
        }

        startVoiceControl()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE_POST_NOTIFICATIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (VoiceScrollForegroundService.isRunning) return
                refreshControlNotification()
            }
            return
        }
        if (requestCode == REQ_CODE_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceControl()
            } else {
                showPermissionDeniedDialog()
            }
        }
    }

    private fun startVoiceControl() {
        VoiceScrollForegroundService.updateRunningState(true)
        prefsManager.isVoiceControlActive = true
        updateControlState()

        val serviceIntent = Intent(this, VoiceScrollForegroundService::class.java).apply {
            action = VoiceScrollForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopVoiceControl() {
        VoiceScrollForegroundService.updateRunningState(false)
        prefsManager.isVoiceControlActive = false
        updateControlState()

        val serviceIntent = Intent(this, VoiceScrollForegroundService::class.java).apply {
            action = VoiceScrollForegroundService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun checkAccessibilityStatus() {
        val isEnabled = VoiceScrollAccessibilityService.isServiceEnabled(this)
        val params = tvAccessibilityStatus.layoutParams as? ViewGroup.MarginLayoutParams
        if (isEnabled) {
            tvAccessibilityStatus.text = getString(R.string.accessibility_enabled)
            tvAccessibilityStatus.setTextColor(getColor(R.color.accent))
            params?.let {
                it.marginEnd = 0
                tvAccessibilityStatus.layoutParams = it
            }
            btnFixAccessibility.visibility = View.GONE
        } else {
            tvAccessibilityStatus.text = getString(R.string.accessibility_disabled)
            tvAccessibilityStatus.setTextColor(getColor(R.color.accent_red))
            params?.let {
                it.marginEnd = (16 * resources.displayMetrics.density).toInt()
                tvAccessibilityStatus.layoutParams = it
            }
            btnFixAccessibility.visibility = View.VISIBLE
        }
    }

    private fun updateControlState() {
        val isRunning = VoiceScrollForegroundService.isRunning
        if (isRunning) {
            btnToggleVoice.text = getString(R.string.btn_stop_voice_control)
            btnToggleVoice.setBackgroundResource(R.drawable.btn_danger)
            tvStatusDot.text = "●"
            tvStatusDot.setTextColor(getColor(R.color.accent))
            tvStatusText.text = getString(R.string.status_running)
            tvLastCommandInfo.text = "يستمع لأوامرك (دوز، رجع، وقف الفيديو، كمل…)"
        } else {
            btnToggleVoice.text = getString(R.string.btn_start_voice_control)
            btnToggleVoice.setBackgroundResource(R.drawable.btn_primary)
            tvStatusDot.text = "●"
            tvStatusDot.setTextColor(getColor(R.color.text_muted))
            tvStatusText.text = getString(R.string.status_stopped)
            tvLastCommandInfo.text = "جاهز للبدء"
        }
    }

    private fun updateStatusDisplay(isListening: Boolean, statusText: String, phrase: String?) {
        if (!VoiceScrollForegroundService.isRunning) {
            updateControlState()
            return
        }
        tvStatusText.text = statusText
        if (!phrase.isNullOrBlank()) {
            tvLastCommandInfo.text = "آخر كلام ملتقط: \"$phrase\""
        }
    }

    /**
     * أندرويد ما كيسمحش لتطبيق يفعّل خدمة إمكانية الوصول بوحدو.
     * أحسن حل هو نفتحو مباشرة صفحة خدمة Voice Scroll (ماشي لائحة إمكانية الوصول كاملة)،
     * وهنا المستخدم غير كيفعّل المفتاح (Switch) ويأكد.
     */
    private fun openAccessibilitySettings() {
        awaitingAccessibility = true
        val component = ComponentName(this, VoiceScrollAccessibilityService::class.java)
        val flat = component.flattenToString()

        Toast.makeText(this, "فعّل «Voice Scroll» ثم اضغط موافق", Toast.LENGTH_LONG).show()

        // 1) الصفحة المباشرة ديال الخدمة (أندرويد 14+ وبعض الهواتف)
        try {
            val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                putExtra(Intent.EXTRA_COMPONENT_NAME, flat)
                putExtra("android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME", flat)
            }
            startActivity(details)
            return
        } catch (_: Exception) {
        }

        // 2) لائحة إمكانية الوصول مع تحديد (highlight) الخدمة ديالنا
        try {
            val argsKey = ":settings:fragment_args_key"
            val showArgsKey = ":settings:show_fragment_args"
            val bundle = Bundle().apply { putString(argsKey, flat) }
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                putExtra(argsKey, flat)
                putExtra(showArgsKey, bundle)
            }
            startActivity(intent)
            return
        } catch (_: Exception) {
        }

        // 3) آخر حل
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            awaitingAccessibility = false
        }
    }

    private fun handleReturnFromAccessibility() {
        if (!awaitingAccessibility) return
        awaitingAccessibility = false

        if (VoiceScrollAccessibilityService.isServiceEnabled(this)) {
            // تفعّلات الخدمة: إلا كان المستخدم ضغط على "تشغيل" نكملو مباشرة
            if (startAfterAccessibility) {
                startAfterAccessibility = false
                attemptStartVoiceControl()
            }
            return
        }

        startAfterAccessibility = false
        // الخدمة باقا ما تفعلاتش: على أندرويد 13+ إلا كان التطبيق مثبت من خارج المتجر
        // الخدمة كتكون مقفولة بـ "Restricted setting" ولازم السماح من معلومات التطبيق
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isSideloaded()) {
            showRestrictedSettingDialog()
        }
    }

    private fun isSideloaded(): Boolean {
        return try {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
            installer !in setOf(
                "com.android.vending",
                "com.sec.android.app.samsungapps",
                "com.huawei.appmarket",
                "com.amazon.venezia"
            )
        } catch (_: Exception) {
            true
        }
    }

    private fun showRestrictedSettingDialog() {
        AlertDialog.Builder(this)
            .setTitle("خدمة إمكانية الوصول مقفولة")
            .setMessage(
                "أندرويد كيقفل هاد الخيار فالتطبيقات المثبتة من خارج المتجر (Restricted setting).\n\n" +
                "للسماح به مرة وحدة:\n" +
                "1. اضغط على «معلومات التطبيق»\n" +
                "2. اضغط على القائمة (⋮) فوق اليمين\n" +
                "3. اختار «السماح بالإعدادات المقيدة»\n" +
                "4. رجع وضغط على «تفعيل» مرة أخرى"
            )
            .setPositiveButton("معلومات التطبيق") { _, _ -> openAppDetailsSettings() }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("إذن الميكروفون مطلوب")
            .setMessage(getString(R.string.permission_mic_required))
            .setPositiveButton("فتح إعدادات التطبيق") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }
}

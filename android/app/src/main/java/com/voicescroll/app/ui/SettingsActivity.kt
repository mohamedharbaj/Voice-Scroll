package com.voicescroll.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.voicescroll.app.R
import com.voicescroll.app.data.PreferencesManager
import com.voicescroll.app.service.VoiceScrollAccessibilityService

class SettingsActivity : Activity() {

    private lateinit var prefsManager: PreferencesManager
    private lateinit var spinnerSettingsLang: Spinner
    private lateinit var tvDurationValue: TextView
    private lateinit var seekBarSwipeDuration: SeekBar
    private lateinit var tvCooldownValue: TextView
    private lateinit var seekBarCooldown: SeekBar
    private lateinit var tvSettingsAccessStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefsManager = PreferencesManager(this)

        initViews()
        setupLanguageSpinner()
        setupSliders()
        setupNavCards()
    }

    private fun initViews() {
        spinnerSettingsLang = findViewById(R.id.spinnerSettingsLang)
        tvDurationValue = findViewById(R.id.tvDurationValue)
        seekBarSwipeDuration = findViewById(R.id.seekBarSwipeDuration)
        tvCooldownValue = findViewById(R.id.tvCooldownValue)
        seekBarCooldown = findViewById(R.id.seekBarCooldown)
        tvSettingsAccessStatus = findViewById(R.id.tvSettingsAccessStatus)
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun setupLanguageSpinner() {
        val languages = listOf(
            "الدارجة المغربية (ar-MA)" to PreferencesManager.LANG_AR_MOROCCO,
            "العربية الفصحى (ar-SA)" to PreferencesManager.LANG_AR_STANDARD,
            "Français (fr-FR)" to PreferencesManager.LANG_FRENCH,
            "English (en-US)" to PreferencesManager.LANG_ENGLISH,
            "كل اللغات (دارجة + English + Français)" to PreferencesManager.LANG_AUTO
        )

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages.map { it.first })
        spinnerSettingsLang.adapter = adapter

        val currentLang = prefsManager.commandLanguage
        val selectedIndex = languages.indexOfFirst { it.second == currentLang }.coerceAtLeast(0)
        spinnerSettingsLang.setSelection(selectedIndex)

        spinnerSettingsLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefsManager.commandLanguage = languages[position].second
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSliders() {
        val currentDuration = prefsManager.swipeDurationMs
        seekBarSwipeDuration.progress = (currentDuration - 150).toInt().coerceIn(0, 450)
        tvDurationValue.text = "$currentDuration ms"

        seekBarSwipeDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (150 + progress).toLong()
                tvDurationValue.text = "$value ms"
                if (fromUser) {
                    prefsManager.swipeDurationMs = value
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val currentCooldown = prefsManager.cooldownMs
        seekBarCooldown.progress = (currentCooldown - 400).toInt().coerceIn(0, 2500)
        tvCooldownValue.text = "$currentCooldown ms"

        seekBarCooldown.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (400 + progress).toLong()
                tvCooldownValue.text = "$value ms"
                if (fromUser) {
                    prefsManager.cooldownMs = value
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupNavCards() {
        findViewById<View>(R.id.cardNavAliases).setOnClickListener {
            startActivity(Intent(this, CustomAliasesActivity::class.java))
        }

        findViewById<View>(R.id.cardNavAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "ابحث عن Voice Scroll لتفعيلها", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.cardNavPrivacy).setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }

        findViewById<View>(R.id.cardNavAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = VoiceScrollAccessibilityService.isServiceEnabled
        if (isEnabled) {
            tvSettingsAccessStatus.text = "إمكانية الوصول: مفعلة ✓"
            tvSettingsAccessStatus.setTextColor(getColor(R.color.accent))
        } else {
            tvSettingsAccessStatus.text = "إمكانية الوصول: غير مفعلة ✗"
            tvSettingsAccessStatus.setTextColor(getColor(R.color.accent_red))
        }
    }
}

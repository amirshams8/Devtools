package com.jarvismini.devtools

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.jarvismini.devtools.autobuild.AutoBuildService
import com.jarvismini.devtools.autobuild.ModeStore
import com.jarvismini.devtools.autobuild.models.AutoBuildState
import com.jarvismini.devtools.autobuild.models.ExtractionMode
import com.jarvismini.devtools.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logBuilder = SpannableStringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupModeSpinner()
        setupButtons()
        registerCallbacks()
        refreshServiceState()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceState()
    }

    override fun onDestroy() {
        super.onDestroy()
        AutoBuildService.onStatusUpdate = null
        AutoBuildService.onLogLine = null
    }

    // ── Mode spinner ──────────────────────────────────────────────────────────

    private fun setupModeSpinner() {
        val labels = listOf(
            "Code block in chat",
            "Downloaded file(s) → /sdcard",
            "Plain text in chat"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerMode.adapter = adapter
        binding.spinnerMode.setSelection(ModeStore.load(this).ordinal)

        binding.spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val mode = ExtractionMode.entries[pos]
                ModeStore.save(this@MainActivity, mode)
                appendLog("Mode: ${mode.name}", Color.CYAN)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnStart.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnStop.setOnClickListener {
            AutoBuildService.requestStop()
            appendLog("Stop requested by user.", Color.YELLOW)
            binding.btnStop.isEnabled = false
        }
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    // ── Service callbacks ─────────────────────────────────────────────────────

    private fun registerCallbacks() {
        AutoBuildService.onStatusUpdate = { iteration, state ->
            runOnUiThread { updateStatus(iteration, state) }
        }
        AutoBuildService.onLogLine = { line, isError ->
            runOnUiThread { appendLog(line, if (isError) Color.RED else Color.WHITE) }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun refreshServiceState() {
        val enabled = isAccessibilityServiceEnabled()
        if (enabled) {
            binding.tvAccessibilityHint.visibility = View.GONE
            binding.btnStart.isEnabled = false
            binding.btnStop.isEnabled = true
            if (AutoBuildService.isLoopRunning) {
                updateStatus(AutoBuildService.currentIteration, AutoBuildService.currentState)
            }
        } else {
            binding.tvAccessibilityHint.visibility = View.VISIBLE
            binding.btnStart.isEnabled = true
            binding.btnStop.isEnabled = false
            setStatusDot(Color.GRAY)
            binding.tvStatus.text = getString(R.string.status_idle)
        }
    }

    private fun updateStatus(iteration: Int, state: AutoBuildState) {
        binding.tvIteration.text = "Iter: $iteration"
        binding.tvStatus.text = state.displayName()
        setStatusDot(state.dotColor())
    }

    private fun setStatusDot(color: Int) {
        binding.statusDot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(color)
    }

    private fun appendLog(line: String, color: Int) {
        val start = logBuilder.length
        logBuilder.append(line).append("\n")
        logBuilder.setSpan(
            ForegroundColorSpan(color), start, logBuilder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.tvLog.text = logBuilder
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val short = "${packageName}/.autobuild.AutoBuildService"
        val full  = "${packageName}/com.jarvismini.devtools.autobuild.AutoBuildService"
        return enabledServices.split(":").any { entry ->
            val t = entry.trim()
            t.equals(short, ignoreCase = true) || t.equals(full, ignoreCase = true)
        }
    }

    private fun AutoBuildState.displayName() = when (this) {
        AutoBuildState.IDLE                     -> "Idle"
        AutoBuildState.WAITING_FOR_RESPONSE     -> "Waiting for Claude response…"
        AutoBuildState.EXTRACTING_CODE          -> "Extracting code…"
        AutoBuildState.WRITING_OUTPUT           -> "Writing ai-output.txt…"
        AutoBuildState.TRIGGERING_BUILD         -> "Pushing to GitHub via Termux…"
        AutoBuildState.WAITING_FOR_BUILD        -> "Waiting for Apk.yml to finish…"
        AutoBuildState.CHECKING_ERROR_FRESHNESS -> "Checking if errors are new…"
        AutoBuildState.READING_ERROR_LOGS       -> "Pulling error logs…"
        AutoBuildState.ATTACHING_FILES          -> "Attaching error logs to Claude…"
        AutoBuildState.SUBMITTING_PROMPT        -> "Submitting prompt to Claude…"
        AutoBuildState.BUILD_SUCCEEDED          -> "✅ Build succeeded!"
        AutoBuildState.TIMEOUT_ERROR            -> "⚠ Timeout — retrying…"
    }

    private fun AutoBuildState.dotColor() = when (this) {
        AutoBuildState.BUILD_SUCCEEDED          -> Color.GREEN
        AutoBuildState.TIMEOUT_ERROR            -> Color.RED
        AutoBuildState.IDLE                     -> Color.GRAY
        AutoBuildState.WAITING_FOR_BUILD,
        AutoBuildState.TRIGGERING_BUILD         -> Color.YELLOW
        else                                    -> Color.CYAN
    }
}

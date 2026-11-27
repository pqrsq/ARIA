package com.example.aria.probe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * PHASE 1-PROBE — a measuring instrument, not a fix.
 *
 * WHAT IT IS FOR: six earlier instrumentation rounds ruled out ARIA's lifecycle,
 * result selection, endpointing, acoustic level and recogniser reuse. What is
 * still unknown is WHICH audio path the recognition service ends up using, and
 * whether ARIA's own process influences it. This screen answers that.
 *
 * WHY IT IS A SEPARATE PROCESS (`:sttprobe`, set in the debug manifest): if this
 * Activity ran in ARIA's main process it could not distinguish "the platform is
 * misbehaving" from "something ARIA started in its process is misbehaving".
 *
 * WHY IT USES NONE OF ARIA'S CODE: no SpeechRecognizerManager, no
 * AssistantViewModel, no AriaTheme, no history, no TTS. It owns its own
 * recogniser. That is what makes it a control condition.
 *
 * WHAT IT DOES NOT DO: no audio is written, retained or transmitted. The only
 * audio-derived value is the maximum of onRmsChanged, one number per attempt.
 * It sets the same extras as ARIA and nothing else; in particular it does NOT set
 * EXTRA_PREFER_OFFLINE, because that would be a second variable.
 *
 * UI IS DELIBERATELY CRUDE — plain Views, not Compose. A measuring instrument
 * should not depend on the theme, the icon set or any Compose machinery whose
 * behaviour could differ between processes.
 */
class SttProbeActivity : Activity() {

    private lateinit var modeGroup: RadioGroup
    private lateinit var serviceGroup: RadioGroup
    private lateinit var serviceLabel: TextView
    private lateinit var statusView: TextView
    private lateinit var resultsView: TextView
    private lateinit var startButton: Button

    private var services: List<RecognizerTargets.Service> = emptyList()
    private var recognizer: SpeechRecognizer? = null

    /** The attempt currently being measured; null when idle. */
    private var pending: ProbeAttempt? = null
    private var startedAt = 0L
    private var readyAt: Long? = null
    private var beginAt: Long? = null
    private var endAt: Long? = null
    private var rmsMax: Float? = null
    private val partials = mutableListOf<String>()

    /** Newest first, capped so a single screenshot holds the whole comparison. */
    private val history = ArrayDeque<ProbeAttempt>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadRecognisers()
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.BLACK)
        }

        root.addView(header("ARIA STT Probe (debug only)"))
        root.addView(note(
            "Separate process: :sttprobe. No TTS, no ARIA code, no audio stored.\n" +
                "Say each utterance once, per mode. rms_max is a single scalar."
        ))

        val permText = if (hasMicPermission()) {
            "microphone permission: GRANTED"
        } else {
            "microphone permission: MISSING — grant it in ARIA first, then reopen this screen."
        }
        statusView = note(permText).also { root.addView(it) }

        root.addView(header("Mode"))
        modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        addMode("A", "A — default (exactly what ARIA does)")
        addMode("B", "B — explicit service (pick below)")
        addMode("C", "C — on-device")
        (modeGroup.getChildAt(0) as RadioButton).isChecked = true
        root.addView(modeGroup)

        serviceLabel = note("Recogniser services are listed after the app starts.")
        root.addView(serviceLabel)
        serviceGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        root.addView(serviceGroup)

        startButton = Button(this).apply {
            text = "LISTEN ONCE"
            setOnClickListener { startAttempt() }
        }
        root.addView(startButton)

        resultsView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            text = "no attempts yet"
        }
        root.addView(
            ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
                addView(resultsView)
            }
        )
        setContentView(root)
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 16f
        setPadding(0, 24, 0, 8)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.LTGRAY)
        textSize = 12f
        gravity = Gravity.START
    }

    private fun addMode(id: String, label: String) {
        modeGroup.addView(RadioButton(this).apply {
            text = label
            tag = id
            setTextColor(Color.WHITE)
        })
    }

    private fun selectedMode(): String {
        val checked = modeGroup.checkedRadioButtonId
        val button = modeGroup.findViewById<RadioButton>(checked)
        return (button?.tag as? String) ?: "A"
    }

    private fun selectedService(): RecognizerTargets.Service? {
        val checked = serviceGroup.checkedRadioButtonId
        val button = serviceGroup.findViewById<RadioButton>(checked) ?: return null
        return button.tag as? RecognizerTargets.Service
    }

    // ── discovery ─────────────────────────────────────────────────────────────

    private fun loadRecognisers() {
        services = RecognizerTargets.installedServices(this)
        serviceGroup.removeAllViews()
        if (services.isEmpty()) {
            serviceLabel.text = "no recognition services visible (package visibility?)"
            return
        }
        serviceLabel.text = "Installed recognition services (${services.size}):"
        services.forEach { service ->
            serviceGroup.addView(RadioButton(this).apply {
                text = "${service.label}\n    ${service.component.flattenToShortString()}"
                tag = service
                setTextColor(Color.WHITE)
                textSize = 12f
            })
        }
        // Pre-select the first so mode B is usable without an extra tap.
        (serviceGroup.getChildAt(0) as? RadioButton)?.isChecked = true
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ── one attempt ───────────────────────────────────────────────────────────

    private fun startAttempt() {
        if (!hasMicPermission()) {
            statusView.text = "microphone permission: MISSING — grant it in ARIA first."
            return
        }
        if (pending != null) {
            statusView.text = "an attempt is already running"
            return
        }

        val mode = selectedMode()
        val service = selectedService()
        val component = RecognizerTargets.describe(mode, service)

        if (mode == "B" && service == null) {
            statusView.text = "mode B needs a service selected"
            return
        }

        partials.clear()
        rmsMax = null
        readyAt = null
        beginAt = null
        endAt = null
        startedAt = SystemClock.elapsedRealtime()

        pending = ProbeAttempt(
            mode = mode,
            component = component,
            capturing = currentCaptureDescription()
        )

        // Report, never crash: an unavailable on-device recogniser is an expected
        // outcome on many devices, and "it is not there" is itself a finding.
        val created = RecognizerTargets.create(this, mode, service)
        if (created.isFailure) {
            val message = created.exceptionOrNull()?.message ?: "unknown"
            finishAttempt(errorCode = null, errorName = "UNAVAILABLE: $message")
            return
        }
        val speech = created.getOrThrow()
        speech.setRecognitionListener(listener)
        recognizer = speech

        runCatching { speech.startListening(buildIntent()) }
            .onFailure { finishAttempt(null, "START FAILED: ${it.message}") }

        statusView.text = "listening — mode $mode ($component)"
    }

    /**
     * The SAME extras for every mode, so the only variable is the recogniser.
     *
     * en-US is pinned so a device-locale change cannot become an accidental
     * second variable between runs. EXTRA_PREFER_OFFLINE is deliberately absent:
     * forcing offline would confound the very path comparison this probe exists
     * to measure.
     */
    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

    /**
     * What the system says is capturing right now.
     *
     * CAVEAT THAT MUST TRAVEL WITH THIS VALUE: without MODIFY_AUDIO_ROUTING this
     * API can legitimately return an EMPTY list even while a capture is active.
     * An empty value here is "not visible to this app", NOT "no microphone was
     * open". The 1F run produced five successful sessions with an empty list.
     */
    private fun currentCaptureDescription(): String =
        runCatching {
            val audio = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager
            when {
                audio == null -> "no AudioManager"
                else -> {
                    val configs = audio.activeRecordingConfigurations
                    if (configs.isEmpty()) {
                        // NOT "no capture": see the caveat above.
                        "none-visible"
                    } else {
                        configs.joinToString(";") { config ->
                            val device = config.audioDevice
                            val name = device?.let { "${it.type}(${it.productName})" }
                                ?: "unknown"
                            "dev=$name silenced=${config.isClientSilenced}"
                        }
                    }
                }
            }
        }.getOrElse { "error: ${it.message}" }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            readyAt = SystemClock.elapsedRealtime() - startedAt
        }

        override fun onBeginningOfSpeech() {
            beginAt = SystemClock.elapsedRealtime() - startedAt
        }

        override fun onRmsChanged(rmsdB: Float) {
            // One scalar, max only. Nothing retained.
            val current = rmsMax
            if (current == null || rmsdB > current) rmsMax = rmsdB
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Raw audio is discarded here too. The probe never keeps a byte.
        }

        override fun onEndOfSpeech() {
            // P3 asks for end_ms: the moment the service decided the utterance
            // was over. Recorded, never acted on.
            endAt = SystemClock.elapsedRealtime() - startedAt
        }

        override fun onError(error: Int) {
            finishAttempt(error, errorName(error))
        }

        override fun onResults(results: Bundle?) {
            finishAttempt(null, null, final = firstTranscript(results))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstTranscript(partialResults)
            if (text != null) partials.add(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun firstTranscript(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        else -> "ERROR_$code"
    }

    /**
     * Records the attempt exactly once, then tears the recogniser down.
     *
     * ORDER MATTERS: the record is completed and logged BEFORE destroy(), so a
     * destroy-triggered ERROR_CLIENT cannot overwrite a real result with a
     * spurious error.
     */
    private fun finishAttempt(errorCode: Int?, errorName: String?, final: String? = null) {
        val base = pending ?: return
        pending = null

        val attempt = base.copy(
            readyMs = readyAt,
            beginMs = beginAt,
            endMs = endAt,
            partials = partials.toList(),
            final = final,
            rmsMax = rmsMax,
            errorCode = errorCode,
            errorName = errorName
        )

        history.addFirst(attempt)
        while (history.size > MAX_HISTORY) history.removeLast()

        Log.d(TAG, attempt.logLine())
        render()

        val current = recognizer
        recognizer = null
        runCatching { current?.destroy() }
        statusView.text = "idle — last: ${attempt.outcome}"
    }

    private fun render() {
        if (history.isEmpty()) {
            resultsView.text = "no attempts yet"
            return
        }
        resultsView.text = history.joinToString("\n\n") { it.logLine() }
    }

    override fun onDestroy() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ARIA_PROBE"

        /** Enough for three modes x five utterances to be on one screen. */
        const val MAX_HISTORY = 20
    }
}

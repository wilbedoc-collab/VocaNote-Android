package com.re2o.recorder

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.media.MediaPlayer
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.TextUtils
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.net.Uri
import android.os.Handler
import androidx.core.content.FileProvider
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : android.app.Activity() {
    private var isStarting = false
    private var isRecording = false
    private var isStopping = false
    private var currentFile: File? = null
    private var lastRecordingId: String? = null
    private var lastSummaryText: String = ""
    private var lastTranscriptText: String = ""
    private var lastAnalysisText: String = ""
    private var lastDetailTitle: String = ""
    private var lastDetailRecordedAt: String = ""
    private var lastDetailDurationSec: Long = 0L
    private var lastDisplayVersion: String = ""
    private var lastProcessingState: String = ""
    private var lastFastAvailable: Boolean = false
    private var lastFinalAvailable: Boolean = false
    private var lastKeywords: List<String> = emptyList()
    private var lastSegments: List<TranscriptSegment> = emptyList()
    private var mediaPlayer: MediaPlayer? = null
    private var transcriptBodyView: TextView? = null
    private var transcriptScrollView: ScrollView? = null
    private var followButtonView: TextView? = null
    private var transcriptContainerView: LinearLayout? = null
    private var segmentViews: MutableList<TextView> = mutableListOf()
    private var playbackSeekBar: SeekBar? = null
    private var playbackCurrentTimeView: TextView? = null
    private var playbackDurationView: TextView? = null
    private var playbackButtonView: TextView? = null
    private var userSeeking = false
    private var autoFollowTranscript = true
    private var lastActiveSegmentIndex = -1
    private var activeTab: String = "transcript"
    private var startedAtMs: Long = 0
    private var currentClientRecordingId: String? = null
    private var currentScreen: Screen = Screen.HOME
    private var editorRecordingId: String? = null
    private val editorHeartbeat = object : Runnable {
        override fun run() {
            val rid = editorRecordingId ?: return
            if (currentScreen != Screen.RECORDING_EDITOR) return
            thread {
                val ok = EditLeaseCoordinator.heartbeat(this@MainActivity, rid)
                if (!ok) runOnUiThread { showEditorRecoveryRequired(rid) }
                else handler.postDelayed(this, 20_000L)
            }
        }
    }
    private val deletingRecordingIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())
    private var pendingHistoryScrollY: Int? = null
    private var historyScrollView: ScrollView? = null
    private var suppressNextHistoryAutoTop = false
    private var lastHistoryItems: List<HistoryItem> = emptyList()
    private var lastVoiceAtMs: Long = 0
    private var latestAmplitude: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    private val recordingStatusTicker = object : Runnable {
        override fun run() {
            if (!isRecording || isStopping) return
            renderRecordingTick()
            handler.postDelayed(this, 1000L)
        }
    }
    private val levelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            latestAmplitude = intent?.getIntExtra(RecordingService.EXTRA_AMPLITUDE, 0) ?: 0
            if (latestAmplitude > VOICE_THRESHOLD) lastVoiceAtMs = System.currentTimeMillis()
        }
    }
    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RecordingService.ACTION_RECORDING_STARTED &&
                intent.getStringExtra(RecordingService.EXTRA_PATH) == currentFile?.absolutePath &&
                intent.getStringExtra(RecordingService.EXTRA_CLIENT_ID) == currentClientRecordingId
            ) {
                isStarting = false
                reconcileRecordingUiFromDurableState()
            } else if (intent?.action == RecordingService.ACTION_RECORDING_START_FAILED &&
                intent.getStringExtra(RecordingService.EXTRA_PATH) == currentFile?.absolutePath &&
                intent.getStringExtra(RecordingService.EXTRA_CLIENT_ID) == currentClientRecordingId
            ) {
                isStarting = false
                showErrorStatus("녹음을 시작하지 못했습니다")
            } else if (intent?.action == RecordingService.ACTION_RECORDING_STOPPED ||
                intent?.action == RecordingService.ACTION_FINALIZATION_COMPLETED
            ) {
                isStarting = false
                reconcileRecordingUiFromDurableState()
            }
        }
    }

    private lateinit var historyButton: TextView
    private lateinit var recordButton: TextView
    private lateinit var stopButton: TextView
    private lateinit var statusText: TextView
    private lateinit var heroSubtitleText: TextView
    private var recordPulse1: ObjectAnimator? = null
    private var recordPulse2: ObjectAnimator? = null
    private var recordPulse3: ObjectAnimator? = null
    private var recordButtonFrameView: View? = null
    private var recordRingView: View? = null
    private var recordCardView: View? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private val serverUrl = BuildConfig.VOCANOTE_SERVER_URL
    private val uploadToken = BuildConfig.VOCANOTE_UPLOAD_TOKEN

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
        private const val APP_VERSION = "0.4.2"
        private const val PREFS = "re2o_recorder"
        private const val KEY_CURRENT_PATH = "current_path"
        private const val KEY_IS_RECORDING = "is_recording"
        private const val KEY_CURRENT_CLIENT_ID = "current_client_recording_id"
        private const val KEY_STARTED_AT_MS = "started_at_ms"
        private const val VOICE_THRESHOLD = 500
    }

    enum class Screen { HOME, RECORDING_LIST, RECORDING_DETAIL, RECORDING_EDITOR }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome(resetTransient = true)
        RecordingFinalizerWorker.recoverPending(this)
        restoreRecordingState()
        LocalRecordingStore.recoverOneAuthBlockedForProcessStart(this)
        EditGraceWorker.enqueueAllPending(this)
        LocalRecordingStore.enqueuePendingUploads(this)
        VocaNotePurgeWorker.schedule(this)
        if (shouldAutoStartRecording(intent)) {
            handler.postDelayed({ startRecordingIfReady() }, 500)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        showHome(resetTransient = true)
        RecordingFinalizerWorker.recoverPending(this)
        restoreRecordingState()
        EditGraceWorker.enqueueAllPending(this)
        LocalRecordingStore.enqueuePendingUploads(this)
        VocaNotePurgeWorker.schedule(this)
        if (shouldAutoStartRecording(intent)) {
            handler.postDelayed({ startRecordingIfReady() }, 300)
        }
    }

    private fun showHome(resetTransient: Boolean = false) {
        currentScreen = Screen.HOME
        buildUi()
        if (resetTransient) {
            lastSummaryText = ""; lastTranscriptText = ""; lastAnalysisText = ""; lastDetailTitle = ""; lastDetailRecordedAt = ""; lastDetailDurationSec = 0L; lastDisplayVersion = ""; lastProcessingState = ""; lastFastAvailable = false; lastFinalAvailable = false; lastKeywords = emptyList(); lastSegments = emptyList()
        }
    }

    private fun shouldAutoStartRecording(intent: Intent?): Boolean {
        val fromDeepLink = intent?.dataString == "re2orecorder://record/start"
        val fromQuickAlias = intent?.component?.className?.contains("QuickRecordActivity") == true
        return fromDeepLink || fromQuickAlias
    }

    private fun roundedBg(color: Int, strokeColor: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = 28f
            if (strokeColor != null) setStroke(2, strokeColor)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun bgColor(): Int = Color.rgb(6, 11, 22)          // #060B16
    private fun cardColor(): Int = Color.rgb(9, 20, 38)        // #091426
    private fun secondaryColor(): Int = Color.rgb(11, 22, 40)  // #0B1628
    private fun borderColor(alpha: Int = 255): Int = Color.argb(alpha, 30, 49, 80) // #1E3150
    private fun accentColor(): Int = Color.rgb(94, 161, 255)   // #5EA1FF
    private fun primaryBlue(): Int = Color.rgb(47, 111, 237)   // #2F6FED
    private fun recordRed(): Int = Color.rgb(255, 69, 77)      // #FF454D
    private fun textMainColor(): Int = Color.rgb(245, 247, 251)
    private fun textBodyColor(): Int = Color.rgb(245, 247, 251)
    private fun textMutedColor(): Int = Color.rgb(148, 163, 189)
    private fun textDisabledColor(): Int = Color.rgb(102, 117, 142)
    private fun appBackground(): GradientDrawable = GradientDrawable().apply { setColor(bgColor()); cornerRadius = 0f }

    private fun surfaceBg(color: Int, radiusDp: Int, strokeColor: Int? = borderColor()): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }
    }

    private fun navyCardBg(start: Int = cardColor(), end: Int = cardColor(), stroke: Int = borderColor()): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun applyInsets(root: View, baseLeft: Int = 20, baseTop: Int = 20, baseRight: Int = 20, baseBottom: Int = 24) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(dp(baseLeft), insets.systemWindowInsetTop + dp(baseTop), dp(baseRight), insets.systemWindowInsetBottom + dp(baseBottom))
            insets
        }
    }

    private fun ellipsize(tv: TextView, lines: Int) {
        tv.maxLines = lines
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.includeFontPadding = true
    }

    private fun setStopVisual(active: Boolean) {
        if (!::stopButton.isInitialized) return
        stopButton.isEnabled = active
        stopButton.alpha = if (active) 1.0f else 0.52f
        stopButton.setTextColor(if (active) Color.rgb(255, 235, 235) else Color.rgb(138, 151, 171))
        stopButton.background = if (active) {
            navyCardBg(Color.rgb(47, 16, 25), Color.rgb(18, 11, 20), Color.argb(95, 248, 113, 113))
        } else {
            navyCardBg(Color.rgb(12, 19, 32), Color.rgb(8, 13, 23), Color.argb(48, 94, 118, 158))
        }
    }

    private fun startRecordPulse() {
        val frame = recordButtonFrameView ?: return
        val ring = recordRingView ?: frame
        if (recordPulse1 != null) return
        recordPulse1 = ObjectAnimator.ofFloat(frame, "scaleX", 1.0f, 1.12f).apply { duration = 620; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start() }
        recordPulse2 = ObjectAnimator.ofFloat(frame, "scaleY", 1.0f, 1.12f).apply { duration = 620; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start() }
        recordPulse3 = ObjectAnimator.ofFloat(ring, "alpha", 0.35f, 1.0f).apply { duration = 520; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start() }
    }

    private fun stopRecordPulse() {
        recordPulse1?.cancel(); recordPulse2?.cancel(); recordPulse3?.cancel()
        recordPulse1 = null; recordPulse2 = null; recordPulse3 = null
        recordButtonFrameView?.scaleX = 1.0f; recordButtonFrameView?.scaleY = 1.0f; recordRingView?.alpha = 1.0f
    }


    private fun setHeroIdle() {
        if (!::recordButton.isInitialized) return
        stopRecordPulse()
        recordButton.text = "녹음 시작"
        recordButton.textSize = 20f
        recordButton.isEnabled = true
        recordButton.setOnClickListener { startRecordingIfReady() }
        recordButtonFrameView?.isEnabled = true
        recordButtonFrameView?.setOnClickListener { startRecordingIfReady() }
        recordCardView?.isEnabled = true
        recordCardView?.setOnClickListener { startRecordingIfReady() }
        if (::heroSubtitleText.isInitialized) heroSubtitleText.text = "탭하여 시작"
    }

    private fun setHeroBusy(title: String, subtitle: String) {
        if (!::recordButton.isInitialized) return
        val recording = title.contains("녹음")
        recordButton.text = if (recording) "녹음 종료" else title
        recordButton.textSize = if (recording) 26f else 28f
        recordButton.isEnabled = recording
        recordButton.setOnClickListener { if (recording) stopRecordingAndUpload() }
        recordButtonFrameView?.isEnabled = recording
        recordButtonFrameView?.setOnClickListener { if (recording) stopRecordingAndUpload() }
        recordCardView?.isEnabled = recording
        recordCardView?.setOnClickListener { if (recording) stopRecordingAndUpload() }
        if (::heroSubtitleText.isInitialized) heroSubtitleText.text = subtitle
        if (recording) startRecordPulse() else stopRecordPulse()
    }

    private fun showStatus(main: String, sub: String, color: Int = Color.rgb(191, 219, 254), progress: Int? = null) {
        if (!::statusText.isInitialized) return
        statusText.text = if (sub.isBlank()) "● $main" else "● $main"
        statusText.setTextColor(color)
        if (::progressBar.isInitialized && ::progressText.isInitialized) {
            if (progress == null) {
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
            } else {
                progressBar.visibility = View.VISIBLE
                progressText.visibility = View.VISIBLE
                progressBar.progress = progress.coerceIn(0, 100)
                progressText.text = "업로드 $progress%"
            }
        }
    }

    private fun showIdleStatus() {
        setHeroIdle()
        setStopVisual(false)
        if (::historyButton.isInitialized) historyButton.isEnabled = true
        showStatus("녹음 준비 완료", "", accentColor())
    }

    private fun showRecordingStatus(elapsedSec: Long = 0) {
        val mm = elapsedSec / 60
        val ss = elapsedSec % 60
        setHeroBusy("녹음 중", String.format(Locale.KOREA, "%02d:%02d", mm, ss))
        setStopVisual(true)
        if (::historyButton.isInitialized) historyButton.isEnabled = true
        showStatus(String.format(Locale.KOREA, "녹음 중 · %02d:%02d", mm, ss), "", accentColor())
    }

    private fun showStartingStatus() {
        stopRecordPulse()
        recordButton.text = "시작 중"
        recordButton.textSize = 24f
        recordButton.isEnabled = false
        recordButton.setOnClickListener(null)
        recordButtonFrameView?.isEnabled = false
        recordButtonFrameView?.setOnClickListener(null)
        recordCardView?.isEnabled = false
        recordCardView?.setOnClickListener(null)
        setStopVisual(false)
        if (::historyButton.isInitialized) historyButton.isEnabled = true
        showStatus("녹음 시작 중…", "", accentColor())
    }

    private fun showNoSpeechStatus(elapsedSec: Long, silentSec: Long) {
        val mm = elapsedSec / 60
        val ss = elapsedSec % 60
        val sm = silentSec / 60
        val ssilent = silentSec % 60
        setHeroBusy("녹음 중", String.format(Locale.KOREA, "%02d:%02d", mm, ss))
        setStopVisual(true)
        if (::historyButton.isInitialized) historyButton.isEnabled = true
        showStatus(String.format(Locale.KOREA, "녹음 중 %02d:%02d", mm, ss), String.format(Locale.KOREA, "무음 %02d:%02d", sm, ssilent), accentColor())
    }

    private fun showStoppingStatus() {
        stopRecordPulse()
        recordButton.text = "종료 중"
        recordButton.textSize = 24f
        recordButton.isEnabled = false
        recordButton.setOnClickListener(null)
        recordButtonFrameView?.isEnabled = false
        recordButtonFrameView?.setOnClickListener(null)
        recordCardView?.isEnabled = false
        recordCardView?.setOnClickListener(null)
        setStopVisual(false)
        if (::historyButton.isInitialized) historyButton.isEnabled = true
        showStatus("녹음 종료 중…", "", accentColor())
    }

    private fun showFinalizingStatus() {
        showStoppingStatus()
        recordButton.text = "마무리 중"
        showStatus("녹음 마무리 중…", "", accentColor())
    }

    private fun showUploadingStatus(percent: Int? = null) {
        setHeroBusy("업로드 중", "서버로 전송하고 있습니다")
        setStopVisual(false)
        if (::historyButton.isInitialized) historyButton.isEnabled = true
        showStatus(if (percent == null) "업로드 중…" else "업로드 중…", "", accentColor(), percent)
    }

    private fun showCompleteStatus(message: String = "녹음이 안전하게 저장되었습니다") {
        showIdleStatus()
        showStatus("완료", "", accentColor())
    }

    private fun showErrorStatus(message: String = "잠시 후 다시 시도해 주세요") {
        showIdleStatus()
        showStatus("오류 발생", "", recordRed())
    }

    private class WaveformView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(80, 132, 205)
            strokeWidth = 3.5f
            strokeCap = Paint.Cap.ROUND
            alpha = 95
        }
        private val bars = floatArrayOf(.18f,.42f,.28f,.62f,.35f,.78f,.46f,.24f,.56f,.32f,.68f,.22f,.44f,.30f)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat(); if (w <= 0 || h <= 0) return
            val gap = w / (bars.size * 1.65f)
            var x = w - gap
            for (i in bars.indices.reversed()) {
                val bh = h * bars[i]
                val cy = h / 2f
                canvas.drawLine(x, cy - bh / 2f, x, cy + bh / 2f, paint)
                x -= gap * 1.65f
            }
        }
    }

    private fun buildUi() {
        currentScreen = Screen.HOME
        window.statusBarColor = bgColor()
        window.navigationBarColor = bgColor()

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            setBackgroundColor(bgColor())
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = appBackground()
        }
        applyInsets(root, 20, 20, 20, 24)
        scroll.addView(root, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "VocaNote"
            textSize = 34f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textMainColor())
            letterSpacing = -0.025f
            includeFontPadding = true
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "v$APP_VERSION"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(textMainColor())
            setPadding(dp(12), 0, dp(12), 0)
            background = surfaceBg(secondaryColor(), 12, borderColor())
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)))

        val recordCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            minimumHeight = dp(220)
            background = surfaceBg(cardColor(), 28, borderColor())
            isClickable = true
            isFocusable = true
            setOnClickListener { if (isRecording) stopRecordingAndUpload() else startRecordingIfReady() }
        }
        val recordButtonFrame = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { if (isRecording) stopRecordingAndUpload() else startRecordingIfReady() }
        }
        val outer = TextView(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(34, 94, 161, 255)); setStroke(dp(1), Color.argb(160, 94, 161, 255)) } }
        val red = TextView(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(recordRed()) } }
        recordButtonFrame.addView(outer, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
        recordButtonFrame.addView(red, FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER))
        recordButton = TextView(this).apply {
            text = "녹음 시작"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(textMainColor())
            includeFontPadding = true
            setOnClickListener { if (isRecording) stopRecordingAndUpload() else startRecordingIfReady() }
        }
        heroSubtitleText = TextView(this).apply {
            text = ""
            textSize = 1f
            visibility = View.GONE
        }
        statusText = TextView(this).apply {
            text = "● 녹음 준비 완료"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.NORMAL)
            setTextColor(accentColor())
            gravity = Gravity.CENTER
            includeFontPadding = true
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0; visibility = View.GONE }
        progressText = TextView(this).apply { text = ""; textSize = 14f; visibility = View.GONE; setTextColor(textMutedColor()); gravity = Gravity.CENTER }
        recordButtonFrameView = recordButtonFrame
        recordRingView = outer
        recordCardView = recordCard
        recordCard.addView(recordButtonFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112)))
        recordCard.addView(recordButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(14), 0, 0) })
        recordCard.addView(statusText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(20), 0, 0) })
        recordCard.addView(progressBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { setMargins(0, dp(12), 0, 0) })
        recordCard.addView(progressText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) })

        historyButton = TextView(this).apply {
            val preview = lastHistoryItems.firstOrNull()?.let { nonRepeatingSnippet(it).ifBlank { it.title } } ?: "최근 녹음을 여기서 확인하세요"
            text = "녹음 목록                              >\n$preview"
            textSize = 21f
            setLineSpacing(dp(6).toFloat(), 1.0f)
            setTypeface(null, android.graphics.Typeface.NORMAL)
            setTextColor(textMainColor())
            setPadding(dp(20), dp(18), dp(20), dp(18))
            minimumHeight = dp(92)
            background = surfaceBg(cardColor(), 22, borderColor())
            setOnClickListener { openHistory() }
        }
        stopButton = TextView(this).apply { visibility = View.GONE; setOnClickListener { stopRecordingAndUpload() } }

        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(recordCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(20), 0, 0) })
        root.addView(historyButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(20), 0, 0) })
        setContentView(scroll)
        showIdleStatus()
    }

    private fun restoreRecordingState() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val durableIsRecording = prefs.getBoolean(KEY_IS_RECORDING, false)
        val durablePath = prefs.getString(RecordingService.KEY_CURRENT_PATH, null)
        val durableClientId = prefs.getString(RecordingService.KEY_CLIENT_ID, null)
        val pendingPath = prefs.getString(RecordingService.KEY_START_PENDING_PATH, null)
        val pendingClientId = prefs.getString(RecordingService.KEY_START_PENDING_CLIENT_ID, null)
        if (durableIsRecording && !durablePath.isNullOrBlank() && !durableClientId.isNullOrBlank()) {
            currentFile = File(durablePath)
            currentClientRecordingId = durableClientId
            isStarting = false
        } else if (!pendingPath.isNullOrBlank() && !pendingClientId.isNullOrBlank()) {
            currentFile = File(pendingPath)
            currentClientRecordingId = pendingClientId
            isStarting = true
        } else {
            val path = prefs.getString(KEY_CURRENT_PATH, null)
            if (!path.isNullOrBlank()) currentFile = File(path)
            currentClientRecordingId = prefs.getString(KEY_CURRENT_CLIENT_ID, null)
        }
        startedAtMs = prefs.getLong(KEY_STARTED_AT_MS, startedAtMs)
        reconcileRecordingUiFromDurableState()
    }

    private fun reconcileRecordingUiFromDurableState() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val durableIsRecording = prefs.getBoolean(KEY_IS_RECORDING, false)
        val durableStopPending = prefs.getBoolean(RecordingService.KEY_FINALIZATION_PENDING, false)
        if (isStarting) {
            val outcome = RecordingIdentityPolicy.startOutcome(
                requested = RecordingIdentityPolicy.Identity(currentFile?.absolutePath, currentClientRecordingId),
                durableCurrent = RecordingIdentityPolicy.Identity(
                    prefs.getString(RecordingService.KEY_CURRENT_PATH, null),
                    prefs.getString(RecordingService.KEY_CLIENT_ID, null)
                ),
                durableIsRecording = durableIsRecording,
                failed = RecordingIdentityPolicy.Identity(
                    currentClientRecordingId?.let { prefs.getString(RecordingService.startFailurePathKey(it), null) },
                    currentClientRecordingId
                )
            )
            when (outcome) {
                RecordingIdentityPolicy.StartOutcome.STARTED -> isStarting = false
                RecordingIdentityPolicy.StartOutcome.FAILED -> {
                    isStarting = false
                    showErrorStatus("녹음을 시작하지 못했습니다")
                    return
                }
                RecordingIdentityPolicy.StartOutcome.WAIT -> {
                    if (currentScreen == Screen.HOME) showStartingStatus()
                    startService(Intent(this, RecordingService::class.java).apply {
                        action = RecordingService.ACTION_QUERY_START_OUTCOME
                        putExtra(RecordingService.EXTRA_PATH, currentFile?.absolutePath)
                        putExtra(RecordingService.EXTRA_CLIENT_ID, currentClientRecordingId)
                    })
                    return
                }
            }
        }
        val decision = RecordingUiReconciliationPolicy.reconcile(
            uiIsRecording = isRecording,
            uiIsStopping = isStopping,
            durableIsRecording = durableIsRecording,
            durableStopPending = durableStopPending,
            isHomeScreen = currentScreen == Screen.HOME
        )
        isRecording = decision.isRecording
        isStopping = decision.isStopping
        if (decision.runTicker) {
            if (startedAtMs <= 0L) startedAtMs = System.currentTimeMillis()
            if (lastVoiceAtMs <= 0L) lastVoiceAtMs = startedAtMs
            when (decision.render) {
                RecordingUiReconciliationPolicy.Render.RECORDING -> renderRecordingTick()
                else -> Unit
            }
            startRecordingStatusTicker()
        } else {
            stopRecordingStatusTicker()
            latestAmplitude = 0
            when (decision.render) {
                RecordingUiReconciliationPolicy.Render.IDLE -> showIdleStatus()
                RecordingUiReconciliationPolicy.Render.STOPPING -> showStoppingStatus()
                RecordingUiReconciliationPolicy.Render.FINALIZING -> showFinalizingStatus()
                else -> Unit
            }
        }
    }

    private fun saveRecordingState(path: String): Boolean = RecordingStartIdentityLock.withLock {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val existingPending = RecordingIdentityPolicy.Identity(
            prefs.getString(RecordingService.KEY_START_PENDING_PATH, null),
            prefs.getString(RecordingService.KEY_START_PENDING_CLIENT_ID, null)
        )
        if (existingPending.isComplete()) return@withLock false
        prefs.edit()
            .putString(RecordingService.KEY_START_PENDING_PATH, path)
            .putString(RecordingService.KEY_START_PENDING_CLIENT_ID, currentClientRecordingId)
            .putString(KEY_CURRENT_CLIENT_ID, currentClientRecordingId)
            .putLong(KEY_STARTED_AT_MS, startedAtMs)
            .commit()
    }

    private fun startRecordingIfReady() {
        if (isStarting || isRecording || isStopping) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_IS_RECORDING, false) ||
            prefs.getBoolean(RecordingService.KEY_FINALIZATION_PENDING, false)
        ) {
            reconcileRecordingUiFromDurableState()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
            return
        }
        val ts = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.KOREA).format(Date())
        val out = File(getExternalFilesDir(null), "${ts}_무제회의.m4a")
        currentClientRecordingId = UUID.randomUUID().toString()
        currentFile = out
        startedAtMs = System.currentTimeMillis()
        if (!saveRecordingState(out.absolutePath)) {
            currentFile = null
            currentClientRecordingId = null
            showErrorStatus("녹음 시작 정보를 저장하지 못했습니다")
            return
        }
        lastVoiceAtMs = startedAtMs
        latestAmplitude = 0
        val svc = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_PATH, out.absolutePath)
            putExtra(RecordingService.EXTRA_CLIENT_ID, currentClientRecordingId)
            putExtra(RecordingService.EXTRA_STARTED_AT_MS, startedAtMs)
        }
        isStarting = true
        showStartingStatus()
        startForegroundService(svc)
    }

    private fun startRecordingStatusTicker() {
        handler.removeCallbacks(recordingStatusTicker)
        handler.post(recordingStatusTicker)
    }

    private fun stopRecordingStatusTicker() {
        handler.removeCallbacks(recordingStatusTicker)
    }

    private fun renderRecordingTick() {
        val now = System.currentTimeMillis()
        val elapsed = (now - startedAtMs) / 1000
        val silent = (now - lastVoiceAtMs) / 1000
        if (silent >= 5) {
            showNoSpeechStatus(elapsed, silent)
        } else {
            showRecordingStatus(elapsed)
        }
    }

    private fun stopRecordingAndUpload() {
        if (isStarting || !isRecording || isStopping) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val path = currentFile?.absolutePath ?: prefs.getString(KEY_CURRENT_PATH, null)
        val clientId = currentClientRecordingId
            ?: prefs.getString(KEY_CURRENT_CLIENT_ID, null)
            ?: prefs.getString(RecordingService.KEY_CLIENT_ID, null)
        val startedAt = startedAtMs.takeIf { it > 0L }
            ?: prefs.getLong(KEY_STARTED_AT_MS, 0L)
        val stoppedAt = System.currentTimeMillis()
        if (path.isNullOrBlank() || clientId.isNullOrBlank() || startedAt <= 0L || stoppedAt < startedAt) {
            showErrorStatus("녹음 종료 정보를 확인하지 못했습니다")
            return
        }
        if (!RecordingService.persistFinalizationIntent(this, path, clientId, startedAt, stoppedAt)) {
            showErrorStatus("녹음 종료 정보를 저장하지 못했습니다")
            return
        }
        currentFile = File(path)
        currentClientRecordingId = clientId
        val svc = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
            putExtra(RecordingService.EXTRA_PATH, path)
            putExtra(RecordingService.EXTRA_CLIENT_ID, clientId)
            putExtra(RecordingService.EXTRA_STARTED_AT_MS, startedAt)
            putExtra(RecordingService.EXTRA_STOPPED_AT_MS, stoppedAt)
        }
        startService(svc)
        isStopping = true
        stopRecordingStatusTicker()
        showStoppingStatus()
    }


    private fun fetchLatestResult(shareAfterFetch: Boolean = false) {
        val id = lastRecordingId
        if (id.isNullOrBlank()) {
            showErrorStatus("업로드 결과를 아직 확인하지 못했습니다")
            return
        }
        showStatus("처리 결과 확인 중", "전사/요약 상태를 확인하고 있습니다", Color.rgb(96, 165, 250))
        thread {
            try {
                val status = httpGet("$serverUrl/api/recordings/$id", uploadToken)
                val root = org.json.JSONObject(status)
                val displayVersion = root.optString("display_version", root.optString("available_version", ""))
                val fastAvailable = root.optBoolean("fast_available", false)
                val finalAvailable = root.optBoolean("final_available", root.optString("status") == "completed")
                if (fastAvailable || finalAvailable) {
                    val detail = httpGet("$serverUrl/api/recordings/$id/detail", uploadToken)
                    applyDetailJson(id, detail)
                    runOnUiThread {
                        showDetailScreen("transcript")
                        if (shareAfterFetch) shareText(lastSummaryText.ifBlank { lastTranscriptText })
                    }
                    if (!finalAvailable) {
                        handler.postDelayed({ refreshCurrentDetailIfNeeded(id) }, 7000)
                    }
                    return@thread
                }
                runOnUiThread {
                    showStatus("처리 중", "전사/요약 완료 후 자동으로 열립니다", Color.rgb(96, 165, 250))
                    handler.postDelayed({ fetchLatestResult(shareAfterFetch) }, 7000)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    android.util.Log.e("VocaNote", "fetch result failed", e)
                    showErrorStatus("잠시 후 다시 시도해 주세요")
                }
            }
        }
    }

    private fun openHistory() {
        currentScreen = Screen.RECORDING_LIST
        showStatus("녹음 목록 확인 중", "저장된 녹음을 불러오고 있습니다", Color.rgb(147, 197, 253))
        thread {
            val localItems = LocalRecordingStore.load(this).filter { File(it.localAudioPath).exists() }.map { rec ->
                val localTitle = rec.serverTitle ?: rec.localTitle
                val displayTitle = if (localTitle.isNullOrBlank() || localTitle == "무제 녹음" || localTitle == "무제회의") "로컬 녹음 ${compactDate(rec.createdAt)}" else localTitle
                HistoryItem(
                    id = rec.serverRecordingId ?: "local:${rec.clientRecordingId}",
                    title = displayTitle,
                    recordedAt = rec.createdAt,
                    status = LocalRecordingStore.displayStatus(rec.uploadStatus),
                    snippet = rec.lastError?.let { "서버 복구 후 자동 업로드됩니다" } ?: "로컬 파일 보관 중",
                    keywords = emptyList(),
                    durationSec = (rec.durationMs / 1000L).coerceAtLeast(0L),
                    isLocal = rec.serverRecordingId.isNullOrBlank()
                )
            }
            try {
                val body = httpGet("$serverUrl/api/recordings", uploadToken, listOpenedSignal = true)
                val root = org.json.JSONObject(body)
                val arr = root.optJSONArray("items") ?: org.json.JSONArray()
                val serverItems = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val serverTitle = o.optString("display_title", o.optString("title", o.optString("id")))
                    val serverSnippet = o.optString("snippet", "")
                    HistoryItem(
                        id = o.optString("id"),
                        title = if (serverTitle.isBlank() || serverTitle == "무제 녹음" || serverTitle == "무제회의") serverSnippet.take(24).ifBlank { o.optString("id") } else serverTitle,
                        recordedAt = o.optString("recorded_at", ""),
                        status = o.optString("status", ""),
                        snippet = serverSnippet,
                        keywords = extractJsonArrayStrings(o.toString(), "keywords"),
                        durationSec = o.optLong("duration_sec", 0L),
                        isLocal = false
                    )
                }.filter { it.id.isNotBlank() }
                val serverIds = serverItems.map { it.id }.toSet()
                val localOnly = localItems.filter { it.id.startsWith("local:") }
                val merged = (serverItems + localOnly).sortedByDescending { normalizeSortKey(it.recordedAt) }
                runOnUiThread { showHistoryScreen(merged) }
            } catch (e: Exception) {
                runOnUiThread {
                    android.util.Log.e("VocaNote", "history failed", e)
                    showHistoryScreen(localItems.sortedByDescending { normalizeSortKey(it.recordedAt) })
                    showStatus("녹음 목록", "서버 목록은 나중에 다시 확인합니다", Color.rgb(251, 191, 36))
                }
            }
        }
    }

    private fun displayStatusLabel(raw: String): String = when (raw.lowercase(Locale.US)) {
        "uploading", "upload_running", "upload_in_progress", "server_accepted", "업로드 중" -> "업로드 중"
        "stt_running", "stt_processing", "transcribing", "speech_to_text" -> "음성 인식 중"
        "fast_running" -> "빠른 결과 준비 중"
        "fast_ready", "final_running" -> "빠른 결과 · 최종 교정 중"
        "correction_running", "correcting", "polishing", "cleanup_running" -> "문장 정리 중"
        "semantic_running", "analysis_running", "analyzing", "reduce_running" -> "내용 분석 중"
        "validating", "rendering", "finalizing", "finalize_running" -> "마무리 중"
        "completed", "complete", "done", "완료" -> "완료"
        "retry_wait", "retry_requested", "stale_recovered", "retrying" -> "재처리 중"
        "queued", "processing", "pending", "created" -> "처리 중"
        "upload_pending", "upload_failed", "업로드 대기 중" -> "처리 중"
        "failed", "error", "실패" -> "실패"
        else -> raw.ifBlank { "마무리 중" }
    }

    private fun compactDate(value: String): String {
        if (value.isBlank()) return ""
        return try {
            val normalized = value.replace("T", " ").replace("+09:00", "")
            val nowPrefix = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
            val date = normalized.take(10)
            val hm = normalized.drop(11).take(5)
            if (date == nowPrefix) "오늘 $hm" else date.replace("-", ".") + " " + hm
        } catch (_: Exception) { value.take(16) }
    }

    private fun durationLabel(seconds: Long): String {
        if (seconds <= 0L) return ""
        val h = seconds / 3600L
        val m = (seconds % 3600L) / 60L
        val s = seconds % 60L
        return when {
            h > 0L -> "${h}시간 ${m}분"
            m > 0L -> "${m}분 ${s}초"
            else -> "${s}초"
        }
    }

    private fun durationClockLabel(seconds: Long): String {
        val h = seconds / 3600L
        val m = (seconds % 3600L) / 60L
        val s = seconds % 60L
        return if (h > 0L) String.format(Locale.KOREA, "%d:%02d:%02d", h, m, s) else String.format(Locale.KOREA, "%02d:%02d", m, s)
    }

    private fun historyMetaLine(item: HistoryItem): String {
        val parts = mutableListOf<String>()
        val date = compactDate(item.recordedAt)
        if (date.isNotBlank()) parts.add(date)
        val duration = durationLabel(item.durationSec)
        if (duration.isNotBlank()) parts.add(duration)
        parts.add(displayStatusLabel(item.status))
        return parts.joinToString(" · ")
    }

    private fun shortTag(raw: String): String? {
        val cleaned = raw.replace("#", "").replace(Regex("[\n\r]+"), " ").trim()
        if (cleaned.isBlank()) return null
        if (cleaned.length > 16) return null
        if (cleaned.split(Regex("""\s+""")).size > 3) return null
        return cleaned.replace(" ", "")
    }

    private fun keywordSummary(keywords: List<String>, title: String = "", snippet: String = ""): String {
        val titleNorm = title.lowercase(Locale.KOREA)
        val snippetNorm = snippet.lowercase(Locale.KOREA)
        val tags = keywords.mapNotNull { shortTag(it) }
            .filterNot { tag -> titleNorm == tag.lowercase(Locale.KOREA) || snippetNorm.contains(tag.lowercase(Locale.KOREA)) && tag.length > 8 }
            .distinctBy { it.lowercase(Locale.KOREA) }
            .take(3)
        return tags.joinToString("   ") { "#${it}" }
    }

    private fun nonRepeatingSnippet(item: HistoryItem): String {
        val snippet = item.snippet.trim()
        if (snippet.isBlank()) return ""
        val t = item.title.trim().lowercase(Locale.KOREA)
        val s = snippet.lowercase(Locale.KOREA)
        return if (s == t || (t.length > 8 && s.startsWith(t))) "" else snippet
    }

    private fun normalizeSortKey(value: String): String = value.replace("T", " ").replace("+09:00", "")

    private fun showHistoryScreen(items: List<HistoryItem>) {
        currentScreen = Screen.RECORDING_LIST
        lastHistoryItems = items
        stopPlayback()
        window.statusBarColor = bgColor()
        window.navigationBarColor = bgColor()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = appBackground()
        }
        applyInsets(root, 20, 16, 20, 24)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
        }
        fun iconButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 24f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.NORMAL)
            setTextColor(accentColor())
            background = surfaceBg(secondaryColor(), 16, borderColor())
            setOnClickListener { action() }
        }
        header.addView(iconButton("‹") { showHome(resetTransient = true); restoreRecordingState() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(TextView(this).apply {
            text = "녹음 목록"
            textSize = 30f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(textMainColor())
            includeFontPadding = true
            setPadding(dp(16), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton("↻") { openHistory() }, LinearLayout.LayoutParams(dp(40), dp(40)))

        val search = EditText(this).apply {
            hint = "녹음에서 검색"
            textSize = 16f
            setSingleLine(true)
            setTextColor(textMainColor())
            setHintTextColor(textMutedColor())
            background = surfaceBg(secondaryColor(), 18, borderColor())
            setPadding(dp(16), 0, dp(16), 0)
        }
        val scroll = ScrollView(this).apply { clipToPadding = false }
        historyScrollView = scroll
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(120)) }
        fun render(filtered: List<HistoryItem>) {
            list.removeAllViews()
            if (filtered.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = "녹음 내역이 없습니다."
                    textSize = 16f
                    setTextColor(textMutedColor())
                    setPadding(dp(18), dp(24), dp(18), dp(24))
                    background = surfaceBg(cardColor(), 22, borderColor())
                })
                return
            }
            filtered.forEach { item ->
                val deleting = deletingRecordingIds.contains(item.id)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    minimumHeight = dp(108)
                    background = surfaceBg(cardColor(), 22, borderColor())
                    alpha = if (deleting) 0.62f else 1.0f
                    setOnClickListener { if (deleting) statusToast("삭제 중…") else if (item.isLocal) statusToast("업로드 대기 중입니다") else fetchDetailAndShow(item.id) }
                }
                val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
                titleRow.addView(TextView(this).apply {
                    text = item.title.ifBlank { if (displayStatusLabel(item.status) == "완료") "제목 없음" else "제목 생성 중…" }
                    textSize = 17f
                    setTypeface(null, android.graphics.Typeface.NORMAL)
                    setTextColor(textMainColor())
                    setLineSpacing(dp(1).toFloat(), 1.0f)
                    ellipsize(this, 2)
                    setPadding(0, 0, dp(8), 0)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                titleRow.addView(TextView(this).apply {
                    text = if (deleting) "삭제 중…" else "⋮"
                    textSize = if (deleting) 14f else 24f
                    gravity = Gravity.CENTER
                    setTextColor(if (deleting) textMutedColor() else textMutedColor())
                    isEnabled = !deleting
                    setOnClickListener { v -> showRecordingItemMenu(item, v) }
                }, LinearLayout.LayoutParams(dp(42), dp(36)))
                card.addView(titleRow)
                val cleanSnippet = nonRepeatingSnippet(item)
                card.addView(TextView(this).apply {
                    val done = displayStatusLabel(item.status) == "완료"
                    text = cleanSnippet.ifBlank { if (done) "요약 없음" else "요약 생성 중…" }
                    textSize = if (cleanSnippet.isBlank()) 12.5f else 13.5f
                    setTextColor(if (cleanSnippet.isBlank()) textDisabledColor() else textMutedColor())
                    setTypeface(null, android.graphics.Typeface.NORMAL)
                    setLineSpacing(dp(1).toFloat(), 1.0f)
                    ellipsize(this, 2)
                    setPadding(0, dp(6), 0, 0)
                })
                card.addView(TextView(this).apply {
                    text = historyMetaLine(item)
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.NORMAL)
                    setTextColor(textMutedColor())
                    setPadding(0, dp(6), 0, 0)
                    includeFontPadding = true
                })
                val kw = keywordSummary(item.keywords, item.title, cleanSnippet)
                if (kw.isNotBlank()) card.addView(TextView(this).apply {
                    text = kw
                    textSize = 12.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(accentColor())
                    maxLines = 1
                    setPadding(0, dp(6), 0, 0)
                })
                list.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
            }
        }
        search.setOnEditorActionListener { _, _, _ ->
            val q = search.text.toString()
            render(items.filter { q.isBlank() || it.title.contains(q, true) || it.snippet.contains(q, true) || it.keywords.joinToString(" ").contains(q, true) })
            true
        }
        search.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) render(items) }
        render(items)
        scroll.addView(list)
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(16)) })
        root.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { setMargins(0, 0, 0, dp(16)) })
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        pendingHistoryScrollY?.let { y ->
            pendingHistoryScrollY = null
            scroll.post { scroll.scrollTo(0, y.coerceAtLeast(0)) }
        }
    }

    private fun showRecordingItemMenu(item: HistoryItem, anchor: View) {
        if (deletingRecordingIds.contains(item.id)) {
            statusToast("삭제 중…")
            return
        }
        PopupMenu(this, anchor).apply {
            if (item.isLocal) menu.add("업로드 다시 시도")
            menu.add("삭제")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "업로드 다시 시도" -> { retryLocalUpload(item); true }
                    "삭제" -> { confirmDeleteRecording(item); true }
                    else -> false
                }
            }
            show()
        }
    }

    private fun retryLocalUpload(item: HistoryItem) {
        val clientId = item.id.removePrefix("local:")
        thread {
            val queued = LocalRecordingStore.requestManualUploadRetry(this, clientId)
            runOnUiThread {
                statusToast(if (queued) "업로드를 다시 예약했습니다" else "현재는 업로드를 다시 시도할 수 없습니다")
                if (queued) openHistory()
            }
        }
    }

    private fun confirmDeleteRecording(item: HistoryItem) {
        if (deletingRecordingIds.contains(item.id)) return
        AlertDialog.Builder(this)
            .setTitle("녹음을 삭제할까요?")
            .setMessage("이 녹음과 전사 및 분석 결과가 삭제됩니다.\n삭제 후 앱에서는 복구할 수 없습니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ -> deleteRecording(item.id) }
            .show()
    }

    private fun deleteRecording(recordingId: String) {
        if (!deletingRecordingIds.add(recordingId)) {
            statusToast("삭제 중…")
            return
        }
        val scrollBefore = historyScrollView?.scrollY ?: 0
        pendingHistoryScrollY = scrollBefore
        showHistoryScreen(lastHistoryItems)
        if (recordingId.startsWith("local:")) {
            val clientId = recordingId.removePrefix("local:")
            val forget = try {
                LocalRecordingStore.tryForgetLocal(this, recordingId)
            } catch (_: Exception) {
                deletingRecordingIds.remove(recordingId)
                pendingHistoryScrollY = scrollBefore
                showHistoryScreen(lastHistoryItems)
                Toast.makeText(this, "삭제 상태를 저장하지 못했습니다. 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
                return
            }
            when (forget.decision) {
                UploadLifecyclePolicy.ForgetDecision.DEFER_UPLOAD_IN_FLIGHT -> {
                    deletingRecordingIds.remove(recordingId)
                    pendingHistoryScrollY = scrollBefore
                    showHistoryScreen(lastHistoryItems)
                    Toast.makeText(this, "업로드가 끝난 뒤 삭제해주세요.", Toast.LENGTH_SHORT).show()
                    return
                }
                UploadLifecyclePolicy.ForgetDecision.REQUIRE_SERVER_DELETE -> {
                    deletingRecordingIds.remove(recordingId)
                    val serverId = forget.serverRecordingId
                    if (!serverId.isNullOrBlank()) deleteRecording(serverId)
                    else {
                        pendingHistoryScrollY = scrollBefore
                        showHistoryScreen(lastHistoryItems)
                        Toast.makeText(this, "서버 기록을 확인한 뒤 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                UploadLifecyclePolicy.ForgetDecision.DELETE_LOCALLY -> Unit
            }
            if (!forget.removed) {
                deletingRecordingIds.remove(recordingId)
                pendingHistoryScrollY = scrollBefore
                showHistoryScreen(lastHistoryItems)
                Toast.makeText(this, "녹음을 삭제하지 못했습니다. 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
                return
            }
            androidx.work.WorkManager.getInstance(this).cancelUniqueWork("vocanote_finalize_$clientId")
            androidx.work.WorkManager.getInstance(this).cancelUniqueWork("vocanote_edit_grace_$clientId")
            androidx.work.WorkManager.getInstance(this).cancelUniqueWork(UploadRecoveryPolicy.workName(clientId))
            LocalEditStateStore.remove(this, clientId)
            deletingRecordingIds.remove(recordingId)
            pendingHistoryScrollY = scrollBefore
            showHistoryScreen(lastHistoryItems.filterNot { it.id == recordingId })
            Toast.makeText(this, "녹음이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        thread {
            var success = false
            var message = "녹음을 삭제하지 못했습니다. 잠시 후 다시 시도해주세요."
            try {
                val result = VocaNoteApiClient.deleteRecording(serverUrl, uploadToken, recordingId)
                success = result.ok && result.recordingId == recordingId && result.deleteState == "deleted"
                if (success) {
                    success = LocalRecordingStore.cleanupAfterServerDelete(this, recordingId)
                    message = if (success) {
                        "녹음이 삭제되었습니다."
                    } else {
                        "서버 기록은 삭제됐지만 휴대폰 파일을 확인하지 못했습니다. 다시 시도해주세요."
                    }
                } else {
                    message = "삭제하지 못했습니다. 서버 연결 후 다시 시도해주세요."
                }
            } catch (e: VocaNoteApiClient.HttpStatusException) {
                if (e.code == 404) {
                    // Fail closed: even authenticated 404 keeps the durable local row so the
                    // user can retry; a generic list is not an exhaustive deletion proof.
                    success = false
                    message = "서버 삭제를 확인하지 못했습니다. 다시 시도해주세요."
                } else {
                    message = "삭제하지 못했습니다. 서버 연결 후 다시 시도해주세요."
                }
            } catch (e: java.net.SocketTimeoutException) {
                success = false
                message = "삭제 여부를 확인하지 못했습니다. 서버 연결 후 다시 시도해주세요."
            } catch (e: IOException) {
                message = "삭제하지 못했습니다. 서버 연결 후 다시 시도해주세요."
            } catch (e: Exception) {
                message = "삭제하지 못했습니다. 잠시 후 다시 시도해주세요."
            }
            runOnUiThread {
                deletingRecordingIds.remove(recordingId)
                if (success) {
                    val filtered = lastHistoryItems.filterNot { it.id == recordingId }
                    pendingHistoryScrollY = scrollBefore
                    showHistoryScreen(filtered)
                    handler.postDelayed({ pendingHistoryScrollY = historyScrollView?.scrollY ?: scrollBefore; openHistory() }, 400)
                }
                Toast.makeText(this, message.replace("\n", " "), Toast.LENGTH_SHORT).show()
                if (!success) {
                    pendingHistoryScrollY = scrollBefore
                    showHistoryScreen(lastHistoryItems)
                }
            }
        }
    }

    private fun applyDetailJson(id: String, body: String) {
        val root = org.json.JSONObject(body)
        lastRecordingId = id
        lastDetailTitle = root.optString("display_title", root.optString("title", "")).ifBlank { "제목 생성 중…" }
        lastDetailRecordedAt = root.optString("recorded_at", "")
        lastDetailDurationSec = root.optLong("duration_sec", 0L)
        lastSummaryText = root.optString("summary", "")
        lastTranscriptText = root.optString("transcript", "")
        lastAnalysisText = root.optString("analysis", "")
        lastDisplayVersion = root.optString("display_version", root.optString("available_version", ""))
        lastProcessingState = root.optString("processing_state", root.optString("status", ""))
        lastFastAvailable = root.optBoolean("fast_available", false)
        lastFinalAvailable = root.optBoolean("final_available", root.optString("status") == "completed")
        lastKeywords = extractJsonArrayStrings(body, "keywords")
        lastSegments = extractSegments(body)
    }

    private fun refreshCurrentDetailIfNeeded(id: String) {
        if (currentScreen != Screen.RECORDING_DETAIL || lastRecordingId != id || lastFinalAvailable) return
        thread {
            try {
                val enc = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
                val body = httpGet("$serverUrl/api/recordings/$enc/detail", uploadToken)
                val root = org.json.JSONObject(body)
                val finalAvailable = root.optBoolean("final_available", root.optString("status") == "completed")
                runOnUiThread {
                    applyDetailJson(id, body)
                    if (finalAvailable) {
                        showDetailScreen(activeTab)
                        statusToast("최종본으로 갱신했습니다")
                    } else {
                        handler.postDelayed({ refreshCurrentDetailIfNeeded(id) }, 7000)
                    }
                }
            } catch (_: Exception) {
                handler.postDelayed({ refreshCurrentDetailIfNeeded(id) }, 10000)
            }
        }
    }

    private fun fetchDetailAndShow(id: String) {
        currentScreen = Screen.RECORDING_DETAIL
        statusText.text = "상세 불러오는 중..."
        thread {
            try {
                val enc = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
                val body = httpGet("$serverUrl/api/recordings/$enc/detail", uploadToken)
                applyDetailJson(id, body)
                runOnUiThread {
                    showDetailScreen("transcript")
                    if (!lastFinalAvailable && lastFastAvailable) handler.postDelayed({ refreshCurrentDetailIfNeeded(id) }, 7000)
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "상세 불러오기 실패: ${e.message}" }
            }
        }
    }

    private fun showDetailScreen(tab: String) {
        currentScreen = Screen.RECORDING_DETAIL
        activeTab = tab
        autoFollowTranscript = true
        lastActiveSegmentIndex = -1
        window.statusBarColor = bgColor()
        window.navigationBarColor = bgColor()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = appBackground()
        }
        applyInsets(root, 20, 12, 20, 24)
        fun smallButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.NORMAL)
            setTextColor(accentColor())
            background = surfaceBg(secondaryColor(), 16, borderColor())
            setOnClickListener { action() }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(smallButton("‹") { stopPlayback(); openHistory() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        top.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (BuildConfig.VOCANOTE_SAFE_EDIT_FOUNDATION_ENABLED && !lastRecordingId.isNullOrBlank()) {
            top.addView(smallButton("편집") { openSafeEditor() }, LinearLayout.LayoutParams(dp(56), dp(40)).apply { setMargins(0, 0, dp(8), 0) })
        }
        top.addView(smallButton("검색") { searchInCurrentDetail() }, LinearLayout.LayoutParams(dp(56), dp(40)).apply { setMargins(0, 0, dp(8), 0) })
        top.addView(smallButton("공유") { showShareOptions(tab) }, LinearLayout.LayoutParams(dp(56), dp(40)))

        val title = TextView(this).apply {
            text = lastDetailTitle
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1.0f)
            setTextColor(textMainColor())
            maxLines = 2
            includeFontPadding = true
        }
        val detailDuration = if (lastDetailDurationSec > 0L) durationClockLabel(lastDetailDurationSec) else lastSegments.lastOrNull()?.let { formatTime(it.endMs) } ?: "00:00"
        val sub = TextView(this).apply {
            val versionLabel = when {
                lastFinalAvailable || lastDisplayVersion.equals("FINAL", ignoreCase = true) -> "완료 · 최종본"
                lastFastAvailable || lastDisplayVersion.equals("FAST", ignoreCase = true) -> "빠른 결과 · 최종 교정 중"
                else -> displayStatusLabel(lastProcessingState)
            }
            text = listOf(compactDate(lastDetailRecordedAt), detailDuration, versionLabel).filter { it.isNotBlank() }.joinToString(" · ")
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.NORMAL)
            setTextColor(textMutedColor())
            includeFontPadding = true
        }
        val chipText = keywordSummary(lastKeywords, lastDetailTitle, lastSummaryText)
        val chips = TextView(this).apply {
            text = chipText
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(accentColor())
            maxLines = 2
            visibility = if (chipText.isBlank()) View.GONE else View.VISIBLE
        }

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = surfaceBg(secondaryColor(), 16, null)
        }
        fun tabBtn(label: String, key: String): TextView = TextView(this).apply {
            val active = tab == key
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.NORMAL)
            setTextColor(if (active) textMainColor() else textMutedColor())
            background = if (active) surfaceBg(primaryBlue(), 12, null) else GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(12).toFloat() }
            setOnClickListener { showDetailScreen(key) }
        }
        tabs.addView(tabBtn("전사", "transcript"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        tabs.addView(tabBtn("요약", "summary"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        tabs.addView(tabBtn("핵심 정리", "analysis"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        val player = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = surfaceBg(cardColor(), 18, borderColor())
        }
        val progressRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        playbackCurrentTimeView = TextView(this).apply { text = "00:00"; textSize = 11.5f; setTextColor(textMutedColor()); gravity = Gravity.CENTER }
        playbackDurationView = TextView(this).apply { text = lastSegments.lastOrNull()?.let { formatTime(it.endMs) } ?: "00:00"; textSize = 11.5f; setTextColor(textMutedColor()); gravity = Gravity.CENTER }
        playbackSeekBar = SeekBar(this).apply {
            max = maxOf(1, lastSegments.lastOrNull()?.endMs ?: 1)
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(accentColor())
            thumbTintList = android.content.res.ColorStateList.valueOf(accentColor())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(borderColor())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) playbackCurrentTimeView?.text = formatTime(progress) }
                override fun onStartTrackingTouch(bar: SeekBar?) { userSeeking = true; autoFollowTranscript = false; followButtonView?.visibility = View.VISIBLE }
                override fun onStopTrackingTouch(bar: SeekBar?) { userSeeking = false; seekToMs(bar?.progress ?: 0); resumeAutoFollow() }
            })
        }
        progressRow.addView(playbackCurrentTimeView, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        progressRow.addView(playbackSeekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
        progressRow.addView(playbackDurationView, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        fun secondaryCtl(label: String, action: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.NORMAL)
            setTextColor(textMainColor())
            background = surfaceBg(secondaryColor(), 16, null)
            setOnClickListener { action() }
        }
        playbackButtonView = TextView(this).apply {
            text = "▶"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(textMainColor())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(primaryBlue()) }
            setOnClickListener { togglePlayback(); resumeAutoFollow() }
        }
        controls.addView(secondaryCtl("1×") { mediaPlayer?.let { it.playbackParams = it.playbackParams.setSpeed(1.0f) } }, LinearLayout.LayoutParams(dp(46), dp(34)))
        controls.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(secondaryCtl("↶5") { seekBy(-5000); resumeAutoFollow() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        controls.addView(playbackButtonView, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(8), 0, dp(8), 0) })
        controls.addView(secondaryCtl("↷5") { seekBy(5000); resumeAutoFollow() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        controls.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        player.addView(progressRow); player.addView(controls)

        followButtonView = TextView(this).apply {
            text = "현재 재생 위치로"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(accentColor())
            background = surfaceBg(secondaryColor(), 16, borderColor())
            visibility = View.GONE
            setOnClickListener { resumeAutoFollow() }
        }

        val scroll = ScrollView(this).apply { clipToPadding = false; setPadding(0, 0, 0, dp(96)); setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_MOVE || ev.action == MotionEvent.ACTION_DOWN) { autoFollowTranscript = false; followButtonView?.visibility = View.VISIBLE }; false } }
        transcriptScrollView = scroll
        if (tab == "transcript" && lastSegments.isNotEmpty()) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = surfaceBg(cardColor(), 18, borderColor())
            }
            transcriptContainerView = container
            segmentViews = mutableListOf()
            lastSegments.forEachIndexed { idx, seg ->
                val tv = TextView(this).apply {
                    text = segmentDisplayText(seg)
                    textSize = 14.5f
                    setLineSpacing(dp(2).toFloat(), 1.0f)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setTextColor(textMainColor())
                    setTextIsSelectable(true)
                    setOnClickListener { seekToMs(seg.startMs); resumeAutoFollow() }
                }
                segmentViews.add(tv)
                container.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
            }
            scroll.addView(container)
            updateActiveSegmentView(activeSegmentIndex(), forceScroll = false)
        } else {
            val body = TextView(this).apply {
                textSize = 14.5f
                setLineSpacing(dp(2).toFloat(), 1.0f)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setTextIsSelectable(true)
                background = surfaceBg(cardColor(), 20, borderColor())
                setTextColor(textMainColor())
                text = currentTabText(tab).ifBlank { "아직 내용이 없습니다." }
            }
            transcriptBodyView = body
            scroll.addView(body)
        }
        root.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(10), 0, 0) })
        root.addView(sub, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) })
        root.addView(chips, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) })
        root.addView(tabs, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { setMargins(0, dp(10), 0, 0) })
        if (tab == "transcript") {
            root.addView(player, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) })
            root.addView(followButtonView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)).apply { setMargins(0, dp(4), 0, 0) })
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, dp(8), 0, 0) })
        setContentView(root)
        if (tab == "transcript") startHighlightTicker()
    }

    private fun segmentDisplayText(seg: TranscriptSegment): String {
        val uniqueSpeakers = lastSegments.map { it.speaker.ifBlank { "화자 1" } }.distinct().size
        val speaker = if (seg.speaker.matches(Regex("S\\d+"))) "화자 ${seg.speaker.drop(1)}" else seg.speaker.ifBlank { "화자 1" }
        return if (uniqueSpeakers <= 1) "${formatTime(seg.startMs)}\n${seg.text}" else "$speaker · ${formatTime(seg.startMs)}\n${seg.text}"
    }

    private fun activeSegmentIndex(): Int {
        val pos = mediaPlayer?.currentPosition ?: playbackSeekBar?.progress ?: 0
        return lastSegments.indexOfFirst { pos >= it.startMs && pos <= it.endMs }.let { if (it < 0 && lastSegments.isNotEmpty()) 0 else it }
    }

    private fun scrollActiveSegmentToCenter(index: Int) {
        val scroll = transcriptScrollView ?: return
        if (index < 0 || index >= segmentViews.size) return
        val view = segmentViews[index]
        view.post {
            val target = (view.top - scroll.height / 2 + view.height / 2).coerceAtLeast(0)
            scroll.smoothScrollTo(0, target)
        }
    }

    private fun updateActiveSegmentView(index: Int, forceScroll: Boolean) {
        if (index < 0 || index >= segmentViews.size) return
        segmentViews.forEachIndexed { i, tv ->
            if (i == index) {
                tv.background = surfaceBg(Color.rgb(16, 37, 65), 14, null)
                tv.setTextColor(textMainColor())
                tv.setTypeface(null, android.graphics.Typeface.NORMAL)
            } else {
                tv.background = null
                tv.setTextColor(textBodyColor())
                tv.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
        if (forceScroll && autoFollowTranscript) scrollActiveSegmentToCenter(index)
    }

    private fun resumeAutoFollow() {
        autoFollowTranscript = true
        followButtonView?.visibility = View.GONE
        val idx = activeSegmentIndex()
        updateActiveSegmentView(idx, forceScroll = true)
    }

    private fun seekToMs(ms: Int) {
        val p = mediaPlayer
        if (p != null) {
            val target = ms.coerceIn(0, p.duration.coerceAtLeast(0))
            p.seekTo(target)
            playbackSeekBar?.progress = target
            playbackCurrentTimeView?.text = formatTime(target)
        } else {
            playbackSeekBar?.progress = ms
            playbackCurrentTimeView?.text = formatTime(ms)
        }
        val idx = activeSegmentIndex()
        lastActiveSegmentIndex = idx
        updateActiveSegmentView(idx, forceScroll = true)
    }

    private fun startHighlightTicker() {
        if (activeTab != "transcript") return
        val p = mediaPlayer
        if (p != null && !userSeeking) {
            val pos = p.currentPosition
            playbackSeekBar?.max = p.duration.coerceAtLeast(playbackSeekBar?.max ?: 1)
            playbackSeekBar?.progress = pos
            playbackCurrentTimeView?.text = formatTime(pos)
            playbackDurationView?.text = formatTime(p.duration.coerceAtLeast(0))
            playbackButtonView?.text = if (p.isPlaying) "Ⅱ" else "▶"
        }
        val idx = activeSegmentIndex()
        if (lastSegments.isNotEmpty() && idx != lastActiveSegmentIndex) {
            lastActiveSegmentIndex = idx
            updateActiveSegmentView(idx, forceScroll = true)
        }
        handler.postDelayed({ startHighlightTicker() }, 250)
    }

    private fun togglePlayback() {
        val id = lastRecordingId ?: return
        if (mediaPlayer == null) {
            val enc = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
            val url = "$serverUrl/api/recordings/$enc/download/audio"
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, Uri.parse(url), mapOf("X-Upload-Token" to uploadToken))
                setOnPreparedListener { p -> playbackSeekBar?.max = p.duration.coerceAtLeast(1); playbackDurationView?.text = formatTime(p.duration.coerceAtLeast(0)); p.start(); resumeAutoFollow(); statusToast("재생 시작") }
                setOnCompletionListener { playbackButtonView?.text = "▶"; statusToast("재생 완료") }
                prepareAsync()
            }
            statusToast("오디오 준비 중...")
        } else {
            mediaPlayer?.let { if (it.isPlaying) it.pause() else it.start() }
        }
    }

    private fun seekBy(deltaMs: Int) {
        val base = mediaPlayer?.currentPosition ?: playbackSeekBar?.progress ?: 0
        val max = mediaPlayer?.duration ?: playbackSeekBar?.max ?: 0
        seekToMs((base + deltaMs).coerceIn(0, max.coerceAtLeast(0)))
    }

    private fun stopPlayback() {
        try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun statusToast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun formatTime(ms: Int): String {
        val sec = ms / 1000
        return String.format(Locale.KOREA, "%02d:%02d", sec / 60, sec % 60)
    }


    private fun currentTabText(tab: String): String = when (tab) {
        "summary" -> lastSummaryText
        "analysis" -> lastAnalysisText
        else -> lastTranscriptText
    }

    private fun searchInCurrentDetail() {
        val input = EditText(this).apply { hint = "검색어" }
        AlertDialog.Builder(this)
            .setTitle("현재 회의록 검색")
            .setView(input)
            .setPositiveButton("검색") { _, _ ->
                val q = input.text.toString()
                val all = listOf(lastTranscriptText, lastSummaryText, lastAnalysisText).joinToString("\n")
                val idx = all.indexOf(q, ignoreCase = true)
                val msg = if (q.isBlank()) "검색어를 입력하세요." else if (idx >= 0) all.substring(maxOf(0, idx - 80), minOf(all.length, idx + q.length + 180)) else "찾지 못했습니다."
                AlertDialog.Builder(this).setTitle("검색 결과").setMessage(msg).setPositiveButton("확인", null).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun extractJsonString(json: String, key: String): String {
        return try { org.json.JSONObject(json).optString(key, "") } catch (e: Exception) { "" }
    }

    private fun extractJsonArrayStrings(json: String, key: String): List<String> {
        return try {
            val arr = org.json.JSONObject(json).optJSONArray(key) ?: return emptyList()
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) { emptyList() }
    }

    private fun unescapeJson(s: String): String {
        return s
    }

    private fun extractSegments(json: String): List<TranscriptSegment> {
        return try {
            val root = org.json.JSONObject(json)
            val arr = root.optJSONArray("segments") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                TranscriptSegment(
                    speaker = o.optString("speaker", "참석자 1"),
                    startMs = (o.optDouble("start", 0.0) * 1000).toInt(),
                    endMs = (o.optDouble("end", 0.0) * 1000).toInt(),
                    text = o.optString("text", "")
                )
            }.filter { it.text.isNotBlank() }
        } catch (e: Exception) { emptyList() }
    }

    data class HistoryItem(val id: String, val title: String, val recordedAt: String, val status: String, val snippet: String, val keywords: List<String>, val durationSec: Long = 0L, val isLocal: Boolean = false)

    data class TranscriptSegment(val speaker: String, val startMs: Int, val endMs: Int, val text: String)

    private fun httpGet(url: String, token: String, listOpenedSignal: Boolean = false): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("X-Upload-Token", token)
        if (listOpenedSignal) conn.setRequestProperty("X-VocaNote-List-Opened", "1")
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
        if (code !in 200..299) throw IOException("HTTP $code $body")
        return body
    }


    private fun showShareOptions(tab: String) {
        val labels = arrayOf("현재 탭 텍스트 공유", "음성녹음 파일 공유")
        AlertDialog.Builder(this)
            .setTitle("공유할 내용")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> shareText(currentTabText(tab))
                    1 -> shareAudioRecording()
                }
            }
            .show()
    }

    private fun shareText(text: String) {
        val safeText = text.ifBlank { "아직 공유할 텍스트가 없습니다." }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, lastDetailTitle.ifBlank { "VocaNote" })
            putExtra(Intent.EXTRA_TEXT, safeText)
        }
        startActivity(Intent.createChooser(intent, "텍스트 공유"))
    }

    private fun shareAudioRecording() {
        val id = lastRecordingId
        if (id.isNullOrBlank()) {
            statusToast("공유할 녹음 ID가 없습니다")
            return
        }
        statusToast("음성 파일 준비 중...")
        thread {
            try {
                val file = downloadAudioForShare(id)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/mp4"
                    putExtra(Intent.EXTRA_SUBJECT, lastDetailTitle.ifBlank { "VocaNote 음성녹음" })
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "음성녹음 파일 공유").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val receivers = packageManager.queryIntentActivities(intent, 0)
                receivers.forEach { resolveInfo ->
                    grantUriPermission(resolveInfo.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread { startActivity(chooser) }
            } catch (e: Exception) {
                android.util.Log.e("VocaNote", "share audio failed", e)
                runOnUiThread { statusToast("음성 파일을 준비하지 못했습니다") }
            }
        }
    }

    private fun downloadAudioForShare(recordingId: String): File {
        val enc = java.net.URLEncoder.encode(recordingId, "UTF-8").replace("+", "%20")
        val conn = (URL("$serverUrl/api/recordings/$enc/download/audio").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("X-Upload-Token", uploadToken)
            connectTimeout = 15000
            readTimeout = 120000
        }
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("HTTP $code")
        val dir = File(cacheDir, "shared_audio").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        val base = lastDetailTitle.ifBlank { "vocanote_$recordingId" }
            .replace(Regex("[^가-힣A-Za-z0-9._-]+"), "_")
            .trim('_')
            .take(80)
            .ifBlank { "vocanote_audio" }
        val outFile = File(dir, "$base.m4a")
        conn.inputStream.use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return outFile
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(levelReceiver, IntentFilter(RecordingService.ACTION_LEVEL), RECEIVER_NOT_EXPORTED)
        val recordingStateFilter = IntentFilter(RecordingService.ACTION_RECORDING_STOPPED).apply {
            addAction(RecordingService.ACTION_RECORDING_STARTED)
            addAction(RecordingService.ACTION_RECORDING_START_FAILED)
            addAction(RecordingService.ACTION_FINALIZATION_COMPLETED)
        }
        registerReceiver(recordingStateReceiver, recordingStateFilter, RECEIVER_NOT_EXPORTED)
        reconcileRecordingUiFromDurableState()
    }

    private fun openSafeEditor() {
        val rid = lastRecordingId ?: return
        stopPlayback()
        thread {
            try {
                EditLeaseCoordinator.acquire(this, rid)
                runOnUiThread { showSafeEditorScreen(rid) }
            } catch (e: Exception) {
                runOnUiThread {
                    statusToast("편집 lease를 획득하지 못했습니다")
                    showEditorRecoveryRequired(rid)
                }
            }
        }
    }

    private fun showSafeEditorScreen(recordingId: String) {
        editorRecordingId = recordingId
        currentScreen = Screen.RECORDING_EDITOR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = appBackground()
        }
        root.addView(TextView(this).apply {
            text = "편집 세션 보호 중"
            textSize = 22f
            setTextColor(textMainColor())
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "안전 lease가 활성화되었습니다.\nTrim/Split 실행은 아직 비활성화되어 있습니다."
            textSize = 15f
            setTextColor(textMutedColor())
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(24))
        })
        root.addView(TextView(this).apply {
            text = "편집 취소 및 돌아가기"
            textSize = 16f
            setTextColor(accentColor())
            gravity = Gravity.CENTER
            background = surfaceBg(secondaryColor(), 16, borderColor())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { closeSafeEditor() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        handler.removeCallbacks(editorHeartbeat)
        handler.postDelayed(editorHeartbeat, 20_000L)
    }

    private fun showEditorRecoveryRequired(recordingId: String) {
        handler.removeCallbacks(editorHeartbeat)
        editorRecordingId = recordingId
        currentScreen = Screen.RECORDING_EDITOR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = appBackground()
        }
        root.addView(TextView(this).apply {
            text = "편집 복구 필요"
            textSize = 22f
            setTextColor(textMainColor())
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "lease 상태를 확인할 때까지 자동 확정이나 편집을 실행하지 않습니다."
            textSize = 15f
            setTextColor(textMutedColor())
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(24))
        })
        root.addView(TextView(this).apply {
            text = "기록으로 돌아가기"
            textSize = 16f
            setTextColor(accentColor())
            gravity = Gravity.CENTER
            background = surfaceBg(secondaryColor(), 16, borderColor())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { editorRecordingId = null; showDetailScreen(activeTab) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun closeSafeEditor() {
        val rid = editorRecordingId ?: run { showDetailScreen(activeTab); return }
        handler.removeCallbacks(editorHeartbeat)
        thread {
            val released = EditLeaseCoordinator.release(this, rid)
            runOnUiThread {
                if (released) {
                    editorRecordingId = null
                    showDetailScreen(activeTab)
                } else showEditorRecoveryRequired(rid)
            }
        }
    }

    override fun onPause() {
        try { unregisterReceiver(levelReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(recordingStateReceiver) } catch (_: Exception) {}
        super.onPause()
    }

    override fun onDestroy() {
        stopPlayback()
        stopRecordingStatusTicker()
        handler.removeCallbacks(editorHeartbeat)
        editorRecordingId?.let { LocalEditStateStore.requireRecovery(this, it) }
        super.onDestroy()
    }

    override fun onBackPressed() {
        when (currentScreen) {
            Screen.RECORDING_EDITOR -> closeSafeEditor()
            Screen.RECORDING_DETAIL -> openHistory()
            Screen.RECORDING_LIST -> { showHome(resetTransient = true); restoreRecordingState() }
            Screen.HOME -> super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecordingIfReady()
        } else {
            statusText.text = "마이크 권한이 필요합니다."
        }
    }
}

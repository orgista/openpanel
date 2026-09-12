package com.orgista.openpanel

import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.adapter.exoplayer.audio.ExoPlayerEngineProvider
import org.readium.adapter.exoplayer.audio.ExoPlayerPreferences
import org.readium.adapter.exoplayer.audio.ExoPlayerSettings
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.navigator.media.audio.AudioNavigator
import org.readium.navigator.media.audio.AudioNavigatorFactory
import org.readium.navigator.media.common.MediaNavigator
import org.readium.r2.navigator.Navigator
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.allAreHtml
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/** Minimal, remote-friendly Readium reader for OpenPanel's private offline library. */
@OptIn(ExperimentalReadiumApi::class)
class LibraryReaderActivity : AppCompatActivity() {
    private lateinit var repository: LibraryRepository
    private lateinit var publicationId: String
    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var content: FrameLayout
    private lateinit var loading: ProgressBar
    private var publication: Publication? = null
    private var navigator: Navigator? = null
    private var audioNavigator: AudioNavigator<ExoPlayerSettings, ExoPlayerPreferences>? = null
    private var progressionJob: Job? = null
    // Latest reading position, coalesced across rapid currentLocator emissions.
    // A debounced job persists it off the main thread; onStop flushes the tail.
    private var pendingLocatorJson: String? = null
    private var pendingProgression: Double = 0.0
    private var hasPendingSave = false
    private var saveJob: Job? = null
    private var epubPreferences = EpubPreferences(fontSize = 1.0, theme = Theme.LIGHT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        publicationId = intent.getStringExtra(EXTRA_PUBLICATION_ID).orEmpty()
        repository = LibraryRepository(this)
        buildShell()
        if (publicationId.isEmpty()) {
            showFailure("This publication could not be found.")
            return
        }
        lifecycleScope.launch {
            try {
                val opened = withContext(Dispatchers.IO) { openPublication() }
                publication = opened.publication
                titleView.text = opened.metadata.optString("title", opened.publication.metadata.title ?: "Reader")
                loading.visibility = View.GONE
                showPublication(opened.publication, opened.initialLocator)
            } catch (error: Exception) {
                showFailure(error.message ?: "This publication could not be opened.")
            }
        }
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.rgb(28, 28, 28))
        }
        toolbar.addView(controlButton("Back") { finish() })
        titleView = TextView(this).apply {
            text = "Opening publication…"
            setTextColor(Color.WHITE)
            textSize = 18f
            maxLines = 1
            setPadding(dp(12), 0, dp(12), 0)
        }
        toolbar.addView(titleView, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content = FrameLayout(this).apply { id = View.generateViewId() }
        loading = ProgressBar(this).apply { isIndeterminate = true }
        content.addView(loading, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private suspend fun openPublication(): OpenedPublication {
        val metadata = repository.publication(publicationId)
            ?: throw ReaderException("This publication is no longer stored on this device.")
        val file = repository.publicationFile(metadata)
            ?: throw ReaderException("The publication file is missing.")
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(contentResolver, httpClient)
        val opener = PublicationOpener(
            DefaultPublicationParser(
                this,
                assetRetriever = assetRetriever,
                httpClient = httpClient,
                pdfFactory = PdfiumDocumentFactory(this)
            )
        )
        val asset = assetRetriever.retrieve(file).getOrElse {
            throw ReaderException(it.message)
        }
        val opened = opener.open(asset, allowUserInteraction = true).getOrElse {
            asset.close()
            throw ReaderException(it.message)
        }
        if (opened.isRestricted) {
            opened.close()
            throw ReaderException("This DRM-protected publication requires the institution's licensed Readium LCP connector.")
        }
        val locator = metadata.optString("locator", "")
            .takeIf { it.isNotEmpty() }
            ?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        return OpenedPublication(opened, metadata, locator)
    }

    private fun showPublication(opened: Publication, initialLocator: Locator?) {
        when {
            opened.conformsTo(Publication.Profile.AUDIOBOOK) -> showAudio(opened, initialLocator)
            opened.conformsTo(Publication.Profile.EPUB) || opened.readingOrder.allAreHtml -> showEpub(opened, initialLocator)
            opened.conformsTo(Publication.Profile.PDF) -> showPdf(opened, initialLocator)
            else -> showFailure("This publication format is not supported by the reader.")
        }
    }

    private fun showEpub(opened: Publication, initialLocator: Locator?) {
        addEpubControls()
        val factory = EpubNavigatorFactory(opened)
        supportFragmentManager.fragmentFactory = factory.createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = epubPreferences
        )
        supportFragmentManager.commitNow {
            replace(content.id, EpubNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
        }
        val epub = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as EpubNavigatorFragment
        navigator = epub
        epub.addInputListener(DirectionalNavigationAdapter(epub))
        observeProgress(epub)
    }

    private fun showPdf(opened: Publication, initialLocator: Locator?) {
        val factory = PdfNavigatorFactory(opened, PdfiumEngineProvider())
        supportFragmentManager.fragmentFactory = factory.createFragmentFactory(initialLocator)
        supportFragmentManager.commitNow {
            replace(content.id, PdfNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
        }
        val pdf = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as PdfNavigatorFragment<*, *>
        navigator = pdf
        pdf.addInputListener(DirectionalNavigationAdapter(pdf))
        observeProgress(pdf)
    }

    private fun showAudio(opened: Publication, initialLocator: Locator?) {
        volumeControlStream = AudioManager.STREAM_MUSIC
        val factory = AudioNavigatorFactory(opened, ExoPlayerEngineProvider(application))
            ?: return showFailure("This audiobook has no playable audio tracks.")
        lifecycleScope.launch {
            val audio = factory.createNavigator(initialLocator).getOrElse {
                showFailure(it.message)
                return@launch
            }
            audioNavigator = audio
            navigator = audio
            showAudioControls(audio)
            observeProgress(audio)
            audio.play()
        }
    }

    private fun addEpubControls() {
        val decrease = controlButton("A−") {
            val next = (epubPreferences.fontSize ?: 1.0).minus(0.1).coerceAtLeast(0.7)
            epubPreferences = epubPreferences.copy(fontSize = next)
            (navigator as? EpubNavigatorFragment)?.submitPreferences(epubPreferences)
        }
        val increase = controlButton("A+") {
            val next = (epubPreferences.fontSize ?: 1.0).plus(0.1).coerceAtMost(2.0)
            epubPreferences = epubPreferences.copy(fontSize = next)
            (navigator as? EpubNavigatorFragment)?.submitPreferences(epubPreferences)
        }
        val theme = controlButton("Theme") {
            val next = if (epubPreferences.theme == Theme.DARK) Theme.LIGHT else Theme.DARK
            epubPreferences = epubPreferences.copy(theme = next)
            (navigator as? EpubNavigatorFragment)?.submitPreferences(epubPreferences)
        }
        toolbar.addView(decrease)
        toolbar.addView(increase)
        toolbar.addView(theme)
    }

    private fun showAudioControls(audio: AudioNavigator<ExoPlayerSettings, ExoPlayerPreferences>) {
        content.removeAllViews()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        val type = TextView(this).apply {
            text = "AUDIOBOOK"
            setTextColor(Color.rgb(145, 165, 255))
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val name = TextView(this).apply {
            // A standalone MP3/AAC often has no embedded Readium title. The
            // toolbar already contains the repository/import title, so keep
            // the player label useful for locally imported institutional media.
            text = publication?.metadata?.title ?: titleView.text
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(30))
        }
        val timeline = SeekBar(this).apply { max = 1 }
        val position = TextView(this).apply {
            text = "00:00 / 00:00"
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
        }
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val back = controlButton("−30 sec") { audio.skip((-30).seconds) }
        val play = controlButton("Pause") {
            if (audio.playback.value.playWhenReady) audio.pause() else audio.play()
        }
        val forward = controlButton("+30 sec") { audio.skip(30.seconds) }
        controls.addView(back)
        controls.addView(play)
        controls.addView(forward)
        panel.addView(type)
        panel.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(timeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(position, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(controls)
        content.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        var userSeeking = false
        timeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) position.text = formatTime(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val index = audio.playback.value.index
                audio.skipTo(index, seekBar.progress.seconds)
                userSeeking = false
            }
        })
        audio.playback.onEach { playback ->
            play.text = if (playback.playWhenReady) "Pause" else "Play"
            val duration = audio.readingOrder.items.getOrNull(playback.index)?.duration?.inWholeSeconds ?: 0L
            timeline.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            if (!userSeeking) timeline.progress = playback.offset.inWholeSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            position.text = "${formatTime(playback.offset.inWholeSeconds)} / ${formatTime(duration)}"
            if (playback.state is MediaNavigator.State.Failure) {
                Toast.makeText(this, "Audio playback failed.", Toast.LENGTH_LONG).show()
            }
        }.launchIn(lifecycleScope)
    }

    private fun observeProgress(current: Navigator) {
        progressionJob?.cancel()
        progressionJob = current.currentLocator.onEach { locator ->
            val progression = locator.locations.totalProgression
                ?: locator.locations.progression
                ?: 0.0
            // Record the newest position and coalesce writes. The full-library
            // JSON rewrite is expensive, so persist at most once per debounce
            // window (last-position-wins) instead of on every emission.
            pendingLocatorJson = locator.toJSON().toString()
            pendingProgression = progression
            hasPendingSave = true
            scheduleProgressSave()
        }.launchIn(lifecycleScope)
    }

    private fun scheduleProgressSave() {
        // Debounce: restart the timer on each emission so the (expensive) write
        // happens once the position settles, not on every locator tick. The tail
        // is also flushed in onStop so nothing is lost on close/background.
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            flushProgress()
        }
    }

    private suspend fun flushProgress() {
        if (!hasPendingSave) return
        val json = pendingLocatorJson ?: return
        val progression = pendingProgression
        hasPendingSave = false
        // The repository re-parses and rewrites the entire publications JSON to
        // SharedPreferences; keep that off the main thread.
        withContext(Dispatchers.IO) {
            repository.saveProgress(publicationId, json, progression)
        }
    }

    private fun showFailure(message: String) {
        loading.visibility = View.GONE
        content.removeAllViews()
        val label = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        content.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun controlButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = dp(56)
            minHeight = dp(48)
            setOnClickListener { action() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun formatTime(seconds: Long): String = DateUtils.formatElapsedTime(seconds.coerceAtLeast(0))

    override fun onStop() {
        super.onStop()
        // Guarantee the last observed position is persisted even if the debounce
        // window had not elapsed. Blocks briefly on IO so the write completes
        // before the activity is torn down (last-position-wins).
        saveJob?.cancel()
        if (hasPendingSave) {
            val json = pendingLocatorJson
            val progression = pendingProgression
            hasPendingSave = false
            if (json != null) {
                runBlocking(Dispatchers.IO) {
                    repository.saveProgress(publicationId, json, progression)
                }
            }
        }
    }

    override fun onDestroy() {
        progressionJob?.cancel()
        saveJob?.cancel()
        audioNavigator?.close()
        publication?.close()
        super.onDestroy()
    }

    private data class OpenedPublication(
        val publication: Publication,
        val metadata: JSONObject,
        val initialLocator: Locator?,
    )

    private class ReaderException(message: String) : Exception(message)

    companion object {
        const val EXTRA_PUBLICATION_ID = "publicationId"
        private const val NAVIGATOR_TAG = "openpanel-reader-navigator"
        private const val PROGRESS_SAVE_DEBOUNCE_MS = 1_500L
    }
}

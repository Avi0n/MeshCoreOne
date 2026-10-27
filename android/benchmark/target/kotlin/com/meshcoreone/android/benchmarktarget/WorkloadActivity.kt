// AndroidOnly: WP-504 Isolated release-optimized component workloads, never a radio simulation or shipping route.
package com.meshcoreone.android.benchmarktarget

import android.content.Context
import android.graphics.RectF
import android.os.Bundle
import android.os.StrictMode
import android.system.Os
import android.system.OsConstants
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.meshcoreone.android.app.MeshCoreApplication
import com.meshcoreone.android.benchmark.Workload
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.timeline.*
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineRoute
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

class WorkloadActivity : ComponentActivity() {
    private val logger = Logger.getLogger("com.meshcoreone.android.WP504")
    private var mapView: MapView? = null
    private val screen by lazy { requireNotNull(intent.getStringExtra("screen")) }
    private val readyDescription by lazy { "WP504_READY:$screen:${Workload.VERSION}:${Workload.digest()}" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        require(packageName == "com.meshcoreone.android.benchmark") { "Fixture must never run in the product package" }
        if (intent.getBooleanExtra("strictMode", false)) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().penaltyDeath().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().penaltyDeath().build())
        }
        setContent { MaterialTheme { Text("WP504_LOADING") } }
        lifecycleScope.launch {
            try {
                val container = (application as MeshCoreApplication).container.await()
                val storage = withContext(Dispatchers.IO) { WorkloadStorage.get(this@WorkloadActivity, container.uiScope) }
                when (screen) {
                    "prepare" -> {
                        withContext(Dispatchers.IO) { storage.prepare() }
                        setContent { MaterialTheme { Text(Workload.VERSION, Modifier.semantics { contentDescription = readyDescription }) } }
                    }
                    "timeline" -> {
                        withContext(Dispatchers.IO) { storage.verifyPrepared() }
                        showTimeline(storage)
                    }
                    "map" -> showMap(savedInstanceState)
                    else -> error("Unknown WP-504 workload: $screen")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.log(Level.SEVERE, "WP-504 workload failed", failure)
                setContent { MaterialTheme { Text("WP504_FAILED:${failure.javaClass.simpleName}") } }
            }
        }
    }

    private fun showTimeline(storage: WorkloadStorage) {
        val contact = ContactDTO(
            id = Workload.contactId, radioId = RadioId(Workload.radioId), publicKey = Bytes(ByteArray(32) { 0x50 }),
            name = "Offline workload", lastHeardTimestamp = null,
        )
        val key = EntityKey(contact.radioId, contact.id)
        val drafts = mutableMapOf<ChatConversationID, String>()
        val dependencies = object : ChatTimelineDependencies {
            override val data = object : TimelineDataSource {
                override suspend fun fetchMessages(conversation: TimelineConversation, limit: Int, offset: Int) =
                    storage.store.fetchMessages(key, limit.toLong(), offset.toLong())
                override suspend fun fetchMessageWindow(conversation: TimelineConversation, anchorSortDate: Instant?, floorLimit: Int) =
                    storage.store.fetchMessageWindow(key, anchorSortDate, floorLimit.toLong())
                override suspend fun fetchMessage(messageId: UUID) =
                    storage.store.fetchMessage(EntityKey(contact.radioId, messageId))
                override suspend fun unreadAnchorMessageId(conversation: TimelineConversation) =
                    storage.store.newestUnreadIncomingMessage(key)?.id
                override suspend fun clearUnread(conversation: TimelineConversation) {
                    check(storage.store.newestUnreadIncomingMessage(key) == null) { "Unexpected unread workload row" }
                }
                override suspend fun markFailedSendsSeen(conversation: TimelineConversation) =
                    storage.store.markFailedSendsSeen(key)
            }
            override val retryService = TimelineRetryService { _, _ -> error("Offline workload cannot send to a radio") }
            override val draftStore = object : TimelineDraftStore {
                override fun get(conversationId: ChatConversationID) = drafts[conversationId]
                override fun set(conversationId: ChatConversationID, text: String?) {
                    if (text == null) drafts.remove(conversationId) else drafts[conversationId] = text
                }
            }
            override val events = emptyFlow<TimelineEvent>()
            override val diagnostics = TimelineDiagnostics { operation, failure ->
                logger.log(Level.SEVERE, operation, failure)
            }
        }
        val model = ChatViewModel(TimelineConversation.Direct(contact), dependencies, lifecycleScope, ZoneOffset.UTC)
        setContent {
            MaterialTheme {
                val state by model.state.collectAsState()
                val ready = state.hasLoadedOnce && state.loadError == null && state.messages.isNotEmpty()
                ReportDrawnWhen { ready }
                Column {
                    Text(Workload.VERSION, Modifier.semantics {
                        contentDescription = if (ready) readyDescription else "WP504_LOADING"
                    })
                    Text("Loaded ${state.messages.size}", Modifier.semantics {
                        contentDescription = if (state.messages.size > 50) "WP504_PAGED:${state.messages.size}" else "WP504_INITIAL"
                    })
                    ChatTimelineRoute(model, Modifier.weight(1f).semantics { contentDescription = "WP504_TIMELINE" })
                }
            }
        }
    }

    private fun showMap(savedInstanceState: Bundle?) {
        if (intent.getBooleanExtra("requireNative16KiB", false)) {
            check(Os.sysconf(OsConstants._SC_PAGESIZE) == 16_384L) {
                "WP-504 target process must report native 16 KiB pages; kernel size alone is insufficient"
            }
        }
        MapLibre.getInstance(applicationContext)
        val status = TextView(this).apply { text = "WP504_MAP_LOADING" }
        val view = MapView(this).apply { contentDescription = "WP504_MAP" }
        mapView = view
        val frame = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        }
        setContentView(frame)
        var styleLoaded = false
        var reportedDrawn = false
        var readyMap: MapLibreMap? = null
        view.addOnDidFailLoadingMapListener { reason ->
            logger.severe("WP-504 native map failed: $reason")
            status.text = "WP504_FAILED:map"
            status.contentDescription = "WP504_FAILED:map"
        }
        view.addOnDidFinishRenderingFrameListener { fully, _, _ ->
            if (fully && styleLoaded && !reportedDrawn) {
                val map = checkNotNull(readyMap)
                if (map.queryRenderedFeatures(
                    RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), "wp504-clusters", "wp504-markers",
                ).isEmpty()) return@addOnDidFinishRenderingFrameListener
                status.text = Workload.VERSION
                if (status.contentDescription != "WP504_MAP_MOVED") status.contentDescription = readyDescription
                reportedDrawn = true
                reportFullyDrawn()
            }
        }
        view.onCreate(savedInstanceState)
        view.getMapAsync { map ->
            readyMap = map
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false
            map.cameraPosition = CameraPosition.Builder().target(LatLng(37.024, -121.961)).zoom(11.0).build()
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == 1) status.contentDescription = "WP504_MAP_MOVED"
            }
            // Self-authored local style has no provider, glyph, sprite, tile or network input.
            map.setStyle(Style.Builder().fromJson(LOCAL_STYLE)) { style ->
                val features = Workload.markers().map {
                    Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude), null, it.id.toString())
                }
                check(features.size == Workload.MARKER_COUNT)
                style.addSource(GeoJsonSource(
                    "wp504-points", FeatureCollection.fromFeatures(features),
                    GeoJsonOptions().withCluster(true).withClusterRadius(44).withClusterMaxZoom(13),
                ))
                style.addLayer(CircleLayer("wp504-clusters", "wp504-points")
                    .withFilter(Expression.has("point_count")).withProperties(circleColor("#2255A4"), circleRadius(20f)))
                style.addLayer(CircleLayer("wp504-markers", "wp504-points")
                    .withFilter(Expression.not(Expression.has("point_count")))
                    .withProperties(circleColor("#1B8A5A"), circleRadius(8f)))
                styleLoaded = true
            }
        }
        // onStart/onResume may already have run while the process container was being awaited.
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) view.onStart()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) view.onResume()
    }

    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView?.onSaveInstanceState(outState) }
    override fun onDestroy() { mapView?.onDestroy(); mapView = null; super.onDestroy() }

    companion object {
        private const val LOCAL_STYLE = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#E5ECF1"}}]}"""
    }
}

private class WorkloadStorage private constructor(context: Context, scope: CoroutineScope) {
    private val database = MeshCoreDatabase.open(context, "wp504-${Workload.VERSION}.db")
    val store = RoomPersistenceStore(database, scope)
    private val key = EntityKey(RadioId(Workload.radioId), Workload.contactId)

    suspend fun prepare() {
        database.withTransaction {
            Workload.messages().forEach { row ->
                val instant = Instant.ofEpochSecond(row.epochSeconds)
                database.messages().upsert(MessageDTO(
                    id = row.id, radioId = key.radioId, contactID = key.id, text = row.text,
                    timestamp = row.epochSeconds.toUInt(), createdAt = instant, sortDate = instant,
                    direction = if (row.outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                    status = if (row.outgoing) MessageStatus.DELIVERED else MessageStatus.SENT, isRead = true,
                ).toEntity())
            }
        }
        verifyInputBytes()
    }

    suspend fun verifyPrepared() {
        check(database.messages().countForContact(key.radioId.value, key.id) == Workload.MESSAGE_COUNT.toLong()) {
            "Run the prepare workload before measuring"
        }
    }

    suspend fun verifyInputBytes() {
        val actual = store.fetchMessages(key, Workload.MESSAGE_COUNT.toLong() + 1)
        val expected = Workload.messages()
        check(actual.size == expected.size) { "Run the prepare workload before measuring" }
        check(actual.zip(expected).all { (row, input) ->
            row.id == input.id && row.text == input.text && row.timestamp.toLong() == input.epochSeconds && row.isRead
        }) { "WP-504 Room workload differs from its versioned input" }
    }

    companion object {
        // Process-owned fixture store follows the existing container's scope, never a connection lifetime.
        private var instance: WorkloadStorage? = null
        @Synchronized fun get(context: Context, scope: CoroutineScope): WorkloadStorage =
            instance ?: WorkloadStorage(context.applicationContext, scope).also { instance = it }
    }
}

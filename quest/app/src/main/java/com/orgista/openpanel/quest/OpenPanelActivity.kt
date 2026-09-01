/*
 * Portions copyright (c) Meta Platforms, Inc. and affiliates.
 * Modifications copyright (c) 2026 Orgista.
 * SPDX-License-Identifier: MIT
 */

package com.orgista.openpanel.quest

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.hardware.input.InputManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.net.toUri
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.isdk.IsdkPanelResize
import com.meta.spatial.isdk.ResizeMode
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.runtime.LayerFilters
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.PanelShapeLayerBlendType
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.PanelShapeConfig
import com.meta.spatial.toolkit.Equirect360ShapeOptions
import com.meta.spatial.toolkit.MediaPanelShapeOptions
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.VRFeature
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OpenPanelActivity : AppSystemActivity() {
  private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var apps by mutableStateOf<List<LaunchableApp>>(emptyList())
  private var status by mutableStateOf("Finding launchable apps…")
  private var showBoot by mutableStateOf(true)
  private var passthroughEnabled by mutableStateOf(false)
  private var metaAiAvailable by mutableStateOf(false)
  private var castAvailable by mutableStateOf(false)
  private var domeEntity: Entity? = null
  private var videoDomeEntity: Entity? = null
  private var videoPlayer: androidx.media3.exoplayer.ExoPlayer? = null
  private var nearEnvEntity: Entity? = null
  private var waterNearEntity: Entity? = null
  private var waterFarEntity: Entity? = null
  private var riverNearMaterial: SceneMaterial? = null
  private var riverFarMaterial: SceneMaterial? = null
  private var libraryEntity: Entity? = null
  private var barEntity: Entity? = null
  private var ambience: MediaPlayer? = null
  private var isForeground = false
  private var libraryResized by mutableStateOf(false)
  /** Last scale actually persisted, so the 1.4 Hz watcher only writes on change. */
  private var lastWrittenLibraryScale: Float? = null

  private val prefs by lazy { getSharedPreferences("openpanel", MODE_PRIVATE) }

  private val restrictionsReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
          refreshApps()
        }
      }

  private lateinit var mrukFeature: MRUKFeature

  override fun registerFeatures(): List<SpatialFeature> {
    // ISDK (direct touch, grab, in-place panel resize) is auto-registered by
    // VRFeature since 0.13.2. MRUKFeature supplies scene understanding for
    // passthrough mode; physics stays off — it is only needed for MRUK's
    // procedural mesh colliders, which we do not spawn.
    mrukFeature = MRUKFeature(this, systemManager)
    return listOf(VRFeature(this), ComposeFeature(), mrukFeature)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.Theme_Transparent)
    super.onCreate(savedInstanceState)
    passthroughEnabled =
        if (prefs.contains(PREF_PASSTHROUGH)) {
          prefs.getBoolean(PREF_PASSTHROUGH, false)
        } else {
          launcherRestrictions().getString("default_environment") == "passthrough"
        }
    registerReceiver(
        restrictionsReceiver,
        IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
        Context.RECEIVER_NOT_EXPORTED,
    )
    // No refreshApps() here: onResume always follows onCreate and refreshes,
    // so scanning from both ran the two-query package sweep twice per cold
    // start. Discovery is async anyway and the boot logo covers the gap.
    maybeLoadSceneUnderstanding()
  }

  /**
   * Scene understanding (MRUK). USE_SCENE is a runtime permission; a launcher
   * should not nag, so it is requested exactly once and scene data loads on
   * any later start where the user granted it (grant persists).
   */
  private fun maybeLoadSceneUnderstanding() {
    if (checkSelfPermission(PERMISSION_USE_SCENE) == PackageManager.PERMISSION_GRANTED) {
      loadSceneUnderstanding()
    } else if (!prefs.getBoolean(PREF_SCENE_PERMISSION_ASKED, false)) {
      prefs.edit().putBoolean(PREF_SCENE_PERMISSION_ASKED, true).apply()
      requestPermissions(arrayOf(PERMISSION_USE_SCENE), REQUEST_CODE_USE_SCENE)
    }
  }

  override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<out String>,
      grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (
        requestCode == REQUEST_CODE_USE_SCENE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
    ) {
      loadSceneUnderstanding()
    }
  }

  private fun loadSceneUnderstanding() {
    runCatching {
          mrukFeature.loadSceneFromDevice().whenComplete { result, error ->
            if (error != null) {
              Log.w(TAG, "MRUK scene load failed", error)
            } else {
              Log.i(TAG, "MRUK scene load: $result, rooms=${mrukFeature.rooms.size}")
            }
          }
        }
        .onFailure { Log.w(TAG, "MRUK scene load did not start", it) }
  }

  override fun onResume() {
    super.onResume()
    isForeground = true
    runCatching { videoPlayer?.playWhenReady = !passthroughEnabled }
    refreshApps()
    updateAmbience()
  }

  override fun onPause() {
    isForeground = false
    runCatching { videoPlayer?.playWhenReady = false }
    updateAmbience()
    savePanelPoses()
    super.onPause()
  }

  override fun onDestroy() {
    runCatching { unregisterReceiver(restrictionsReceiver) }
    runCatching { ambience?.release() }
    ambience = null
    runCatching { videoPlayer?.release() }
    videoPlayer = null
    activityScope.cancel()
    super.onDestroy()
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.setLightingEnvironment(
        ambientColor = Vector3(0.22f, 0.22f, 0.24f),
        sunColor = Vector3(1.1f, 1.15f, 1.2f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.3f,
    )
    scene.setViewOrigin(0.0f, 0.0f, 0.2f, 180.0f)

    // Immersive world: ONE 8K equirect skybox — a Cycles 360 render of the
    // Fir & Pine Forest Blender scene (../../pine_forest, CC0), standing at
    // the river bank from Anthony's reference shot. The old fallback domes,
    // horizon band GLB, and foreground GLBs are gone: the render bakes all of
    // it, which also removes the band/foreground seams and the posts that cut
    // through the panel. Two low-alpha shimmer planes animate the river.
    if (videoDomeAvailable()) {
      videoDomeEntity =
          Entity.createPanelEntity(
              R.id.video_dome_panel,
              Transform(Pose(Vector3(0f, 0f, 0f))),
              Visible(!passthroughEnabled),
          )
      Log.i(TAG, "video dome active: " + videoDomeFile().absolutePath)
    } else {
      domeEntity =
          Entity.create(
              listOf(
                  Mesh("mesh://skybox".toUri(), hittable = MeshCollision.LineTest),
                  Material().apply {
                    baseTextureAndroidResourceId = R.drawable.skydome_pine_forest_v2
                    unlit = true
                  },
                  Transform(Pose(Vector3(0f, 0f, 0f))),
                  Visible(!passthroughEnabled),
              ),
      )
    }
    // Native 3D near field (terrain+rocks reprojection): QUARANTINED —
    // 0.3.47/vc59 broke on wear (rolled back before root cause was pinned).
    // Re-enable only after: cropped embedded texture, unlit material, and an
    // immediate wear test. See AGENTS.md 09:20 rollback entry.
    if (ENABLE_NATIVE_NEAR_ENV) {
      nearEnvEntity =
          Entity.create(
              listOf(
                  Mesh("near_environment.glb".toUri(), hittable = MeshCollision.LineTest),
                  Transform(Pose(Vector3(0f, 0f, 0f))),
                  Visible(!passthroughEnabled),
              ),
          )
    }
    if (ENABLE_ANIMATED_WATER_OVERLAY) {
      // The animated glints live ON the river's actual water surface — the
      // `river_water` ribbon exported from the same Blender scene in
      // camera-space (scripts/export-river-water-glb.py) — not on scene-wide
      // overlay planes. Two stacked copies with different tiling drift in
      // opposite directions: the tutorial's in-place undulation, confined to
      // the river.
      waterNearEntity = createRiverGlintLayer("river_water_surface_a.glb", yLift = 0.00f)
      waterFarEntity = createRiverGlintLayer("river_water_surface_b.glb", yLift = 0.035f)
      resolveRiverMaterial(waterNearEntity) { riverNearMaterial = it }
      resolveRiverMaterial(waterFarEntity) { riverFarMaterial = it }
      startWaterMotion()
    }
    scene.enablePassthrough(passthroughEnabled)

    // Navigator parity: library window + compact quick-settings bar. Both are
    // grabbable so the user can set up their space; poses persist across runs.
    libraryEntity =
        createPanel(
            R.id.library_panel,
            savedPose(PREF_POSE_LIBRARY) ?: Pose(Vector3(0f, 1.40f, -1.58f), yaw180()),
        )
    barEntity =
        createPanel(
            R.id.system_bar_panel,
            savedPose(PREF_POSE_BAR) ?: Pose(Vector3(0f, 0.72f, -1.50f), yaw180()),
        )
    // SDK-native in-place resize (Spatial SDK 0.13.2): pinch the library's
    // corners to resize, same bounds the old hand-rolled corner grip used.
    libraryEntity?.setComponent(
        IsdkPanelResize(
            enabled = true,
            resizeMode = ResizeMode.Simple,
            minDimensions =
                Vector2(
                    PanelLayout.LIBRARY_WIDTH_METERS * 0.70f,
                    PanelLayout.LIBRARY_HEIGHT_METERS * 0.70f,
                ),
            maxDimensions =
                Vector2(
                    PanelLayout.LIBRARY_WIDTH_METERS * 1.35f,
                    PanelLayout.LIBRARY_HEIGHT_METERS * 1.35f,
                ),
            preserveAspectRatio = true,
        ),
    )
    applyLibraryScale(libraryScale())
    startLibraryScaleWatcher()
    activityScope.launch {
      delay(1_250)
      showBoot = false
    }
  }

  /** Animated 360 environment clip, pushed to app-external files while iterating. */
  private fun videoDomeFile(): java.io.File =
      java.io.File(getExternalFilesDir(null), VIDEO_DOME_FILE)

  private fun videoDomeAvailable(): Boolean = ENABLE_VIDEO_DOME && videoDomeFile().exists()

  /**
   * Replaces the SDK's equirect dome mesh (visible meridian seams that
   * converge at the nadir — confirmed dome-fixed via a rotated-video test)
   * with SceneMesh.skybox(), the mesh Meta's own seam-free environments
   * use. Forces the scene-texture path: sceneMeshCreator is ignored by the
   * compositor-layer path.
   */
  private class SeamlessDomeShape(private val radius: Float) : MediaPanelShapeOptions {
    override fun applyTo(config: PanelConfigOptions) {
      Equirect360ShapeOptions(radius).applyTo(config)
      // Equirect360ShapeOptions turns on includeGlass: a translucent glass
      // MESH sphere drawn in-scene over the video layer. Its meridian edges
      // are the dome-fixed seams (V at the nadir). The video itself stays on
      // the compositor layer — the scene-texture path can't feed a video
      // surface at all (renders black, tested 0.3.52 first builds).
      config.includeGlass = false
    }
  }

  override fun registerPanels(): List<PanelRegistration> =
      listOf(
          // 360 VIDEO DOME (OpenXR hybrid: prerendered animated environment
          // behind real-time panels). Mono equirect, composited behind
          // everything (zIndex -1), fed by plain MediaPlayer (no new deps).
          VideoSurfacePanelRegistration(
              R.id.video_dome_panel,
              surfaceConsumer = { _, surface ->
                videoPlayer =
                    androidx.media3.exoplayer.ExoPlayer.Builder(this).build().apply {
                      repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                      setVideoSurface(surface)
                      volume = 0f
                      setMediaItem(
                          androidx.media3.common.MediaItem.fromUri(
                              android.net.Uri.fromFile(videoDomeFile()),
                          ),
                      )
                      prepare()
                      playWhenReady = isForeground && !passthroughEnabled
                    }
              },
              settingsCreator = {
                MediaPanelSettings(
                    shape =
                        if (ENABLE_SEAMLESS_DOME) SeamlessDomeShape(radius = 300f)
                        else Equirect360ShapeOptions(radius = 300f),
                    display = PixelDisplayOptions(width = 100, height = 100),
                    rendering = MediaPanelRenderOptions(stereoMode = StereoMode.None, zIndex = -1),
                )
              },
          ),
          composePanel(
              R.id.library_panel,
              width = PanelLayout.LIBRARY_WIDTH_METERS,
              height = PanelLayout.LIBRARY_HEIGHT_METERS,
              resolutionScale = PanelLayout.MAIN_RESOLUTION_SCALE,
          ) {
            OpenPanelLibrary(
                apps = apps,
                status = status,
                showBoot = showBoot,
                passthroughEnabled = passthroughEnabled,
                libraryResized = libraryResized,
                onResetSize = ::resetLibrarySize,
                onLaunch = ::launchApp,
            )
          },
          composePanel(
              R.id.system_bar_panel,
              width = PanelLayout.BAR_WIDTH_METERS,
              height = PanelLayout.BAR_HEIGHT_METERS,
              resolutionScale = PanelLayout.SECONDARY_RESOLUTION_SCALE,
          ) {
            OpenPanelSystemBar(
                showBoot = showBoot,
                passthroughEnabled = passthroughEnabled,
                metaAiAvailable = metaAiAvailable,
                castAvailable = castAvailable,
                batteryPercent = ::batteryPercent,
                batteryCharging = ::batteryCharging,
                controllerBattery = ::controllerBatteryLevels,
                onHome = ::returnHome,
                onToggleEnvironment = ::toggleEnvironment,
                onOpenWifiSettings = { openSettingsScreen(HorizonSystemSettings.wifi) },
                onOpenBluetoothSettings = { openSettingsScreen(HorizonSystemSettings.bluetooth) },
                onLaunchMetaAi = ::launchMetaAi,
                onStartCasting = ::startCasting,
                onOpenSettings = { openSettingsScreen(HorizonSystemSettings.root) },
                onOpenDateSettings = { openSettingsScreen(HorizonSystemSettings.dateTime) },
            )
          },
      )

  private fun composePanel(
      id: Int,
      width: Float,
      height: Float,
      resolutionScale: Float,
      content: @androidx.compose.runtime.Composable () -> Unit,
  ): PanelRegistration =
      ComposeViewPanelRegistration(
          id,
          composeViewCreator = { _, context ->
            ComposeView(context).apply { setContent(content) }
          },
          settingsCreator = {
            UIPanelSettings(
                shape = QuadShapeOptions(width = width, height = height),
                style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                display =
                    DpPerMeterDisplayOptions(
                        dpPerMeter = PanelLayout.DP_PER_METER,
                        resolutionScale = resolutionScale,
                    ),
                // Horizon's own panels are compositor quad layers, sampled once
                // at display resolution with compositor super-sampling and
                // sharpening. The default mesh mode draws the panel into the
                // eye buffer and resamples it — that double sampling is the
                // visible dot/moire grid we fought on dark sheets.
                // Feathered edge OFF: it softens the whole cutout boundary and
                // let the bright skybox bleed through the panel rim ("you can
                // see the background through the panel"). Corners are already
                // anti-aliased in the Compose texture.
                rendering =
                    UIPanelRenderOptions(
                        renderMode =
                            PanelRenderMode.Layer(
                                layerBlendType = PanelShapeLayerBlendType.ALPHA_BLEND,
                                enableLayerFeatheredEdge = false,
                                filters = LayerFilters.AUTO_FILTER,
                            ),
                    ),
            )
          },
      )

  private fun createPanel(id: Int, pose: Pose): Entity =
      Entity.createPanelEntity(id, Transform(pose), Grabbable())

  private fun yaw180(): Quaternion = Quaternion(0f, 180f, 0f)

  private fun createRiverGlintLayer(assetName: String, yLift: Float): Entity =
      Entity.create(
          listOf(
              Mesh(assetName.toUri(), hittable = MeshCollision.NoCollision),
              Transform(Pose(Vector3(0f, yLift, 0f))),
              Visible(!passthroughEnabled),
          ),
      )

  /**
   * A toolkit Material component does NOT override a GLB's own materials
   * (verified on-device: tint changes never render), so animation goes
   * through the runtime: fetch the SceneObject, grab the embedded
   * "river_glint" material, and pan its UV crop window each tick. The two
   * layers load the SAME mesh under two asset names so the cached materials
   * stay independent.
   */
  private fun resolveRiverMaterial(entity: Entity?, assign: (SceneMaterial?) -> Unit) {
    if (entity == null) return
    runCatching {
          systemManager.findSystem<SceneObjectSystem>().getSceneObject(entity)?.thenAccept { obj ->
            val mesh = obj?.mesh
            val material =
                runCatching { mesh?.getMaterial(RIVER_MATERIAL_NAME) }.getOrNull()
                    ?: runCatching { mesh?.getMaterial(0) }.getOrNull()
            if (material == null) Log.w(TAG, "river glint material not found on $entity")
            assign(material)
          }
        }
        .onFailure { Log.w(TAG, "river material resolve failed", it) }
  }

  private fun applyWaterOffset(material: SceneMaterial?, offset: WaterLayerOffset, tile: Float) {
    material?.setCropUV(offset.u, offset.v, offset.u + tile, offset.v + tile)
  }

  private fun startWaterMotion() {
    val startedAt = SystemClock.elapsedRealtime()
    Log.i(TAG, "River water motion active at ${1_000L / WaterMotion.UPDATE_INTERVAL_MS} Hz")
    activityScope.launch {
      var loggedMotionSample = false
      while (true) {
        if (isForeground && !passthroughEnabled) {
          val elapsedMs = SystemClock.elapsedRealtime() - startedAt
          val frame = WaterMotion.frame(elapsedMs)
          applyWaterOffset(riverNearMaterial, frame.near, tile = 1.0f)
          applyWaterOffset(riverFarMaterial, frame.far, tile = 1.45f)
          if (!loggedMotionSample && elapsedMs >= 1_000L) {
            Log.i(
                TAG,
                "River UV motion sample near=${frame.near} far=${frame.far}",
            )
            loggedMotionSample = true
          }
        }
        delay(WaterMotion.UPDATE_INTERVAL_MS)
      }
    }
  }

  // -- Pose persistence ("set up your space" pinning) ------------------------

  private fun savePanelPoses() {
    val editor = prefs.edit()
    poseString(libraryEntity)?.let { editor.putString(PREF_POSE_LIBRARY, it) }
    poseString(barEntity)?.let { editor.putString(PREF_POSE_BAR, it) }
    editor.apply()
  }

  private fun poseString(entity: Entity?): String? =
      runCatching {
            val pose = entity?.getComponent<Transform>()?.transform ?: return null
            listOf(
                    pose.t.x,
                    pose.t.y,
                    pose.t.z,
                    pose.q.w,
                    pose.q.x,
                    pose.q.y,
                    pose.q.z,
                )
                .joinToString(",")
          }
          .getOrNull()

  private fun savedPose(key: String): Pose? =
      runCatching {
            val parts = prefs.getString(key, null)?.split(',')?.map { it.toFloat() } ?: return null
            if (parts.size != 7) return null
            Pose(
                Vector3(parts[0], parts[1], parts[2]),
                Quaternion(parts[3], parts[4], parts[5], parts[6]),
            )
          }
          .getOrNull()

  // -- Policy / discovery -----------------------------------------------------

  private fun launcherRestrictions(): Bundle {
    val manager = getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    return manager.applicationRestrictions ?: Bundle.EMPTY
  }

  private fun launcherPolicy(): LauncherPolicy {
    val restrictions = launcherRestrictions()
    fun csv(key: String): Set<String> =
        restrictions
            .getString(key)
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    return LauncherPolicy(
        visiblePackages = csv("visible_packages"),
        hiddenPackages = csv("hidden_packages"),
        showAllApps = restrictions.getBoolean("show_all_apps", false),
    )
  }

  private fun readyStatus(): String {
    val count = apps.size
    val noun = if (count == 1) "app" else "apps"
    return if (assignmentClient() != null) "$count $noun assigned" else "$count $noun ready"
  }

  private fun assignmentClient(): String? {
    val client = apps.firstOrNull()?.assignmentClient ?: return null
    return client.takeIf { expected -> apps.all { it.assignmentClient == expected } }
  }

  private fun recentLaunches(): Map<String, Long> =
      RecentLaunchStore.parse(prefs.getString(PREF_RECENT_LAUNCHES, null))

  private fun recordRecentLaunch(launchedPackage: String) {
    val updated =
        RecentLaunchStore.withLaunch(recentLaunches(), launchedPackage, System.currentTimeMillis())
    prefs.edit().putString(PREF_RECENT_LAUNCHES, RecentLaunchStore.serialize(updated)).apply()
  }

  private fun refreshApps() {
    refreshCapabilities()
    activityScope.launch {
      val policy = launcherPolicy()
      val recents = recentLaunches()
      val result =
          runCatching {
            withContext(Dispatchers.Default) {
              AppDiscovery(packageManager, packageName).discover(policy, recents)
            }
          }
      result.fold(
          onSuccess = { discovered ->
            apps = discovered
            status =
                if (discovered.isEmpty()) "No apps are assigned to this headset yet."
                else readyStatus()
          },
          onFailure = { error -> status = error.message ?: "Unable to scan installed apps." },
      )
    }
  }

  // -- Environment, capabilities, system entries ------------------------------

  private fun toggleEnvironment() {
    passthroughEnabled = !passthroughEnabled
    prefs.edit().putBoolean(PREF_PASSTHROUGH, passthroughEnabled).apply()
    domeEntity?.setComponent(Visible(!passthroughEnabled))
    videoDomeEntity?.setComponent(Visible(!passthroughEnabled))
    runCatching { videoPlayer?.playWhenReady = isForeground && !passthroughEnabled }
    nearEnvEntity?.setComponent(Visible(!passthroughEnabled))
    waterNearEntity?.setComponent(Visible(!passthroughEnabled))
    waterFarEntity?.setComponent(Visible(!passthroughEnabled))
    scene.enablePassthrough(passthroughEnabled)
    updateAmbience()
  }

  /**
   * Subtle forest ambience, alive whenever OpenPanel itself is foreground —
   * in both the immersive world and passthrough (Anthony, 2026-08-22) — and
   * paused the moment any other app takes over.
   */
  private fun updateAmbience() {
    val shouldPlay = isForeground
    if (shouldPlay) {
      val player =
          ambience
              ?: runCatching { MediaPlayer.create(this, R.raw.forest_river_birdsong_background_v1) }
                  .getOrNull()
                  ?.apply {
                    isLooping = true
                    setVolume(0.22f, 0.22f)
                  }
                  ?.also { ambience = it }
      runCatching { if (player?.isPlaying == false) player.start() }
    } else {
      runCatching { if (ambience?.isPlaying == true) ambience?.pause() }
    }
  }

  // -- Panel scale ------------------------------------------------------------

  private fun libraryScale(): Float = prefs.getFloat(PREF_LIB_SCALE, 1f)

  private fun applyLibraryScale(scale: Float) {
    runCatching { libraryEntity?.setComponent(Scale(Vector3(scale, scale, scale))) }
  }

  private fun currentLibraryScale(): Float =
      runCatching { libraryEntity?.getComponent<Scale>()?.scale?.x }.getOrNull() ?: 1f

  /**
   * Keeps the header's subtle reset-size control in sync with the window's
   * actual scale — ISDK pinch-resize mutates the entity outside our code, so
   * a light poll (only while foregrounded) is the sync point. Also persists
   * the latest pinch result so a resize survives restarts.
   */
  private fun startLibraryScaleWatcher() {
    activityScope.launch {
      while (true) {
        if (isForeground) {
          val scale = currentLibraryScale()
          val resized = PanelLayout.isResizedScale(scale)
          if (resized != libraryResized) libraryResized = resized
          if (resized || prefs.contains(PREF_LIB_SCALE)) {
            // Persist only when the pinch actually moved the window. The poll
            // runs at 1.4 Hz forever, so re-writing an unchanged float every
            // tick churned the preference file for the life of the process.
            val last = lastWrittenLibraryScale
            if (last == null || abs(scale - last) > SCALE_WRITE_EPSILON) {
              prefs.edit().putFloat(PREF_LIB_SCALE, scale).apply()
              lastWrittenLibraryScale = scale
            }
          }
        }
        delay(SCALE_WATCH_INTERVAL_MS)
      }
    }
  }

  /** Restores the library window to its authored size. */
  private fun resetLibrarySize() {
    applyLibraryScale(1f)
    prefs.edit().remove(PREF_LIB_SCALE).apply()
    libraryResized = false
    // The stored scale is gone, so the watcher must be free to write again
    // even if the next pinch lands on the same value it last persisted.
    lastWrittenLibraryScale = null
  }

  /**
   * Fires a SystemUX overlay command via Meta's documented system deep-link
   * contract: the vrshell launch intent with `intent_data`. The panel opens
   * as a small overlay OVER the running app (ArborXR Home behavior) instead
   * of app-switching away.
   */
  private fun openSystemUxOverlay(command: String, uriPath: String? = null): Boolean =
      runCatching {
            // Exact shape verified on-device 2026-08-23: VIEW + explicit
            // vrshell MainActivity + intent_data extra → SEO router starts the
            // target with OVERLAY_LAUNCHER (a panel over the running app).
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setClassName(SystemUx.SHELL_PACKAGE, "com.oculus.vrshell.MainActivity")
                    .putExtra(SystemUx.EXTRA_INTENT_DATA, command)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (uriPath != null) intent.putExtra(SystemUx.EXTRA_URI, uriPath)
            startActivity(intent)
            true
          }
          .getOrDefault(false)

  /** Opens a system panel: SystemUX overlay first, settings activity fallback. */
  private fun openSettingsScreen(route: SystemSettingsRoute) {
    val overlay = route.overlayCommand
    if (overlay != null && openSystemUxOverlay(overlay)) return
    val direct =
        runCatching { startActivity(Intent(route.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    if (direct.isFailure && route.action != route.fallbackAction) {
      runCatching {
            startActivity(Intent(route.fallbackAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          }
          .onFailure { status = "Settings are unavailable on this headset." }
    }
  }

  /** Brings the launcher itself back to the foreground. */
  private fun returnHome() {
    runCatching {
      startActivity(
          Intent(this, OpenPanelActivity::class.java)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      )
    }
  }

  /**
   * Social gating: on Horizon OS, disabling social features disables the
   * Horizon social app. Meta AI only appears when that package is present and
   * enabled.
   */
  private fun socialAllowed(): Boolean =
      runCatching { packageManager.getApplicationInfo(SOCIAL_PACKAGE, 0).enabled }
          .getOrDefault(false)

  private fun metaAiIntent(): Intent =
      Intent(META_AI_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  private fun launchMetaAi() {
    runCatching { startActivity(metaAiIntent()) }
        .onFailure { status = "Meta AI is unavailable on this headset." }
  }

  private fun castingIntent(route: ExternalActivityRoute): Intent =
      Intent(route.action)
          .setClassName(route.packageName, route.componentClassName())
          .apply { route.categories.forEach(::addCategory) }
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

  private fun ExternalActivityRoute.componentClassName(): String =
      if (activityName.startsWith(".")) packageName + activityName else activityName

  private fun routeInstalled(route: ExternalActivityRoute): Boolean =
      runCatching {
          packageManager
              .getActivityInfo(ComponentName(route.packageName, route.componentClassName()), 0)
              .enabled
        }
        .getOrDefault(false)

  /**
   * Opens Horizon OS' exported native sharing panel, where Cast is a
   * system-owned action ("Cast from this headset" tile). Feedback goes
   * through a system toast: the old status-field message only rendered in the
   * library's empty state, so a slow or silent panel launch read as the Cast
   * button doing nothing.
   */
  private fun startCasting() {
    // Overlay first: Horizon's Quick Settings (small panel over the app —
    // Cast, Wi-Fi, and Bluetooth live there) instead of MetaCam's camera app.
    // This firmware has no dedicated sharing/cast systemux route.
    if (openSystemUxOverlay(SystemUx.QUICK_SETTINGS)) {
      announce("Cast lives in Quick Settings — top row.")
      return
    }

    val routes = HorizonCasting.preferredRoutes(::routeInstalled)
    if (routes.isEmpty()) {
      announce("Casting is unavailable on this headset.")
      return
    }

    for (route in routes) {
      val result = runCatching { startActivity(castingIntent(route)) }
      if (result.isSuccess) {
        announce("Opening Horizon sharing — choose Cast from this headset.")
        return
      }
      Log.w(TAG, "casting route failed: $route", result.exceptionOrNull())
    }

    announce("Could not open Horizon casting.")
  }

  /** User-visible feedback that also survives outside the library panel. */
  private fun announce(message: String) {
    status = message
    runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
  }

  private fun refreshCapabilities() {
    metaAiAvailable =
        socialAllowed() &&
            runCatching { metaAiIntent().resolveActivity(packageManager) != null }
                .getOrDefault(false)
    castAvailable = HorizonCasting.preferredRoutes(::routeInstalled).isNotEmpty()
  }

  private fun batteryCharging(): Boolean =
      runCatching {
            val intent =
                registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
          }
          .getOrDefault(false)

  private fun batteryPercent(): Int =
      runCatching {
            (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
          }
          .getOrDefault(0)
          .coerceIn(0, 100)

  private fun controllerBatteryLevels(): Pair<Int, Int> =
      runCatching {
            val im = getSystemService(Context.INPUT_SERVICE) as InputManager
            var left = -1
            var right = -1
            for (id in im.inputDeviceIds) {
              val dev = im.getInputDevice(id) ?: continue
              if (dev.sources and InputDevice.SOURCE_JOYSTICK == 0) continue
              val cap = (dev.batteryState.capacity * 100).toInt().coerceIn(0, 100)
              val name = dev.name.lowercase()
              if (name.contains("left")) left = cap
              else if (name.contains("right")) right = cap
            }
            left to right
          }
          .getOrDefault(-1 to -1)

  private fun launchApp(app: LaunchableApp) {
    val intent =
        Intent(Intent.ACTION_MAIN).apply {
          component = ComponentName(app.packageName, app.activityName)
          addCategory(if (app.isVr) AppDiscovery.VR_CATEGORY else Intent.CATEGORY_LAUNCHER)
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
    runCatching { startActivity(intent) }
        .onSuccess {
          status = "Launching ${app.label}…"
          recordRecentLaunch(app.packageName)
        }
        .onFailure { error -> status = error.message ?: "Could not launch ${app.label}." }
  }

  companion object {
    private const val TAG = "OpenPanel"
    private const val PREF_LIB_SCALE = "library_scale"
    private const val PREF_PASSTHROUGH = "passthrough_enabled"
    private const val PREF_POSE_LIBRARY = "pose_library"
    private const val PREF_POSE_BAR = "pose_bar"
    private const val PREF_RECENT_LAUNCHES = "recent_launches"
    private const val PREF_SCENE_PERMISSION_ASKED = "scene_permission_asked"
    private const val PERMISSION_USE_SCENE = "com.oculus.permission.USE_SCENE"
    private const val REQUEST_CODE_USE_SCENE = 1001
    private const val SCALE_WATCH_INTERVAL_MS = 700L
    // Far below PanelLayout.isResizedScale's 2% "meaningfully resized" bar, so
    // any real pinch still persists; only identical re-polls are skipped.
    private const val SCALE_WRITE_EPSILON = 0.001f
    private const val SOCIAL_PACKAGE = "com.facebook.horizon"
    private const val META_AI_ACTION = "horizonos.content.action.LAUNCH_ASSISTANT_NUX"
    // OFF per Anthony 2026-08-24: the baked river carries the look for now;
    // the animation pipeline stays for a future pass (learnings in AGENTS.md).
    private const val ENABLE_ANIMATED_WATER_OVERLAY = false
    private const val RIVER_MATERIAL_NAME = "river_glint"
    private const val ENABLE_NATIVE_NEAR_ENV = false
    // 360 video environment (test chain): entity only spawns when the clip
    // file exists at getExternalFilesDir()/VIDEO_DOME_FILE.
    private const val ENABLE_VIDEO_DOME = true
    // Custom skybox mesh for the dome; flip off to return to the SDK equirect.
    private const val ENABLE_SEAMLESS_DOME = true
    private const val VIDEO_DOME_FILE = "forest360.mp4"
  }
}

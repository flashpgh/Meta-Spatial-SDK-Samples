/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.splatsample

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.SpatialSDKInternalTestingAPI
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.okhttp3.OkHttpAssetFetcher
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.NetworkedAssetLoader
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.splat.SpatialSDKExperimentalSplatAPI
import com.meta.spatial.splat.Splat
import com.meta.spatial.splat.SplatFeature
import com.meta.spatial.splat.SplatLoadEventArgs
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.GLXFInfo
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SupportsLocomotion
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.VRFeature
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(SpatialSDKExperimentalSplatAPI::class)
class SplatSampleActivity : AppSystemActivity() {

  private var gltfxEntity: Entity? = null
  private val activityScope = CoroutineScope(Dispatchers.Main)

  private lateinit var environmentEntity: Entity
  private lateinit var skyboxEntity: Entity
  private lateinit var panelEntity: Entity
  private lateinit var floorEntity: Entity
  private lateinit var splatEntity: Entity

  // --- UI state ---
  private val splatListState = mutableStateOf<List<String>>(emptyList())
  private val selectedIndex = mutableStateOf(0)

  // Zero-trust debug log lines shown in UI panel
  private val debugLogState = mutableStateOf<List<String>>(emptyList())

  private var defaultSplatPath: Uri? = null

  private val panelHeight = 1.5f
  private val panelOffset = 2.5f
  private val defaultZ = 4f

  // Rotation applied to the Splat to align it with the scene coordinate system
  // -90 degrees on X axis converts from original Splat coordinate space to Spatial SDK space
  private val eulerRotation = Vector3(-90f, 0f, 0f)

  // Optional per-splat Z overrides (keep existing behavior for the old sample names)
  private val zOverridesByName: Map<String, Float> =
      mapOf(
          "Menlo Park.spz" to 2.5f,
          "Los Angeles.spz" to 4f,
      )

  private val headQuery =
      Query.where { has(AvatarAttachment.id) }
          .filter { isLocal() and by(AvatarAttachment.typeData).isEqualTo("head") }

  // Persistent local splats list (app-private file:// URIs)
  private val prefs by lazy { getSharedPreferences("splatsample_prefs", Context.MODE_PRIVATE) }
  private val PREF_LOCAL_SPLATS = "local_splats_file_uris"

  // File picker for local splats
  private val openLocalSplatPicker =
      registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
          appendLog("Pick local splat: cancelled")
          return@registerForActivityResult
        }
        activityScope.launch {
          onPickedLocalSplat(uri)
        }
      }

  // Register all features your app needs. Features add capabilities to the Spatial SDK.
  override fun registerFeatures(): List<SpatialFeature> {
    return listOf(
        VRFeature(this),
        SplatFeature(this.spatialContext, systemManager),
        ComposeFeature(),
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    appendLog("onCreate: starting")
    NetworkedAssetLoader.init(
        File(applicationContext.cacheDir.canonicalPath),
        OkHttpAssetFetcher(),
    )

    // Build list before scene inflates
    rebuildSplatList(reason = "startup")
    selectedIndex.value = 0
    defaultSplatPath = splatListState.value.firstOrNull()?.toUri()

    loadGLXF { composition ->
      environmentEntity = composition.getNodeByName("Environment").entity
      val environmentMesh = environmentEntity.getComponent<Mesh>()
      environmentMesh.defaultShaderOverride = SceneMaterial.UNLIT_SHADER
      environmentEntity.setComponent(environmentMesh)

      floorEntity = composition.getNodeByName("Floor").entity

      val initial = defaultSplatPath
      if (initial != null) {
        appendLog("initial splat: $initial")
        initializeSplat(initial)
        setSplatVisibility(true)
      } else {
        appendLog("No splats found in assets/ or local list. Add files or use Load from device.")
        setEnvironmentVisiblity(true)
      }
    }
  }

  @OptIn(SpatialSDKInternalTestingAPI::class)
  override fun onSceneReady() {
    super.onSceneReady()
    registerTestingIntentReceivers()

    scene.setLightingEnvironment(
        ambientColor = Vector3(0f),
        sunColor = Vector3(7.0f, 7.0f, 7.0f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.3f,
    )

    scene.updateIBLEnvironment("environment.env")
    scene.setViewOrigin(0.0f, 0.0f, defaultZ, 90.0f)

    skyboxEntity =
        Entity.create(
            listOf(
                Mesh(Uri.parse("mesh://skybox"), hittable = MeshCollision.NoCollision),
                Material().apply {
                  baseTextureAndroidResourceId = R.drawable.skydome
                  unlit = true
                },
                Transform(Pose(Vector3(x = 0f, y = 0f, z = 0f))),
            )
        )

    panelEntity =
        Entity.createPanelEntity(
            R.id.control_panel,
            Transform(Pose(Vector3(0f, panelHeight, 0f), Quaternion(0f, 180f, 0f))),
            Grabbable(type = GrabbableType.PIVOT_Y, minHeight = 0.75f, maxHeight = 2.5f),
        )

    systemManager.registerSystem(ControllerListenerSystem())
  }

  /**
   * Build the full list shown in the UI:
   * - Bundled assets: apk://*.spz/*.ply
   * - Local picks (persisted): file://... (app-private)
   */
  private fun rebuildSplatList(reason: String) {
    val bundled = discoverBundledSplats()
    val local = loadPersistedLocalSplats().filter { it.startsWith("file://") }
    val combined = (bundled + local).distinct()

    splatListState.value = combined

    appendLog(
        "rebuildSplatList($reason): bundled=${bundled.size}, local=${local.size}, total=${combined.size}"
    )

    if (combined.isEmpty()) {
      appendLog("No splats available yet.")
    } else {
      appendLog("First entry: ${combined.first()}")
    }
  }

  private fun discoverBundledSplats(): List<String> {
    return try {
      val names = applicationContext.assets.list("")?.toList().orEmpty()
      val out =
          names
              .filter { it.endsWith(".spz", true) || it.endsWith(".ply", true) }
              .sortedWith(String.CASE_INSENSITIVE_ORDER)
              .map { "apk://$it" }

      appendLog("discoverBundledSplats: found=${out.size}")
      out
    } catch (t: Throwable) {
      appendLog("discoverBundledSplats: ERROR: ${t.message}")
      Log.e("SplatManager", "Failed to enumerate assets", t)
      emptyList()
    }
  }

  /** UI action: open a file picker to select a splat from headset storage. */
  fun pickLocalSplatFromDevice() {
    appendLog("pickLocalSplatFromDevice: launching file picker")
    // Keep it permissive; we validate extension ourselves.
    openLocalSplatPicker.launch(arrayOf("*/*"))
  }

  private suspend fun onPickedLocalSplat(uri: Uri) {
    appendLog("Picked URI: $uri")

    val doc = DocumentFile.fromSingleUri(this, uri)
    val name = doc?.name ?: "picked_splat"
    appendLog("Picked name: $name")

    val lower = name.lowercase()
    if (!(lower.endsWith(".spz") || lower.endsWith(".ply"))) {
      appendLog("Rejected: not .spz/.ply ($name)")
      return
    }

    // Copy into app-private storage so we can always load it via file://
    val destDir = File(filesDir, "splats")
    destDir.mkdirs()

    val safeName = name.replace(Regex("""[^\w\s\.\-\(\)]"""), "_")
    val destFile = File(destDir, safeName)

    appendLog("Copying to: ${destFile.absolutePath}")

    try {
      contentResolver.openInputStream(uri).use { input ->
        if (input == null) {
          appendLog("ERROR: openInputStream returned null for $uri")
          return
        }
        FileOutputStream(destFile).use { output ->
          input.copyTo(output)
        }
      }
    } catch (t: Throwable) {
      appendLog("ERROR copying file: ${t.message}")
      Log.e("SplatManager", "Copy failed", t)
      return
    }

    val fileUri = destFile.toUri().toString()
    appendLog("Copied OK. Local file URI: $fileUri")

    persistLocalSplat(fileUri)
    rebuildSplatList(reason = "picked_local")

    // Select and load immediately
    val idx = splatListState.value.indexOf(fileUri)
    if (idx >= 0) selectedIndex.value = idx

    loadSplat(fileUri)
  }

  private fun loadPersistedLocalSplats(): List<String> {
    val set = prefs.getStringSet(PREF_LOCAL_SPLATS, emptySet()) ?: emptySet()
    return set.toList().sortedWith(String.CASE_INSENSITIVE_ORDER).also {
      appendLog("loadPersistedLocalSplats: ${it.size}")
    }
  }

  private fun persistLocalSplat(fileUri: String) {
    val existing = prefs.getStringSet(PREF_LOCAL_SPLATS, emptySet())?.toMutableSet() ?: mutableSetOf()
    existing.add(fileUri)
    prefs.edit().putStringSet(PREF_LOCAL_SPLATS, existing).apply()
    appendLog("persistLocalSplat: added=$fileUri total=${existing.size}")
  }

  private fun appendLog(msg: String) {
    Log.d("SplatManager", msg)
    val now = System.currentTimeMillis() % 100000
    val line = "${now}ms | $msg"
    val current = debugLogState.value
    // Keep last 80 lines
    debugLogState.value = (current + line).takeLast(80)
  }

  private fun initializeSplat(splatPath: Uri) {
    appendLog("initializeSplat: $splatPath")
    splatEntity =
        Entity.create(
            listOf(
                Splat(splatPath),
                Transform(
                    Pose(
                        Vector3(0.0f, 0.0f, 0.0f),
                        Quaternion(eulerRotation.x, eulerRotation.y, eulerRotation.z),
                    )
                ),
                Scale(Vector3(1f)),
                SupportsLocomotion(),
            )
        )

    splatEntity.registerEventListener<SplatLoadEventArgs>(SplatLoadEventArgs.EVENT_NAME) { _, _ ->
      appendLog("Splat loaded EVENT")
      onSplatLoaded()
    }
  }

  private fun onSplatLoaded() {
    recenterScene()
    setSplatVisibility(true)
  }

  /** Load a new splat path (apk://, file://, https://). */
  fun loadSplat(newSplatPath: String) {
    appendLog("loadSplat: $newSplatPath")

    if (!::splatEntity.isInitialized) {
      appendLog("WARNING: splatEntity not initialized yet; ignoring loadSplat")
      return
    }

    if (splatEntity.hasComponent<Splat>()) {
      val splatComponent = splatEntity.getComponent<Splat>()
      if (splatComponent.path.toString() == newSplatPath) {
        appendLog("loadSplat: already showing; no-op")
        return
      }
      splatEntity.setComponent(Splat(newSplatPath.toUri()))
      setSplatVisibility(false)
      recenterScene()
    } else {
      splatEntity.setComponent(Splat(newSplatPath.toUri()))
      setSplatVisibility(false)
      recenterScene()
    }
  }

  fun setSplatVisibility(isSplatVisible: Boolean) {
    if (!::splatEntity.isInitialized) return
    splatEntity.setComponent(Visible(isSplatVisible))
    setEnvironmentVisiblity(!isSplatVisible)
  }

  fun setEnvironmentVisiblity(isVisible: Boolean) {
    environmentEntity.setComponent(Visible(isVisible))
    skyboxEntity.setComponent(Visible(isVisible))
  }

  fun recenterScene() {
    val currentPath =
        if (::splatEntity.isInitialized && splatEntity.hasComponent<Splat>())
            splatEntity.getComponent<Splat>().path.toString()
        else defaultSplatPath?.toString()

    val filename = currentPath?.substringAfterLast("/")?.removePrefix("apk://") ?: ""
    val z = zOverridesByName[filename] ?: defaultZ

    appendLog("recenterScene: path=$currentPath z=$z")

    scene.setViewOrigin(0f, 0f, z, 0f)
    panelEntity.setComponent(
        Transform(Pose(Vector3(0f, panelHeight, z - panelOffset), Quaternion(0f, 180f, 0f)))
    )
  }

  private fun positionPanelInFrontOfUser(distance: Float) {
    val head = headQuery.eval().firstOrNull() ?: return
    val headPose = head.getComponent<Transform>().transform
    val forward = headPose.forward()
    forward.y = 0f
    val forwardNormalized = forward.normalize()
    var newPosition = headPose.t + (forwardNormalized * distance)
    newPosition.y = panelHeight
    val lookRotation = Quaternion.lookRotation(forwardNormalized)
    panelEntity.setComponent(Transform(Pose(newPosition, lookRotation)))
  }

  inner class ControllerListenerSystem : SystemBase() {
    override fun execute() {
      val controllers = Query.where { has(Controller.id) }.eval().filter { it.isLocal() }

      for (controllerEntity in controllers) {
        val controller = controllerEntity.getComponent<Controller>()
        if (!controller.isActive) continue

        val attachment = controllerEntity.tryGetComponent<AvatarAttachment>()
        if (attachment?.type != "right_controller") continue

        if (
            (controller.changedButtons and ButtonBits.ButtonA) != 0 &&
                (controller.buttonState and ButtonBits.ButtonA) != 0
        ) {
          positionPanelInFrontOfUser(panelOffset)
        }

        if (
            (controller.changedButtons and ButtonBits.ButtonB) != 0 &&
                (controller.buttonState and ButtonBits.ButtonB) != 0
        ) {
          recenterScene()
        }
      }
    }
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  override fun registerPanels(): List<PanelRegistration> {
    return listOf(
        createSimpleComposePanel(
            R.id.control_panel,
            ANIMATION_PANEL_WIDTH,
            ANIMATION_PANEL_HEIGHT,
        ) {
          ControlPanel(
              splatList = splatListState.value,
              selectedIndex = selectedIndex,
              loadSplatFunction = ::loadSplat,
              pickLocalSplatFunction = ::pickLocalSplatFromDevice,
              debugLogLines = debugLogState.value,
              rescanBundledFunction = { rebuildSplatList("manual_rescan") },
          )
        },
    )
  }

  private fun loadGLXF(onLoaded: ((GLXFInfo) -> Unit) = {}): Job {
    gltfxEntity = Entity.create()
    return activityScope.launch {
      glXFManager.inflateGLXF(
          Uri.parse("apk:///scenes/Composition.glxf"),
          rootEntity = gltfxEntity!!,
          keyName = "example_key_name",
          onLoaded = onLoaded,
      )
    }
  }

  private fun createSimpleComposePanel(
      panelId: Int,
      width: Float,
      height: Float,
      content: @androidx.compose.runtime.Composable () -> Unit,
  ): ComposeViewPanelRegistration {
    return ComposeViewPanelRegistration(
        panelId,
        composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { content() } } },
        settingsCreator = {
          UIPanelSettings(
              shape = QuadShapeOptions(width = width, height = height),
              style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
              display = DpPerMeterDisplayOptions(),
          )
        },
    )
  }
}

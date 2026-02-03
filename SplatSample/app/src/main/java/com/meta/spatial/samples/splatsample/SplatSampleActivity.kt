/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.splatsample

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.net.toUri
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

  // UI state
  private val splatListState = mutableStateOf<List<String>>(emptyList())
  private val selectedIndex = mutableStateOf(0)

  // Debug log lines shown in UI panel (last N lines)
  private val debugLogState = mutableStateOf<List<String>>(emptyList())

  // External folder path displayed in UI (computed once)
  private val externalFolderPathState = mutableStateOf("(initializing...)")

  private var defaultSplatPath: Uri? = null

  // Rotation applied to the Splat to align it with the scene coordinate system
  private val eulerRotation = Vector3(-90f, 0f, 0f)

  private val panelHeight = 1.5f
  private val panelOffset = 2.5f
  private val defaultZ = 4f

  // Optional per-splat Z overrides for the legacy sample names
  private val zOverridesByName: Map<String, Float> =
      mapOf(
          "Menlo Park.spz" to 2.5f,
          "Los Angeles.spz" to 4f,
      )

  // App-owned folder on headset storage: no special permissions needed
  private val splatsFolderName = "splats"
  private var externalSplatsDir: File? = null

  private val headQuery =
      Query.where { has(AvatarAttachment.id) }
          .filter { isLocal() and by(AvatarAttachment.typeData).isEqualTo("head") }

  override fun registerFeatures(): List<SpatialFeature> {
    return listOf(
        VRFeature(this),
        SplatFeature(this.spatialContext, systemManager),
        ComposeFeature(),
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    appendLog("onCreate: start")

    NetworkedAssetLoader.init(
        File(applicationContext.cacheDir.canonicalPath),
        OkHttpAssetFetcher(),
    )

    // Compute external folder ONCE (no Compose spam)
    externalSplatsDir = initExternalSplatsDir()
    externalFolderPathState.value = externalSplatsDir?.absolutePath ?: "(unavailable)"
    appendLog("external folder: ${externalFolderPathState.value}")

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
        appendLog("No splats found. Push .spz/.ply into external folder and press Rescan.")
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

  /** Called by UI panel */
  fun rescanSplats() {
    appendLog("Rescan pressed")
    rebuildSplatList(reason = "user_rescan")

    val list = splatListState.value
    if (list.isEmpty()) {
      selectedIndex.value = 0
      defaultSplatPath = null
      return
    }
    if (selectedIndex.value !in list.indices) selectedIndex.value = 0
    defaultSplatPath = list.firstOrNull()?.toUri()
  }

  private fun rebuildSplatList(reason: String) {
    val bundled = discoverBundledSplats()
    val external = discoverExternalSplats()

    val combined = (bundled + external).distinct()
    splatListState.value = combined

    appendLog(
        "rebuildSplatList($reason): bundled=${bundled.size}, external=${external.size}, total=${combined.size}"
    )

    if (external.isNotEmpty()) {
      appendLog("external files:")
      external.take(12).forEach { appendLog("  - ${it.substringAfterLast("/")}") }
      if (external.size > 12) appendLog("  ... +${external.size - 12} more")
    } else {
      appendLog("external files: (none)")
    }
  }

  private fun discoverBundledSplats(): List<String> {
    return try {
      val names = applicationContext.assets.list("")?.toList().orEmpty()
      names
          .filter { it.endsWith(".spz", true) || it.endsWith(".ply", true) }
          .sortedWith(String.CASE_INSENSITIVE_ORDER)
          .map { "apk://$it" }
          .also { appendLog("bundled assets found: ${it.size}") }
    } catch (t: Throwable) {
      appendLog("discoverBundledSplats ERROR: ${t.message}")
      emptyList()
    }
  }

  private fun initExternalSplatsDir(): File? {
    val base = getExternalFilesDir(null)
    if (base == null) return null
    val dir = File(base, splatsFolderName)
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  private fun discoverExternalSplats(): List<String> {
    val dir = externalSplatsDir ?: return emptyList()

    val files =
        dir.listFiles()?.toList().orEmpty().filter {
          it.isFile && (it.name.endsWith(".spz", true) || it.name.endsWith(".ply", true))
        }

    return files.sortedBy { it.name.lowercase() }.map { it.toUri().toString() } // file://...
  }

  private fun appendLog(msg: String) {
    Log.d("SplatManager", msg)
    val now = System.currentTimeMillis() % 100000
    val line = "${now}ms | $msg"
    debugLogState.value = (debugLogState.value + line).takeLast(60)
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

  fun loadSplat(newSplatPath: String) {
    appendLog("loadSplat: $newSplatPath")

    if (!::splatEntity.isInitialized) {
      appendLog("WARNING: splatEntity not initialized yet; ignoring")
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

    val filename = currentPath?.substringAfterLast("/") ?: ""
    val z = zOverridesByName[filename] ?: defaultZ

    appendLog("recenterScene: filename=$filename z=$z")
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
              rescanFunction = ::rescanSplats,
              debugLogLines = debugLogState.value,
              externalFolderPath = externalFolderPathState.value,
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

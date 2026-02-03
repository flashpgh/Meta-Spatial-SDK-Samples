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

  private val TAG = "SplatSample"
  private var gltfxEntity: Entity? = null
  private val activityScope = CoroutineScope(Dispatchers.Main)

  private lateinit var environmentEntity: Entity
  private lateinit var skyboxEntity: Entity
  private lateinit var panelEntity: Entity
  private lateinit var floorEntity: Entity

  private lateinit var splatEntity: Entity

  // Built-in demo splats (packaged inside APK assets)
  private val builtInSplats: List<String> = listOf("apk://Menlo Park.spz", "apk://Los Angeles.spz")

  // State for UI
  private val selectedIndex = mutableStateOf(0)
  private val builtInDefault = builtInSplats[0].toUri()

  // Dynamic: headset-side splats in /sdcard/Android/data/<pkg>/files/splats
  private val deviceSplatsState = mutableStateOf<List<SplatItem>>(emptyList())

  // Small in-app log buffer for sanity
  private val uiLogState = mutableStateOf(listOf<String>())

  // Rotation applied to align splat coords with Spatial SDK (matches sample)
  private val eulerRotation = Vector3(-90f, 0f, 0f)

  private val panelHeight = 1.5f
  private val panelOffset = 2.5f
  private val laxZ = 4f
  private val mpkZ = 2.5f

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

    NetworkedAssetLoader.init(
        File(applicationContext.cacheDir.canonicalPath),
        OkHttpAssetFetcher(),
    )

    appendLog("onCreate()")
    rebuildDeviceSplatList()

    loadGLXF { composition ->
      appendLog("GLXF loaded: Composition.glxf")

      environmentEntity = composition.getNodeByName("Environment").entity
      val environmentMesh = environmentEntity.getComponent<Mesh>()
      environmentMesh.defaultShaderOverride = SceneMaterial.UNLIT_SHADER
      environmentEntity.setComponent(environmentMesh)

      floorEntity = composition.getNodeByName("Floor").entity

      // Start with built-in default splat so the scene is always valid
      initializeSplat(builtInDefault)
      setSplatVisibility(true)
    }
  }

  @OptIn(SpatialSDKInternalTestingAPI::class)
  override fun onSceneReady() {
    super.onSceneReady()

    registerTestingIntentReceivers()

    scene.setLightingEnvironment(
        ambientColor = Vector3(0f),
        sunColor = Vector3(7.0f, 7.0f, 7.0f),
        sunDirection = -Vector3(1.0f, 3.0f, -Vector3(2.0f, 0f, 0f).z), // keep stable-ish
        environmentIntensity = 0.3f,
    )
    scene.updateIBLEnvironment("environment.env")
    scene.setViewOrigin(0.0f, 0.0f, 2.5f, 90.0f)

    skyboxEntity =
        Entity.create(
            listOf(
                Mesh(Uri.parse("mesh://skybox"), hittable = MeshCollision.NoCollision),
                Material().apply {
                  baseTextureAndroidResourceId = R.drawable.skydome
                  unlit = true
                },
                Transform(Pose(Vector3(0f, 0f, 0f))),
            )
        )

    panelEntity =
        Entity.createPanelEntity(
            R.id.control_panel,
            Transform(Pose(Vector3(0f, panelHeight, 0f), Quaternion(0f, 180f, 0f))),
            Grabbable(type = GrabbableType.PIVOT_Y, minHeight = 0.75f, maxHeight = 2.5f),
        )

    systemManager.registerSystem(ControllerListenerSystem())
    appendLog("Scene ready")
  }

  /**
   * Create an entity with a Splat component + transform/scale.
   */
  private fun initializeSplat(splatPath: Uri) {
    appendLog("initializeSplat($splatPath)")

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
      Log.d(TAG, "Splat loaded event")
      appendLog("Splat loaded event")
      onSplatLoaded()
    }
  }

  private fun onSplatLoaded() {
    recenterScene()
    setSplatVisibility(true)
  }

  /**
   * Load a splat from either:
   * - "apk://Name.spz" (assets)
   * - "file:///.../files/splats/foo.ply" (device folder)
   */
  fun loadSplat(newSplatPath: String) {
    appendLog("loadSplat($newSplatPath)")

    if (splatEntity.hasComponent<Splat>()) {
      val splatComponent = splatEntity.getComponent<Splat>()
      if (splatComponent.path.toString() == newSplatPath) {
        appendLog("Already showing that splat")
        return
      }
      splatEntity.setComponent(Splat(newSplatPath.toUri()))
      setSplatVisibility(false)
      recenterScene()
    } else {
      splatEntity.setComponent(Splat(newSplatPath.toUri()))
    }
  }

  fun setSplatVisibility(isSplatVisible: Boolean) {
    splatEntity.setComponent(Visible(isSplatVisible))
    setEnvironmentVisibility(!isSplatVisible)
  }

  private fun setEnvironmentVisibility(isVisible: Boolean) {
    environmentEntity.setComponent(Visible(isVisible))
    skyboxEntity.setComponent(Visible(isVisible))
  }

  fun recenterScene() {
    var z = laxZ
    if (splatEntity.getComponent<Splat>().path.toString() == builtInDefault.toString()) {
      z = mpkZ
    }
    scene.setViewOrigin(0f, 0f, z, 0f)
    panelEntity.setComponent(
        Transform(Pose(Vector3(0f, panelHeight, z - panelOffset), Quaternion(0f, 180f, 0f)))
    )
    appendLog("recenterScene(z=$z)")
  }

  /**
   * Rebuilds the headset-side splat list by scanning:
   *   context.getExternalFilesDir(null)/splats
   * which maps to:
   *   /sdcard/Android/data/<pkg>/files/splats
   */
  fun rebuildDeviceSplatList() {
    val base = applicationContext.getExternalFilesDir(null)
    val splatDir = File(base, "splats")

    if (!splatDir.exists()) {
      val ok = splatDir.mkdirs()
      appendLog("Created splat dir: ${splatDir.absolutePath} ok=$ok")
    } else {
      appendLog("Using splat dir: ${splatDir.absolutePath}")
    }

    val files =
        splatDir
            .listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".ply", true) || it.name.endsWith(".spz", true)) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    val items =
        files.map {
          SplatItem(
              displayName = it.name,
              path = it.toURI().toString(), // file:///...
          )
        }

    deviceSplatsState.value = items
    appendLog("Device splats found: ${items.size}")
    if (items.isNotEmpty()) {
      appendLog("First: ${items.first().displayName}")
    }
  }

  /**
   * Positions the panel in front of user
   */
  private fun positionPanelInFrontOfUser(distance: Float) {
    val head = headQuery.eval().firstOrNull()
    if (head != null) {
      val headPose = head.getComponent<Transform>().transform
      val forward = headPose.forward()
      forward.y = 0f
      val forwardNormalized = forward.normalize()
      var newPosition = headPose.t + (forwardNormalized * distance)
      newPosition.y = panelHeight
      val lookRotation = Quaternion.lookRotation(forwardNormalized)
      panelEntity.setComponent(Transform(Pose(newPosition, lookRotation)))
      appendLog("Panel snapped in front")
    } else {
      appendLog("No head entity found (panel snap skipped)")
    }
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
        createSimpleComposePanel(R.id.control_panel, ANIMATION_PANEL_WIDTH, ANIMATION_PANEL_HEIGHT) {
          ControlPanel(
              builtInSplats = builtInSplats,
              selectedIndex = selectedIndex,
              deviceSplats = deviceSplatsState.value,
              onRefreshDeviceSplats = { rebuildDeviceSplatList() },
              onLoadSplat = { path ->
                loadSplat(path)
              },
              uiLogLines = uiLogState.value,
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

  private fun appendLog(msg: String) {
    Log.d(TAG, msg)
    val ts = System.currentTimeMillis().toString()
    val line = "$ts  $msg"

    val existing = uiLogState.value
    val updated = (existing + line).takeLast(80)
    uiLogState.value = updated
  }
}

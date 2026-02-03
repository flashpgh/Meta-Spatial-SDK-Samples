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
import androidx.compose.runtime.mutableStateListOf
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

  // ---- UI state
  private val splatListState = mutableStateListOf<String>()
  private val selectedIndexState = mutableStateOf(0)
  private val debugLogState = mutableStateOf(listOf<String>())

  // Bundled
  private val bundledSplats: List<String> = listOf("apk://Menlo Park.spz", "apk://Los Angeles.spz")
  private val splatsSubdirName = "splats"

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

    rebuildSplatList("startup")

    loadGLXF { composition ->
      environmentEntity = composition.getNodeByName("Environment").entity
      val environmentMesh = environmentEntity.getComponent<Mesh>()
      environmentMesh.defaultShaderOverride = SceneMaterial.UNLIT_SHADER
      environmentEntity.setComponent(environmentMesh)

      floorEntity = composition.getNodeByName("Floor").entity

      val initial = splatListState.firstOrNull() ?: bundledSplats.first()
      appendLog("initializeSplat: $initial")
      initializeSplat(initial.toUri())
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
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
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
  }

  private fun initializeSplat(splatPath: Uri) {
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
      appendLog("Splat loaded EVENT!")
      onSplatLoaded()
    }
  }

  private fun onSplatLoaded() {
    recenterScene()
    setSplatVisibility(true)
  }

  fun loadSplat(newSplatPath: String) {
    appendLog("loadSplat: $newSplatPath")
    if (splatEntity.hasComponent<Splat>()) {
      val current = splatEntity.getComponent<Splat>().path.toString()
      if (current == newSplatPath) {
        appendLog("loadSplat: already active, skipping reload")
        return
      }
      splatEntity.setComponent(Splat(newSplatPath.toUri()))
      setSplatVisibility(false)
    } else {
      splatEntity.setComponent(Splat(newSplatPath.toUri()))
      setSplatVisibility(false)
    }
  }

  fun setSplatVisibility(isSplatVisible: Boolean) {
    splatEntity.setComponent(Visible(isSplatVisible))
    setEnvironmentVisiblity(!isSplatVisible)
  }

  fun setEnvironmentVisiblity(isVisible: Boolean) {
    environmentEntity.setComponent(Visible(isVisible))
    skyboxEntity.setComponent(Visible(isVisible))
  }

  fun recenterScene() {
    val current = splatEntity.getComponent<Splat>().path.toString()
    var z = laxZ
    if (current.contains("Menlo Park", ignoreCase = true)) z = mpkZ

    scene.setViewOrigin(0f, 0f, z, 0f)
    panelEntity.setComponent(
        Transform(Pose(Vector3(0f, panelHeight, z - panelOffset), Quaternion(0f, 180f, 0f)))
    )
  }

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

  private fun getExternalSplatsDir(): File {
    val base = getExternalFilesDir(null)
    return File(base, splatsSubdirName)
  }

  private fun listExternalSplats(): List<String> {
    val dir = getExternalSplatsDir()
    if (!dir.exists()) {
      appendLog("external dir missing: ${dir.absolutePath}")
      return emptyList()
    }

    val files =
        dir.listFiles { f ->
          val n = f.name.lowercase()
          f.isFile && (n.endsWith(".spz") || n.endsWith(".ply"))
        }?.sortedBy { it.name.lowercase() } ?: emptyList()

    appendLog("external: ${files.size} file(s) in ${dir.absolutePath}")
    for (f in files) appendLog("  - ${f.name}")

    return files.map { it.toUri().toString() }
  }

  fun rebuildSplatList(reason: String) {
    appendLog("rebuildSplatList($reason)")
    val external = listExternalSplats()
    val all = bundledSplats + external

    splatListState.clear()
    splatListState.addAll(all)

    if (selectedIndexState.value >= splatListState.size) selectedIndexState.value = 0
    appendLog("list: bundled=${bundledSplats.size} external=${external.size} total=${all.size}")
  }

  private fun appendLog(msg: String) {
    Log.d("SplatManager", msg)
    val now = (System.currentTimeMillis() % 100000).toString().padStart(5, '0')
    val line = "$now | $msg"
    debugLogState.value = (debugLogState.value + line).takeLast(40)
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
              splatList = splatListState,
              selectedIndex = selectedIndexState,
              debugLines = debugLogState,
              onRescan = { rebuildSplatList("user_rescan") },
              onLoadSplat = { uriString ->
                val idx = splatListState.indexOf(uriString)
                if (idx >= 0) selectedIndexState.value = idx
                loadSplat(uriString)
              },
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
      content: @Composable () -> Unit,
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

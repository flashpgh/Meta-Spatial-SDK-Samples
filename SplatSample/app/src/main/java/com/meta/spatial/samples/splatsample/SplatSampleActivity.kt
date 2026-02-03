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
  // Entity that holds the Splat component for rendering Gaussian Splats
  private lateinit var splatEntity: Entity

  // Dynamic list of splats discovered in assets/ (apk://<filename>)
  private val splatListState = mutableStateOf<List<String>>(emptyList())
  private var selectedIndex = mutableStateOf(0)

  // Used for the initial load and for distance heuristics (optional)
  private var defaultSplatPath: Uri? = null

  private val delayVisibilityMS = 2000L

  // Rotation applied to the Splat to align it with the scene coordinate system
  // -90 degrees on X axis converts from original Splat coordinate space to Spatial SDK space
  private val eulerRotation = Vector3(-90f, 0f, 0f)

  private val panelHeight = 1.5f
  private val panelOffset = 2.5f

  // Default camera Z distance
  private val defaultZ = 4f

  // Optional per-splat Z overrides (keep your existing behavior for those legacy names)
  private val zOverridesByName: Map<String, Float> =
      mapOf(
          "Menlo Park.spz" to 2.5f,
          "Los Angeles.spz" to 4f,
      )

  private val headQuery =
      Query.where { has(AvatarAttachment.id) }
          .filter { isLocal() and by(AvatarAttachment.typeData).isEqualTo("head") }

  // Register all features your app needs. Features add capabilities to the Spatial SDK.
  override fun registerFeatures(): List<SpatialFeature> {
    return listOf(
        VRFeature(this), // Enable VR rendering
        // SplatFeature: REQUIRED for rendering Gaussian Splats
        // This feature handles loading, decoding, and rendering .spz Splat files
        // Must be registered before creating any entities with Splat components
        SplatFeature(this.spatialContext, systemManager),
        ComposeFeature(), // Enable Compose UI panels
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Build the dynamic list FIRST so we can load the first entry.
    splatListState.value = discoverBundledSplats()
    selectedIndex.value = 0
    defaultSplatPath = splatListState.value.firstOrNull()?.toUri()

    NetworkedAssetLoader.init(
        File(applicationContext.getCacheDir().canonicalPath),
        OkHttpAssetFetcher(),
    )

    loadGLXF { composition ->
      environmentEntity = composition.getNodeByName("Environment").entity
      val environmentMesh = environmentEntity.getComponent<Mesh>()
      environmentMesh.defaultShaderOverride = SceneMaterial.UNLIT_SHADER
      environmentEntity.setComponent(environmentMesh)

      floorEntity = composition.getNodeByName("Floor").entity

      // Initialize the splat entity even if the list is empty (so the app doesn't crash)
      val initial = defaultSplatPath
      if (initial != null) {
        initializeSplat(initial)
        // Make the Splat visible in the scene
        setSplatVisibility(true)
      } else {
        Log.e("SplatManager", "No .spz/.ply files found in assets/. Add splats to app/src/main/assets/")
        // Still show environment so the app is usable
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
   * Returns a list of bundled splat paths (apk://...) by scanning app/src/main/assets/.
   * Includes *.spz and *.ply only.
   */
  private fun discoverBundledSplats(): List<String> {
    return try {
      val names = applicationContext.assets.list("")?.toList().orEmpty()
      names
          .filter { it.endsWith(".spz", ignoreCase = true) || it.endsWith(".ply", ignoreCase = true) }
          .sortedWith(String.CASE_INSENSITIVE_ORDER)
          .map { "apk://$it" }
    } catch (t: Throwable) {
      Log.e("SplatManager", "Failed to enumerate assets", t)
      emptyList()
    }
  }

  /**
   * Creates an entity with a Splat component.
   *
   * Splat files (.ply or .spz) can be loaded from:
   * - Application assets: "apk://filename.spz"
   * - Network URLs: "https://example.com/splat.spz"
   * - Local files: "file:///path/to/splat.spz"
   */
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
      Log.d("SplatManager", "Splat loaded EVENT!")
      onSplatLoaded()
    }
  }

  private fun onSplatLoaded() {
    recenterScene()
    setSplatVisibility(true)
  }

  /**
   * Loads a new Splat asset into the scene.
   *
   * @param newSplatPath Path to the .spz/.ply file
   */
  fun loadSplat(newSplatPath: String) {
    // Guard: if list is empty or not initialized, do nothing
    if (!::splatEntity.isInitialized) {
      Log.w("SplatManager", "Splat entity not initialized yet; ignoring loadSplat($newSplatPath)")
      return
    }

    if (splatEntity.hasComponent<Splat>()) {
      val splatComponent = splatEntity.getComponent<Splat>()
      if (splatComponent.path.toString() == newSplatPath) {
        // Already showing this splat; do nothing
        return
      }

      // Swap splat, hide until load completes
      splatEntity.setComponent(Splat(newSplatPath.toUri()))
      setSplatVisibility(false)
      recenterScene()
    } else {
      splatEntity.setComponent(Splat(newSplatPath.toUri()))
      setSplatVisibility(false)
      recenterScene()
    }
  }

  /**
   * Controls the visibility of the Splat in the scene.
   */
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
    val current = if (::splatEntity.isInitialized && splatEntity.hasComponent<Splat>()) {
      splatEntity.getComponent<Splat>().path.toString()
    } else {
      defaultSplatPath?.toString()
    }

    val filename = current?.removePrefix("apk://") ?: ""
    val z = zOverridesByName[filename] ?: defaultZ

    scene.setViewOrigin(0f, 0f, z, 0f)
    panelEntity.setComponent(
        Transform(Pose(Vector3(0f, panelHeight, z - panelOffset), Quaternion(0f, 180f, 0f)))
    )
  }

  /**
   * Positions the panel in front of the user's head.
   */
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

  /**
   * System that listens for controller button presses and performs actions.
   */
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
  override fun registerPanels(): List<com.meta.spatial.toolkit.PanelRegistration> {
    return listOf(
        createSimpleComposePanel(
            R.id.control_panel,
            ANIMATION_PANEL_WIDTH,
            ANIMATION_PANEL_HEIGHT,
        ) {
          // Now uses the dynamic splat list from assets/
          ControlPanel(splatListState.value, selectedIndex, ::loadSplat)
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

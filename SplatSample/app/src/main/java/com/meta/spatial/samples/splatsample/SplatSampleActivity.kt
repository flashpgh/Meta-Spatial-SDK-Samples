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
import android.view.InputDevice
import android.view.MotionEvent
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
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.VRFeature
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
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
  private val debugLogState = mutableStateOf<List<String>>(emptyList())
  private val externalFolderPathState = mutableStateOf("(initializing...)")

  private var defaultSplatPath: Uri? = null

  // Ensure models stand upright
  private val eulerRotation = Vector3(0f, 0f, 0f)

  private val panelHeight = 1.5f
  private val panelOffset = 1.5f
  private val defaultZ = 2f

  // Flight State
  private var flightX = 0f
  private var flightY = 0f
  private var flightZ = defaultZ
  private var flightYaw = 0f

  // Input State (Generic Motion Event)
  private var leftStickX = 0f
  private var leftStickY = 0f
  private var rightStickX = 0f
  private var rightStickY = 0f

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

    externalSplatsDir = initExternalSplatsDir()
    externalFolderPathState.value = externalSplatsDir?.absolutePath ?: "(unavailable)"
    appendLog("external folder: ${externalFolderPathState.value}")

    rebuildSplatList(reason = "startup")
    selectedIndex.value = 0
    defaultSplatPath = splatListState.value.firstOrNull()?.toUri()

    loadGLXF { composition ->
      environmentEntity = composition.getNodeByName("Environment").entity
      val environmentMesh = environmentEntity.getComponent<Mesh>()
      environmentMesh.defaultShaderOverride = SceneMaterial.UNLIT_SHADER
      environmentEntity.setComponent(environmentMesh)

      floorEntity = composition.getNodeByName("Floor").entity
      updateViewOrigin()

      val initial = defaultSplatPath
      if (initial != null) {
        appendLog("initial splat: $initial")
        initializeSplat(initial)
        setSplatVisibility(true)
      } else {
        appendLog("No splats found. Push .ply/.spz to files/splats.")
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
    updateViewOrigin()

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
    systemManager.registerSystem(DroneFlightSystem())
  }

  // --- DRONE INPUT HANDLER (Standard Android API) ---
  override fun onGenericMotionEvent(event: MotionEvent): Boolean {
    if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
        event.action == MotionEvent.ACTION_MOVE) {

        // Quest Controller Mapping on Android:
        // Left Stick: AXIS_X, AXIS_Y
        // Right Stick: AXIS_Z, AXIS_RZ (or sometimes RX/RY depending on OS version)
        
        leftStickX = event.getAxisValue(MotionEvent.AXIS_X)
        leftStickY = event.getAxisValue(MotionEvent.AXIS_Y)
        
        // Check multiple axes for Right Stick to be safe
        val rz = event.getAxisValue(MotionEvent.AXIS_RZ)
        val ry = event.getAxisValue(MotionEvent.AXIS_RY)
        val z = event.getAxisValue(MotionEvent.AXIS_Z)
        val rx = event.getAxisValue(MotionEvent.AXIS_RX)

        // Prioritize RZ/Z for standard mapping
        rightStickY = if (Math.abs(rz) > 0.1f) rz else ry
        rightStickX = if (Math.abs(z) > 0.1f) z else rx
        
        return true
    }
    return super.onGenericMotionEvent(event)
  }

  inner class DroneFlightSystem : SystemBase() {
      private val moveSpeed = 0.05f
      private val turnSpeed = 1.5f
      private val deadzone = 0.1f

      override fun execute() {
          var hasInput = false

          // Mode 2 Flight:
          // Left Stick Y (Up/Down) -> Altitude
          // Left Stick X (Left/Right) -> Yaw
          // Right Stick Y (Up/Down) -> Forward/Back (Pitch)
          // Right Stick X (Left/Right) -> Strafe (Roll)

          // Throttle (Altitude) - Invert Y so Up is Up
          val throttle = -leftStickY
          val yawInput = -leftStickX

          if (Math.abs(throttle) > deadzone) {
              flightY += throttle * moveSpeed
              hasInput = true
          }
          if (Math.abs(yawInput) > deadzone) {
              flightYaw += yawInput * turnSpeed
              hasInput = true
          }

          // Planar Movement
          val pitch = -rightStickY
          val roll = rightStickX

          if (Math.abs(pitch) > deadzone || Math.abs(roll) > deadzone) {
              val rads = Math.toRadians(flightYaw.toDouble())
              val cosY = cos(rads).toFloat()
              val sinY = sin(rads).toFloat()

              val fwdX = sinY
              val fwdZ = -cosY
              val rightX = cosY
              val rightZ = sinY

              val dX = (fwdX * pitch) + (rightX * roll)
              val dZ = (fwdZ * pitch) + (rightZ * roll)

              flightX += dX * moveSpeed
              flightZ += dZ * moveSpeed
              hasInput = true
          }

          if (hasInput) {
              updateViewOrigin()
          }
      }
  }

  fun recenterScene() {
    flightX = 0f
    flightY = 0f
    flightZ = defaultZ
    flightYaw = 0f
    updateViewOrigin()
    
    panelEntity.setComponent(
        Transform(Pose(Vector3(0f, panelHeight, flightZ - panelOffset), Quaternion(0f, 180f, 0f)))
    )
  }

  private fun updateViewOrigin() {
      scene.setViewOrigin(flightX, flightY, flightZ, flightYaw)
  }

  // --- STANDARD HELPERS ---

  fun rescanSplats() {
    appendLog("Rescan pressed")
    rebuildSplatList("user_rescan")
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
    // Ignore bundled to keep it clean
    val bundled = emptyList<String>() 
    val external = discoverExternalSplats()
    val combined = (bundled + external).distinct()
    splatListState.value = combined
    appendLog("rebuildSplatList($reason): found ${combined.size} files")
  }

  private fun initExternalSplatsDir(): File? {
    val base = getExternalFilesDir(null)
    if (base == null) return null
    // Use root 'files' dir to match your ADB script
    return base
  }

  private fun discoverExternalSplats(): List<String> {
    val dir = externalSplatsDir ?: return emptyList()
    val files =
        dir.listFiles()?.toList().orEmpty().filter {
          it.isFile && (it.name.endsWith(".spz", true) || it.name.endsWith(".ply", true))
        }
    return files.sortedBy { it.name.lowercase() }.map { it.toUri().toString() }
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
    if (!::splatEntity.isInitialized) return

    if (splatEntity.hasComponent<Splat>()) {
      if (splatEntity.getComponent<Splat>().path.toString() == newSplatPath) return
    }
    
    splatEntity.setComponent(Splat(newSplatPath.toUri()))
    setSplatVisibility(false)
    recenterScene()
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

        if ((controller.changedButtons and ButtonBits.ButtonA) != 0 &&
            (controller.buttonState and ButtonBits.ButtonA) != 0) {
          positionPanelInFrontOfUser(panelOffset)
        }
        if ((controller.changedButtons and ButtonBits.ButtonB) != 0 &&
            (controller.buttonState and ButtonBits.ButtonB) != 0) {
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

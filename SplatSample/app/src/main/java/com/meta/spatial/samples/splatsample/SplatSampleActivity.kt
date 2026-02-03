/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.splatsample

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import kotlinx.coroutines.delay
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

  // [FIX] Default rotation -90 to fix "Upside Down" PLY files
  private var currentRotationX = -90f 
  private var currentScale = 1.0f

  private val panelHeight = 1.3f
  private val panelOffset = 1.0f
  private val defaultZ = 0f // Start at 0 so we aren't "under" if the model is huge

  // Flight State
  private var flightX = 0f
  private var flightY = 1.7f // Start at eye level
  private var flightZ = 2.0f // Start back a bit
  private var flightYaw = 0f

  // Input State
  private var leftStickX = 0f
  private var leftStickY = 0f
  private var rightStickX = 0f
  private var rightStickY = 0f

  private val splatsPublicFolder = "Splats"
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
    
    if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 1001)
    }

    NetworkedAssetLoader.init(
        File(applicationContext.cacheDir.canonicalPath),
        OkHttpAssetFetcher(),
    )

    externalSplatsDir = initExternalSplatsDir()
    externalFolderPathState.value = externalSplatsDir?.absolutePath ?: "(unavailable)"

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
        initializeSplat(initial)
        setSplatVisibility(true)
      } else {
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

    // [FIX] Force panel to snap to user after a brief delay to ensure tracking is live
    activityScope.launch {
        delay(1500)
        recenterPanel()
        appendLog("Panel recentered automatically.")
    }
  }

  // [FIX] Aggressive Input Handling to stop "Stepping"
  override fun onGenericMotionEvent(event: MotionEvent): Boolean {
      // Capture ALL joystick events. 
      // If we don't return true, the system does "Snap Turn" (stepping).
      if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
        
        // Map axes
        leftStickX = event.getAxisValue(MotionEvent.AXIS_X)
        leftStickY = event.getAxisValue(MotionEvent.AXIS_Y)
        
        val rz = event.getAxisValue(MotionEvent.AXIS_RZ)
        val ry = event.getAxisValue(MotionEvent.AXIS_RY)
        val z = event.getAxisValue(MotionEvent.AXIS_Z)
        val rx = event.getAxisValue(MotionEvent.AXIS_RX)

        // Try to find the active right stick axis
        rightStickY = if (Math.abs(rz) > 0.1f) rz else ry
        rightStickX = if (Math.abs(z) > 0.1f) z else rx

        // ALWAYS return true if it's a joystick event to consume it.
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

          // Drone Mode 2
          // Left Stick: Y=Altitude, X=Yaw
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

          // Right Stick: Y=Pitch(Fwd/Back), X=Roll(Strafe)
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

  // [FIX] Manual rotation toggle
  fun rotateSplat() {
      if (!::splatEntity.isInitialized) return
      currentRotationX += 90f
      if (currentRotationX >= 360f) currentRotationX = 0f
      
      updateSplatTransform()
      appendLog("Rotated to X: $currentRotationX")
  }

  fun updateSplatTransform() {
      if (!::splatEntity.isInitialized) return
      val t = splatEntity.getComponent<Transform>()
      
      // We keep position 0,0,0. We only rotate X.
      // Quaternion.eulerAngles is (pitch, yaw, roll) usually
      val q = Quaternion.fromEuler(Vector3(currentRotationX, 0f, 0f))
      
      splatEntity.setComponent(Transform(Pose(Vector3(0f,0f,0f), q)))
      splatEntity.setComponent(Scale(Vector3(currentScale)))
  }

  fun recenterPanel() {
      positionPanelInFrontOfUser(panelOffset)
  }

  fun resetFlight() {
    flightX = 0f
    flightY = 1.7f // Eye height
    flightZ = 2.0f
    flightYaw = 0f
    updateViewOrigin()
    recenterPanel()
  }

  private fun updateViewOrigin() {
      scene.setViewOrigin(flightX, flightY, flightZ, flightYaw)
  }

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
    val bundled = emptyList<String>() 
    val external = discoverExternalSplats()
    val combined = (bundled + external).distinct()
    splatListState.value = combined
    appendLog("Found ${combined.size} files")
  }

  private fun initExternalSplatsDir(): File? {
    val publicDir = File(Environment.getExternalStorageDirectory(), splatsPublicFolder)
    if (!publicDir.exists()) {
        publicDir.mkdirs()
    }
    return publicDir
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
    appendLog("init: $splatPath")
    splatEntity =
        Entity.create(
            listOf(
                Splat(splatPath),
                Transform(Pose(Vector3.Zero, Quaternion.Identity)), // Will update in updateSplatTransform
                Scale(Vector3(currentScale)),
            )
        )
    
    // Apply default rotation
    updateSplatTransform()

    splatEntity.registerEventListener<SplatLoadEventArgs>(SplatLoadEventArgs.EVENT_NAME) { _, _ ->
      appendLog("Splat Loaded")
      onSplatLoaded()
    }
  }

  private fun onSplatLoaded() {
    setSplatVisibility(true)
    // Don't reset flight here, just ensure panel is visible
    recenterPanel()
  }

  fun loadSplat(newSplatPath: String) {
    if (!::splatEntity.isInitialized) return
    splatEntity.setComponent(Splat(newSplatPath.toUri()))
    setSplatVisibility(false)
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
    
    // Get head forward vector, flatten Y so panel stays vertical
    val forward = headPose.forward()
    forward.y = 0f
    val forwardNormalized = forward.normalize()
    
    // Position: Head position + (Forward * distance)
    var newPosition = headPose.t + (forwardNormalized * distance)
    newPosition.y = panelHeight // Force height
    
    // Rotation: Look at the forward direction
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
          recenterPanel()
        }
        if ((controller.changedButtons and ButtonBits.ButtonB) != 0 &&
            (controller.buttonState and ButtonBits.ButtonB) != 0) {
          resetFlight()
        }
      }
    }
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  override fun registerPanels(): List<PanelRegistration> {
    return listOf(
        createSimpleComposePanel(
            R.id.control_panel,
            2.0f,
            1.25f,
        ) {
          ControlPanel(
              splatList = splatListState.value,
              selectedIndex = selectedIndex,
              loadSplatFunction = ::loadSplat,
              rescanFunction = ::rescanSplats,
              rotateFunction = ::rotateSplat, // NEW
              debugLogLines = debugLogState.value,
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

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.splatsample

import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
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
import com.meta.spatial.toolkit.SupportsLocomotion
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.VRFeature
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(SpatialSDKExperimentalSplatAPI::class)
class SplatSampleActivity : AppSystemActivity() {

  // ===== Existing sample plumbing =====
  private var gltfxEntity: Entity? = null
  private val activityScope = CoroutineScope(Dispatchers.Main)

  private lateinit var environmentEntity: Entity
  private lateinit var skyboxEntity: Entity
  private lateinit var panelEntity: Entity
  private lateinit var floorEntity: Entity
  private lateinit var splatEntity: Entity

  // Keep the built-in samples so you can sanity check
  private val bundledSplatList: List<String> = listOf("apk://Menlo Park.spz", "apk://Los Angeles.spz")
  private var selectedIndex = mutableStateOf(0)
  private val defaultSplatPath = bundledSplatList[0].toUri()

  private val panelHeight = 1.5f
  private val panelOffset = 2.5f

  private val headQuery =
    Query.where { has(AvatarAttachment.id) }
      .filter { isLocal() and by(AvatarAttachment.typeData).isEqualTo("head") }

  // ===== Drone/orbit navigation state (NEW) =====

  // Left stick: strafe/forward
  @Volatile private var lx = 0f
  @Volatile private var ly = 0f

  // Right stick: yaw/pitch
  @Volatile private var rx = 0f
  @Volatile private var ry = 0f

  // Triggers: up/down (rt - lt)
  @Volatile private var lt = 0f
  @Volatile private var rt = 0f

  // Camera/origin state
  private var camPos = Vector3(0f, 0f, 2.5f)
  private var camYawDeg = 0f
  private var camPitchDeg = 0f

  private var lastTickNs: Long = 0L

  // Tuning
  private val deadzone = 0.15f
  private val moveSpeedMetersPerSec = 2.2f
  private val verticalSpeedMetersPerSec = 1.6f
  private val yawSpeedDegPerSec = 110f
  private val pitchSpeedDegPerSec = 95f
  private val minPitch = -80f
  private val maxPitch = 80f

  // Orbit mode
  private var orbitEnabled = mutableStateOf(false)
  private var orbitRadius = 2.8f
  private var orbitCenter = Vector3(0f, 0.8f, 0f) // will be updated after splat loads
  private var orbitYawDeg = 0f
  private var orbitPitchDeg = -10f

  private fun dz(v: Float): Float = if (abs(v) < deadzone) 0f else v

  private fun logOnce(tag: String, msg: String) {
    // keep log minimal; feel free to remove
    Log.i(tag, msg)
  }

  // ===== Feature registration =====
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

    loadGLXF { composition ->
      environmentEntity = composition.getNodeByName("Environment").entity
      val environmentMesh = environmentEntity.getComponent<Mesh>()
      environmentMesh.defaultShaderOverride = SceneMaterial.UNLIT_SHADER
      environmentEntity.setComponent(environmentMesh)

      floorEntity = composition.getNodeByName("Floor").entity

      // Create splat entity and show default bundled sample
      initializeSplat(defaultSplatPath)
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

    // Initialize origin (you will fly from here)
    camPos = Vector3(0f, 0f, 2.5f)
    camYawDeg = 0f
    camPitchDeg = 0f
    applyCamera()

    skyboxEntity =
      Entity.create(
        listOf(
          Mesh(android.net.Uri.parse("mesh://skybox"), hittable = MeshCollision.NoCollision),
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
    systemManager.registerSystem(DroneLocomotionSystem())

    logOnce("Nav", "Drone controls enabled: LeftStick move, RightStick look, Triggers up/down, RStickClick orbit toggle")
  }

  // ===== Splat creation / switching =====

  private fun initializeSplat(splatPath: android.net.Uri) {
    splatEntity =
      Entity.create(
        listOf(
          Splat(splatPath),
          Transform(Pose(Vector3(0.0f, 0.0f, 0.0f), Quaternion(-90f, 0f, 0f))),
          Scale(Vector3(1f)),
          SupportsLocomotion(),
          // optional: allows you to grab/move the splat if you want
          Grabbable(type = GrabbableType.PIVOT_Y),
        )
      )

    splatEntity.registerEventListener<SplatLoadEventArgs>(SplatLoadEventArgs.EVENT_NAME) { _, _ ->
      Log.d("SplatManager", "Splat loaded")
      onSplatLoaded()
    }
  }

  private fun onSplatLoaded() {
    // After load, set a reasonable orbit center (you can refine later).
    orbitCenter = Vector3(0f, 0.8f, 0f)
    // Start orbit yaw aligned with current camera yaw
    orbitYawDeg = camYawDeg
    orbitPitchDeg = -10f
    setSplatVisibility(true)
  }

  fun loadSplat(newSplatPath: String) {
    if (splatEntity.hasComponent<Splat>()) {
      val splatComponent = splatEntity.getComponent<Splat>()
      if (splatComponent.path.toString() == newSplatPath) return
      splatEntity.setComponent(Splat(newSplatPath.toUri()))
      setSplatVisibility(false)
    } else {
      splatEntity.setComponent(Splat(newSplatPath.toUri()))
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

  // ===== Camera helpers (NEW) =====

  private fun applyCamera() {
    // Spatial SDK uses setViewOrigin(x,y,z,yawDegrees)
    scene.setViewOrigin(camPos.x, camPos.y, camPos.z, camYawDeg)

    // Keep panel in front-ish (optional). Comment out if annoying.
    val zForPanel = camPos.z
    panelEntity.setComponent(
      Transform(Pose(Vector3(camPos.x, panelHeight, zForPanel - panelOffset), Quaternion(0f, 180f, 0f)))
    )
  }

  private fun forwardFromYawPitch(yawDeg: Float, pitchDeg: Float): Vector3 {
    val yaw = Math.toRadians(yawDeg.toDouble())
    val pitch = Math.toRadians(pitchDeg.toDouble())
    val cy = cos(yaw)
    val sy = sin(yaw)
    val cp = cos(pitch)
    val sp = sin(pitch)
    // Right-handed-ish: z forward is -? In practice, this "feels right" with setViewOrigin yaw
    return Vector3((sy * cp).toFloat(), (-sp).toFloat(), (cy * cp).toFloat())
  }

  private fun rightFromYaw(yawDeg: Float): Vector3 {
    val yaw = Math.toRadians(yawDeg.toDouble())
    val cy = cos(yaw)
    val sy = sin(yaw)
    return Vector3(cy.toFloat(), 0f, (-sy).toFloat())
  }

  // ===== Input: read analog sticks via Android MotionEvent (NEW) =====

  override fun onGenericMotionEvent(event: MotionEvent): Boolean {
    val src = event.source
    if ((src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
      event.action == MotionEvent.ACTION_MOVE
    ) {
      // Left stick
      lx = dz(event.getAxisValue(MotionEvent.AXIS_X))
      ly = dz(event.getAxisValue(MotionEvent.AXIS_Y))

      // Right stick (common on gamepads/controllers)
      rx = dz(event.getAxisValue(MotionEvent.AXIS_RX))
      ry = dz(event.getAxisValue(MotionEvent.AXIS_RY))

      // Triggers: try the dedicated axes first, then fall back
      val ltr = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
      val rtr = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
      if (ltr != 0f || rtr != 0f) {
        lt = min(max(ltr, 0f), 1f)
        rt = min(max(rtr, 0f), 1f)
      } else {
        // Some devices map triggers to Z/RZ
        val z = event.getAxisValue(MotionEvent.AXIS_Z)
        val rz = event.getAxisValue(MotionEvent.AXIS_RZ)
        lt = min(max((z + 1f) * 0.5f, 0f), 1f)
        rt = min(max((rz + 1f) * 0.5f, 0f), 1f)
      }
      return true
    }
    return super.onGenericMotionEvent(event)
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    // Toggle orbit with right stick click (BUTTON_THUMBR)
    if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR) {
      orbitEnabled.value = !orbitEnabled.value
      logOnce("Nav", "Orbit: ${orbitEnabled.value}")
      // seed orbit angles from current camera
      orbitYawDeg = camYawDeg
      orbitPitchDeg = camPitchDeg
      return true
    }
    return super.onKeyDown(keyCode, event)
  }

  // ===== Systems =====

  inner class DroneLocomotionSystem : SystemBase() {
    override fun execute() {
      val now = System.nanoTime()
      if (lastTickNs == 0L) {
        lastTickNs = now
        return
      }
      val dt = (now - lastTickNs).toFloat() / 1_000_000_000f
      lastTickNs = now

      // Update yaw/pitch from right stick
      camYawDeg += rx * yawSpeedDegPerSec * dt
      camPitchDeg = (camPitchDeg + (-ry) * pitchSpeedDegPerSec * dt).coerceIn(minPitch, maxPitch)

      // Orbit mode: orbit around orbitCenter using yaw/pitch; left stick changes radius; triggers dolly up/down
      if (orbitEnabled.value) {
        orbitYawDeg += rx * yawSpeedDegPerSec * dt
        orbitPitchDeg = (orbitPitchDeg + (-ry) * pitchSpeedDegPerSec * dt).coerceIn(minPitch, maxPitch)

        // Change radius with left stick Y (forward/back) if desired
        orbitRadius = (orbitRadius + (-ly) * 1.2f * dt).coerceIn(0.4f, 20f)

        val fwd = forwardFromYawPitch(orbitYawDeg, orbitPitchDeg)
        // Place camera behind the center along -forward
        camPos = orbitCenter - (fwd * orbitRadius)

        // Vertical adjustment via triggers
        val v = (rt - lt) * verticalSpeedMetersPerSec * dt
        camPos = Vector3(camPos.x, camPos.y + v, camPos.z)

        camYawDeg = orbitYawDeg
        camPitchDeg = orbitPitchDeg

        applyCamera()
        return
      }

      // Drone mode: translate in the camera yaw plane
      val fwdFlat = forwardFromYawPitch(camYawDeg, 0f) // ignore pitch for movement
      val right = rightFromYaw(camYawDeg)
      val move =
        (right * (lx * moveSpeedMetersPerSec * dt)) +
          (fwdFlat * (-ly * moveSpeedMetersPerSec * dt))

      val v = (rt - lt) * verticalSpeedMetersPerSec * dt

      camPos = Vector3(camPos.x + move.x, camPos.y + v, camPos.z + move.z)

      // Optional: don’t go below floor too much
      camPos = Vector3(camPos.x, max(-0.2f, camPos.y), camPos.z)

      applyCamera()
    }
  }

  inner class ControllerListenerSystem : SystemBase() {
    override fun execute() {
      // Keep your existing A/B behavior for panel snap + recenter (digital)
      val controllers = Query.where { has(Controller.id) }.eval().filter { it.isLocal() }

      for (controllerEntity in controllers) {
        val controller = controllerEntity.getComponent<Controller>()
        if (!controller.isActive) continue

        val attachment = controllerEntity.tryGetComponent<AvatarAttachment>()
        if (attachment?.type != "right_controller") continue

        // A button: snap panel in front (as before)
        if (
          (controller.changedButtons and ButtonBits.ButtonA) != 0 &&
          (controller.buttonState and ButtonBits.ButtonA) != 0
        ) {
          positionPanelInFrontOfUser(panelOffset)
        }

        // B button: quick “home” (reset camera)
        if (
          (controller.changedButtons and ButtonBits.ButtonB) != 0 &&
          (controller.buttonState and ButtonBits.ButtonB) != 0
        ) {
          orbitEnabled.value = false
          camPos = Vector3(0f, 0f, 2.5f)
          camYawDeg = 0f
          camPitchDeg = 0f
          applyCamera()
        }
      }
    }
  }

  private fun positionPanelInFrontOfUser(distance: Float) {
    val head = headQuery.eval().firstOrNull() ?: return
    val headPose = head.getComponent<Transform>().transform
    val forward = headPose.forward().apply { y = 0f }
    val forwardNormalized = forward.normalize()
    var newPosition = headPose.t + (forwardNormalized * distance)
    newPosition.y = panelHeight
    val lookRotation = Quaternion.lookRotation(forwardNormalized)
    panelEntity.setComponent(Transform(Pose(newPosition, lookRotation)))
  }

  // ===== Panels =====

  @OptIn(SpatialSDKExperimentalAPI::class)
  override fun registerPanels(): List<PanelRegistration> {
    return listOf(
      createSimpleComposePanel(
        R.id.control_panel,
        ANIMATION_PANEL_WIDTH,
        ANIMATION_PANEL_HEIGHT,
      ) {
        ControlPanel(bundledSplatList, selectedIndex, ::loadSplat)
      },
    )
  }

  private fun loadGLXF(onLoaded: ((GLXFInfo) -> Unit) = {}): Job {
    gltfxEntity = Entity.create()
    return activityScope.launch {
      glXFManager.inflateGLXF(
        android.net.Uri.parse("apk:///scenes/Composition.glxf"),
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

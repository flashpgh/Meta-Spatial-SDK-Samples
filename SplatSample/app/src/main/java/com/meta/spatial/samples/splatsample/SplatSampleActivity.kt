/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.splatsample

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import java.io.FileInputStream
import java.util.Properties
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(SpatialSDKExperimentalSplatAPI::class)
class SplatSampleActivity : AppSystemActivity() {

  // We no longer use gltfxEntity since we aren't loading the scene file
  private val activityScope = CoroutineScope(Dispatchers.Main)

  private lateinit var skyboxEntity: Entity
  private lateinit var panelEntity: Entity
  private lateinit var splatEntity: Entity

  // UI state
  private val splatListState = mutableStateOf<List<String>>(emptyList())
  private val selectedIndex = mutableStateOf(0)
  private val debugLogState = mutableStateOf<List<String>>(emptyList())
  private val externalFolderPathState = mutableStateOf("(initializing...)")

  private var defaultSplatPath: Uri? = null
  private var hasLoggedInput = false 

  // --- CONFIG ---
  private var configMoveSpeed = 0.5f 
  private var configTurnSpeed = 1.5f
  private var configRotationX = 180f 
  private var configScale = 1.0f

  // Dynamic Panel Distance
  private var panelDistance = 2.0f // Start Far
  private var isPanelNear = false
  
  // Flight State
  private var flightX = 0f
  private var flightY = 0f 
  private var flightZ = 0f 
  private var flightYaw = 0f

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
    
    checkAndRequestPermission()

    NetworkedAssetLoader.init(
        File(applicationContext.cacheDir.canonicalPath),
        OkHttpAssetFetcher(),
    )

    externalSplatsDir = initExternalSplatsDir()
    externalFolderPathState.value = externalSplatsDir?.absolutePath ?: "(unavailable)"

    loadExternalConfig()

    rebuildSplatList(reason = "startup")
    selectedIndex.value = 0
    defaultSplatPath = splatListState.value.firstOrNull()?.toUri()

    // [NUCLEAR FIX] We do NOT load loadGLXF anymore. 
    // We manually initialize the "Void" scene in onSceneReady.
  }

  private fun loadExternalConfig() {
      val dir = externalSplatsDir ?: return
      val configFile = File(dir, "config.txt")
      if (configFile.exists()) {
          try {
              val props = Properties()
              FileInputStream(configFile).use { props.load(it) }
              props.getProperty("moveSpeed")?.toFloatOrNull()?.let { configMoveSpeed = it }
              props.getProperty("rotationX")?.toFloatOrNull()?.let { configRotationX = it }
              props.getProperty("scale")?.toFloatOrNull()?.let { configScale = it }
              appendLog("Config: Speed=$configMoveSpeed Rot=$configRotationX")
          } catch (e: Exception) {}
      }
  }

  private fun checkAndRequestPermission() {
      if (Build.VERSION.SDK_INT >= 30) {
          if (!Environment.isExternalStorageManager()) {
              try {
                  val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                  intent.addCategory("android.intent.category.DEFAULT")
                  intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                  startActivity(intent)
              } catch (e: Exception) {
                  val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                  startActivity(intent)
              }
          }
      } else {
          if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
              requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 1001)
          }
      }
  }

  fun requestPermissionFromUI() {
      checkAndRequestPermission()
  }

  @OptIn(SpatialSDKInternalTestingAPI::class)
  override fun onSceneReady() {
    super.onSceneReady()
    registerTestingIntentReceivers()

    // 1. SETUP LIGHTING (Since we deleted the GLXF scene that had it)
    scene.setLightingEnvironment(
        ambientColor = Vector3(0.1f),
        sunColor = Vector3(1.0f, 1.0f, 1.0f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f), // Standard overhead sun
        environmentIntensity = 0.5f,
    )
    
    // 2. SETUP SKYBOX (So it's not pitch black)
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

    // 3. SETUP VIEW
    updateViewOrigin()

    // 4. SETUP PANEL
    panelEntity =
        Entity.createPanelEntity(
            R.id.control_panel,
            Transform(Pose(Vector3(0f, 1.5f, 0f), Quaternion(0f, 180f, 0f))),
            Grabbable(type = GrabbableType.PIVOT_Y, minHeight = 0.5f, maxHeight = 2.5f),
        )

    // 5. LOAD SPLAT (If found)
    val initial = defaultSplatPath
    if (initial != null) {
      initializeSplat(initial)
      setSplatVisibility(true)
    }

    systemManager.registerSystem(ControllerListenerSystem())
    
    // Auto-center panel after 2 seconds
    activityScope.launch {
        delay(2000)
        recenterPanel()
    }
  }

  // [FLIGHT LOGIC - Direct Android Input]
  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
      if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
          event.action == MotionEvent.ACTION_MOVE) {
        
        val leftX = event.getAxisValue(MotionEvent.AXIS_X)
        val leftY = event.getAxisValue(MotionEvent.AXIS_Y)
        
        val rz = event.getAxisValue(MotionEvent.AXIS_RZ)
        val ry = event.getAxisValue(MotionEvent.AXIS_RY)
        val z = event.getAxisValue(MotionEvent.AXIS_Z)
        val rx = event.getAxisValue(MotionEvent.AXIS_RX)
        
        val rightY = if (Math.abs(rz) > 0.1f) rz else ry
        val rightX = if (Math.abs(z) > 0.1f) z else rx

        val rTrigger = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        val gas = event.getAxisValue(MotionEvent.AXIS_GAS)
        val triggerVal = if (rTrigger > gas) rTrigger else gas

        var hasInput = false
        val deadzone = 0.1f
        val rotSpeed = 2.0f

        // 1. LEFT STICK: Altitude (Y) & Yaw (X)
        val throttle = -leftY
        val yawInput = -leftX

        if (Math.abs(throttle) > deadzone) {
            flightY += throttle * configMoveSpeed
            hasInput = true
        }
        if (Math.abs(yawInput) > deadzone) {
            flightYaw += yawInput * configTurnSpeed
            hasInput = true
        }

        // 2. RIGHT STICK
        val stickY = -rightY
        val stickX = rightX

        if (triggerVal > 0.5f) {
            // [MODIFIER] Rotate WORLD Pitch (Look Up/Down)
            if (Math.abs(stickY) > deadzone) {
                configRotationX += stickY * rotSpeed
                updateSplatTransform()
            }
        } else {
            // [NORMAL] Move Plane
            if (Math.abs(stickY) > deadzone || Math.abs(stickX) > deadzone) {
                val rads = Math.toRadians(flightYaw.toDouble())
                val cosY = cos(rads).toFloat()
                val sinY = sin(rads).toFloat()

                val fwdX = sinY
                val fwdZ = -cosY
                val rightX = cosY
                val rightZ = sinY

                val dX = (fwdX * stickY) + (rightX * stickX)
                val dZ = (fwdZ * stickY) + (rightZ * stickX)

                flightX += dX * configMoveSpeed
                flightZ += dZ * configMoveSpeed
                hasInput = true
            }
        }

        if (hasInput) {
            updateViewOrigin()
        }

        return true // Consumed event
    }
    return super.dispatchGenericMotionEvent(event)
  }

  fun rotateSplat() {
      if (!::splatEntity.isInitialized) return
      configRotationX += 90f
      updateSplatTransform()
      appendLog("Rotated to X: $configRotationX")
  }

  fun updateSplatTransform() {
      if (!::splatEntity.isInitialized) return
      val q = Quaternion(configRotationX, 0f, 0f)
      
      splatEntity.setComponent(Transform(Pose(Vector3(0f), q)))
      splatEntity.setComponent(Scale(Vector3(configScale)))
  }

  fun recenterPanel() {
      // Toggle between Near (0.6m) and Far (2.0m)
      positionPanelInFrontOfUser(panelDistance)
  }

  fun togglePanelDistance() {
      isPanelNear = !isPanelNear
      panelDistance = if (isPanelNear) 0.6f else 2.0f
      recenterPanel()
  }

  fun resetFlight() {
    flightX = 0f
    flightY = 0f 
    flightZ = 0f
    flightYaw = 0f
    updateSplatTransform()
    updateViewOrigin()
    recenterPanel()
  }

  private fun updateViewOrigin() {
      scene.setViewOrigin(flightX, flightY, flightZ, flightYaw)
  }

  fun rescanSplats() {
    loadExternalConfig()
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
                Transform(Pose(Vector3(0f), Quaternion(0f, 0f, 0f))), 
                Scale(Vector3(configScale)),
            )
        )
    
    updateSplatTransform()

    splatEntity.registerEventListener<SplatLoadEventArgs>(SplatLoadEventArgs.EVENT_NAME) { _, _ ->
      appendLog("Splat Loaded")
      onSplatLoaded()
    }
  }

  private fun onSplatLoaded() {
    setSplatVisibility(true)
    recenterPanel()
  }

  fun loadSplat(newSplatPath: String) {
    appendLog("Loading: ${newSplatPath.substringAfterLast("/")}")
    val uri = newSplatPath.toUri()
    
    if (!::splatEntity.isInitialized) {
        initializeSplat(uri)
    } else {
        splatEntity.setComponent(Splat(uri))
    }
    setSplatVisibility(false)
  }

  fun setSplatVisibility(isSplatVisible: Boolean) {
    if (!::splatEntity.isInitialized) return
    splatEntity.setComponent(Visible(isSplatVisible))
  }

  fun setEnvironmentVisiblity(isVisible: Boolean) {
    // Stub
  }

  private fun positionPanelInFrontOfUser(distance: Float) {
    val head = headQuery.eval().firstOrNull() ?: return
    val headPose = head.getComponent<Transform>().transform
    
    val forward = headPose.forward()
    forward.y = 0f
    val forwardNormalized = forward.normalize()
    
    var newPosition = headPose.t + (forwardNormalized * distance)
    newPosition.y = headPose.t.y 
    
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

        // A Button: Toggle Distance
        if ((controller.changedButtons and ButtonBits.ButtonA) != 0 &&
            (controller.buttonState and ButtonBits.ButtonA) != 0) {
          togglePanelDistance()
        }
        // B Button: Reset
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
              rotateFunction = ::rotateSplat,
              requestPermissionFunction = ::requestPermissionFromUI, 
              debugLogLines = debugLogState.value,
          )
        },
    )
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

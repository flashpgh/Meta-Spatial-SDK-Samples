/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * SplatControlPanel.kt
 *
 * UI control panel for the Splat sample (Jetpack Compose).
 *
 * This version is intentionally "zero-trust":
 * - It never silently assumes helper functions exist.
 * - It always renders a Debug log panel that explains what it tried to do.
 * - It avoids referencing undefined symbols.
 */
package com.meta.spatial.samples.splatsample

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.darkSpatialColorScheme
import com.meta.spatial.uiset.theme.lightSpatialColorScheme

/**
 * Physical dimensions of the panel in 3D space (in meters).
 */
const val ANIMATION_PANEL_WIDTH = 2.048f
const val ANIMATION_PANEL_HEIGHT = 1.254f

private const val PANEL_TITLE = "Splat Sample"

private val panelInstructionText = buildAnnotatedString {
  append("Press ")
  withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("A") }
  append(" to snap the panel in front of you.\nPress ")
  withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("B") }
  append(" to recenter the view.")
}

/**
 * Primary panel UI.
 *
 * Contract:
 * - `splatList` is a list of paths like "apk://Menlo Park.spz" or "file:///.../foo.ply"
 * - `selectedIndex` is owned by the Activity
 * - `loadSplatFunction` should load the chosen splat by path string
 */
@Composable
fun ControlPanel(
    splatList: List<String>,
    selectedIndex: MutableState<Int>,
    loadSplatFunction: (String) -> Unit,
) {
  val debugLines = remember { mutableStateListOf<String>() }

  fun appendLog(msg: String) {
    // Keep log bounded so it doesn't explode memory
    if (debugLines.size > 200) debugLines.removeAt(0)
    debugLines.add(msg)
  }

  // “Rescan” is UI-only here; you can wire it to real device scanning in Activity later.
  fun rescanClicked() {
    appendLog("Rescan clicked. Current list size=${splatList.size}")
    splatList.forEachIndexed { idx, p ->
      appendLog("[$idx] ${getSplatShortPath(p)}")
    }
  }

  SpatialTheme(colorScheme = getPanelTheme()) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .clip(SpatialTheme.shapes.large)
                .background(brush = LocalColorScheme.current.panel)
                .padding(28.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
          text = PANEL_TITLE,
          style = SpatialTheme.typography.headline1Strong,
          color = LocalColorScheme.current.primaryAlphaBackground,
      )

      Spacer(modifier = Modifier.height(8.dp))

      Text(
          text = panelInstructionText,
          style = SpatialTheme.typography.body1,
          color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.75f),
      )

      Spacer(modifier = Modifier.height(16.dp))

      // Rescan button (styled as a fat clickable row to avoid extra dependencies)
      Row(
          modifier =
              Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(12.dp))
                  .border(
                      width = 2.dp,
                      color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.35f),
                      shape = RoundedCornerShape(12.dp),
                  )
                  .clickable { rescanClicked() }
                  .padding(vertical = 14.dp, horizontal = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
      ) {
        Text(
            text = "Rescan",
            style = SpatialTheme.typography.headline2Strong,
            color = LocalColorScheme.current.primaryAlphaBackground,
        )
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Splat choices
      if (splatList.isEmpty()) {
        appendLog("UI: splatList is empty")
        Text(
            text = "No splats available.",
            style = SpatialTheme.typography.body1,
            color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.75f),
        )
      } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        ) {
          splatList.forEachIndexed { index, option ->
            val isSelected = (index == selectedIndex.value)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              val previewRes = getSplatPreviewResource(option)
              if (previewRes != null) {
                Image(
                    painter = painterResource(id = previewRes),
                    contentDescription = "Preview of ${getSplatDisplayName(option)}",
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = if (isSelected) 4.dp else 2.dp,
                                color =
                                    if (isSelected) Color(0xFF1877F2)
                                    else LocalColorScheme.current.primaryAlphaBackground,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable {
                              appendLog("UI: clicked ${getSplatShortPath(option)}")
                              selectedIndex.value = index
                              loadSplatFunction(option)
                            },
                    contentScale = ContentScale.Crop,
                )
              } else {
                // No preview image: render a simple tile
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = if (isSelected) 4.dp else 2.dp,
                                color =
                                    if (isSelected) Color(0xFF1877F2)
                                    else LocalColorScheme.current.primaryAlphaBackground,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable {
                              appendLog("UI: clicked ${getSplatShortPath(option)} (no preview)")
                              selectedIndex.value = index
                              loadSplatFunction(option)
                            }
                            .padding(12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  Text(
                      text = "No preview",
                      style = SpatialTheme.typography.body1,
                      color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.75f),
                  )
                  Spacer(modifier = Modifier.height(6.dp))
                  Text(
                      text = getSplatDisplayName(option),
                      style = SpatialTheme.typography.headline2Strong,
                      color =
                          if (isSelected) Color(0xFF1877F2)
                          else LocalColorScheme.current.primaryAlphaBackground,
                  )
                }
              }

              Text(
                  text = getSplatDisplayName(option),
                  style = SpatialTheme.typography.headline2Strong,
                  color =
                      if (isSelected) Color(0xFF1877F2)
                      else LocalColorScheme.current.primaryAlphaBackground,
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Debug log (bounded, scrollable)
      Column(
          modifier =
              Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(12.dp))
                  .border(
                      width = 2.dp,
                      color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.25f),
                      shape = RoundedCornerShape(12.dp),
                  )
                  .padding(12.dp)
      ) {
        Text(
            text = "Debug log",
            style = SpatialTheme.typography.headline2Strong,
            color = LocalColorScheme.current.primaryAlphaBackground,
        )

        Spacer(modifier = Modifier.height(8.dp))

        val scroll = rememberScrollState()
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .height(180.dp)
                    .verticalScroll(scroll)
                    .background(Color(0x22000000))
                    .padding(10.dp)
        ) {
          if (debugLines.isEmpty()) {
            Text(
                text = "No debug messages yet.",
                style = SpatialTheme.typography.body2,
                color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.65f),
            )
          } else {
            debugLines.forEach { line ->
              Text(
                  text = line,
                  style = SpatialTheme.typography.body2,
                  color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.75f),
              )
              Spacer(modifier = Modifier.height(4.dp))
            }
          }
        }
      }
    }
  }
}

/**
 * Theming: respect system dark mode.
 */
@Composable
fun getPanelTheme(): SpatialColorScheme =
    if (isSystemInDarkTheme()) darkSpatialColorScheme() else lightSpatialColorScheme()

/**
 * Preview mapping for the two bundled samples.
 * For everything else (device .ply / .spz), returns null.
 */
fun getSplatPreviewResource(splatPath: String): Int? {
  return when {
    splatPath.contains("Menlo Park", ignoreCase = true) -> R.drawable.mpk_room
    splatPath.contains("Los Angeles", ignoreCase = true) -> R.drawable.lax_room
    else -> null
  }
}

/**
 * Human-friendly name.
 * Works for:
 * - apk://Menlo Park.spz
 * - file:///storage/.../mill1.ply
 * - /sdcard/.../foo.ply
 * - https://.../bar.spz
 */
fun getSplatDisplayName(splatPath: String): String {
  val short = getSplatShortPath(splatPath)
  // Strip common extensions
  return short
      .removePrefix("apk://")
      .removePrefix("file://")
      .removeSuffix(".spz")
      .removeSuffix(".ply")
}

/**
 * Returns the last path segment without losing protocol context too much.
 * Example:
 * - "apk://Menlo Park.spz" -> "apk://Menlo Park.spz"
 * - "file:///storage/emulated/0/.../mill1.ply" -> "mill1.ply"
 * - "/sdcard/.../tree1.ply" -> "tree1.ply"
 * - "https://host/path/house_ok.ply" -> "house_ok.ply"
 */
fun getSplatShortPath(splatPath: String): String {
  val trimmed = splatPath.trim()

  // Keep apk:// as-is because it's already "short"
  if (trimmed.startsWith("apk://", ignoreCase = true)) return trimmed

  // Try to extract last segment for URLs / file paths
  val lastSlash = trimmed.lastIndexOf('/')
  return if (lastSlash >= 0 && lastSlash < trimmed.length - 1) {
    trimmed.substring(lastSlash + 1)
  } else {
    trimmed
  }
}

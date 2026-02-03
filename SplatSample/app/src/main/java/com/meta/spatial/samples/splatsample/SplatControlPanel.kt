/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.splatsample

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.components.Button
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.darkSpatialColorScheme
import com.meta.spatial.uiset.theme.lightSpatialColorScheme

const val ANIMATION_PANEL_WIDTH = 2.048f
const val ANIMATION_PANEL_HEIGHT = 1.254f

private val panelHeadingText = "Splat Sample"
private val panelInstructionText = buildAnnotatedString {
  append("Press ")
  withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("A") }
  append(" to snap the panel in front of you. \nPress ")
  withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("B") }
  append(" to recenter the view.\n\n")
  append("External splats live in:\n")
  withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
    append("/Android/data/com.meta.spatial.samples.splatsample/files/splats\n")
  }
  append("Use adb push, then press Rescan.")
}

@Composable
fun ControlPanel(
    splatList: List<String>,
    selectedIndex: MutableState<Int>,
    debugLines: MutableState<List<String>>,
    onRescan: () -> Unit,
    onLoadSplat: (String) -> Unit,
) {
  SpatialTheme(colorScheme = getPanelTheme()) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
          text = panelHeadingText,
          style = SpatialTheme.typography.headline1Strong,
          color = LocalColorScheme.current.primaryAlphaBackground,
      )

      Text(
          text = panelInstructionText,
          style = SpatialTheme.typography.body1,
          color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.75f),
      )

      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Button(onClick = { onRescan() }) { Text("Rescan") }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Found: ${splatList.size}",
            style = SpatialTheme.typography.body2,
            color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.7f),
        )
      }

      // Splat list (click to load)
      Column(
          modifier =
              Modifier.fillMaxWidth()
                  .weight(1f)
                  .border(
                      width = 1.dp,
                      color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.25f),
                      shape = RoundedCornerShape(14.dp),
                  )
                  .padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
            text = "Available splats",
            style = SpatialTheme.typography.headline3Strong,
            color = LocalColorScheme.current.primaryAlphaBackground,
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          itemsIndexed(splatList) { idx, uriString ->
            val isSelected = idx == selectedIndex.value
            val borderColor = if (isSelected) Color(0xFF1877F2) else Color.Transparent

            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .border(2.dp, borderColor, RoundedCornerShape(12.dp))
                        .clickable {
                          selectedIndex.value = idx
                          onLoadSplat(uriString)
                        }
                        .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = getSplatDisplayName(uriString),
                    style = SpatialTheme.typography.headline3Strong,
                    color =
                        if (isSelected) Color(0xFF1877F2)
                        else LocalColorScheme.current.primaryAlphaBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = getSplatShortPath(uriString),
                    style = SpatialTheme.typography.body2,
                    color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.6f),
                )
              }

              Text(
                  text = if (isSelected) "Loaded" else "Tap",
                  style = SpatialTheme.typography.body2Strong,
                  color =
                      if (isSelected) Color(0xFF1877F2)
                      else LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.6f),
              )
            }
          }
        }
      }

      // Debug log (bounded)
      Column(
          modifier =
              Modifier.fillMaxWidth()
                  .height(220.dp)
                  .border(
                      width = 1.dp,
                      color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.25f),
                      shape = RoundedCornerShape(14.dp),
                  )
                  .padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
            text = "Debug",
            style = SpatialTheme.typography.headline3Strong,
            color = LocalColorScheme.current.primaryAlphaBackground,
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          itemsIndexed(debugLines.value) { _, line ->
            Text(
                text = line,
                style = SpatialTheme.typography.body2,
                color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.7f),
            )
          }
        }
      }
    }
  }
}

@Composable
fun getPanelTheme(): SpatialColorScheme =
    if (isSystemInDarkTheme()) darkSpatialColorScheme() else lightSpatialColorScheme()

fun getSplatDisplayName(splatUriString: String): String {
  // Display name from URI string
  // - apk://Menlo Park.spz -> Menlo Park
  // - file:///.../mill1.ply -> mill1
  val s = splatUriString
  val base =
      when {
        s.startsWith("apk://", ignoreCase = true) -> s.removePrefix("apk://")
        s.startsWith("file://", ignoreCase = true) -> s.substringAfterLast("/")
        else -> s.substringAfterLast("/")
      }
  return base.replace(".spz", "", ignoreCase = true).replace(".ply", "", ignoreCase = true)
}

fun getSplatShortPath(splatUriString: String): String {
  return when {
    splatUriString.startsWith("apk://", ignoreCase = true) -> splatUriString
    splatUriString.startsWith("file://", ignoreCase = true) ->
        splatUriString.substringAfter("/Android/data/")
    else -> splatUriString
  }
}

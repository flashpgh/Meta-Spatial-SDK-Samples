/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
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

const val ANIMATION_PANEL_WIDTH = 2.048f
const val ANIMATION_PANEL_HEIGHT = 1.254f

private val panelHeadingText = "Splat Viewer"

private val panelInstructionText = buildAnnotatedString {
  append("Drone: ")
  withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Left stick") }
  append(" move, ")
  withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Right stick") }
  append(" look, ")
  withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Triggers") }
  append(" up/down.\nOrbit: click ")
  withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Right stick") }
  append(" to toggle.\n")
  append("A snaps panel in front. B resets view.")
}

@Composable
fun ControlPanel(
  splatList: List<String>,
  selectedIndex: MutableState<Int>,
  loadSplatFunction: (String) -> Unit,
) {
  SpatialTheme(colorScheme = getPanelTheme()) {
    Column(
      modifier =
        Modifier.fillMaxSize()
          .clip(SpatialTheme.shapes.large)
          .background(brush = LocalColorScheme.current.panel)
          .padding(36.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
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
          color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.7f),
          modifier = Modifier.padding(top = 8.dp),
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        ) {
          splatList.forEachIndexed { index, option ->
            val isSelected = (index == selectedIndex.value)
            Column(
              modifier = Modifier.weight(1f),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              val previewResource = getSplatPreviewResource(option)
              if (previewResource != null) {
                Image(
                  painter = painterResource(id = previewResource),
                  contentDescription = "Preview of $option",
                  modifier =
                    Modifier.fillMaxWidth()
                      .height(200.dp)
                      .clip(RoundedCornerShape(12.dp))
                      .border(
                        width = if (isSelected) 4.dp else 2.dp,
                        color = if (isSelected) Color(0xFF1877F2)
                        else LocalColorScheme.current.primaryAlphaBackground,
                        shape = RoundedCornerShape(12.dp),
                      )
                      .clickable {
                        loadSplatFunction(option)
                        selectedIndex.value = index
                      },
                  contentScale = ContentScale.Crop,
                )
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
    }
  }
}

@Composable
fun getPanelTheme(): SpatialColorScheme =
  if (isSystemInDarkTheme()) darkSpatialColorScheme() else lightSpatialColorScheme()

fun getSplatPreviewResource(splatPath: String): Int? {
  return when {
    splatPath.contains("Menlo Park", ignoreCase = true) -> R.drawable.mpk_room
    splatPath.contains("Los Angeles", ignoreCase = true) -> R.drawable.lax_room
    else -> null
  }
}

fun getSplatDisplayName(splatPath: String): String {
  return splatPath.replace("apk://", "").replace(".spz", "")
}

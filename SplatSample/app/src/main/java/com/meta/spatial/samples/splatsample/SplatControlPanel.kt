/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.splatsample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

private val panelHeadingText = "Splat Player"
private val panelInstructionText = buildAnnotatedString {
  append("Left Stick: Altitude & Yaw\n")
  append("Right Stick: Move\n")
  append("Press ")
  withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("A") }
  append(" to snap panel. ")
  withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("B") }
  append(" to reset.")
}

private val selectedBlue = Color(0xFF1877F2)

@Composable
fun ControlPanel(
    splatList: List<String>,
    selectedIndex: MutableState<Int>,
    loadSplatFunction: (String) -> Unit,
    rescanFunction: () -> Unit,
    debugLogLines: List<String>,
    externalFolderPath: String,
) {
  SpatialTheme(colorScheme = getPanelTheme()) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .clip(SpatialTheme.shapes.large)
                .background(brush = LocalColorScheme.current.panel)
                .padding(22.dp),
        verticalArrangement = Arrangement.Top,
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

      Spacer(modifier = Modifier.height(10.dp))

      Text(
          text = "Folder: .../files/",
          style = SpatialTheme.typography.body1,
          color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.7f),
      )

      Spacer(modifier = Modifier.height(12.dp))

      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
      ) {
        ActionButton(text = "Rescan Files", onClick = rescanFunction, modifier = Modifier.weight(1f))
      }

      Spacer(modifier = Modifier.height(12.dp))

      if (splatList.isEmpty()) {
        EmptyState()
      } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          itemsIndexed(splatList) { index, option ->
            val isSelected = index == selectedIndex.value
            SplatRowItem(
                splatPath = option,
                isSelected = isSelected,
                onClick = {
                  loadSplatFunction(option)
                  selectedIndex.value = index
                },
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))
      DebugLogPanel(debugLogLines)
    }
  }
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
      modifier =
          modifier
              .clip(RoundedCornerShape(12.dp))
              .background(LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.10f))
              .border(
                  2.dp,
                  LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.35f),
                  RoundedCornerShape(12.dp),
              )
              .clickable(onClick = onClick)
              .padding(vertical = 10.dp, horizontal = 12.dp),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = text,
        style = SpatialTheme.typography.headline2Strong,
        color = LocalColorScheme.current.primaryAlphaBackground,
    )
  }
}

@Composable
private fun EmptyState() {
  Box(
      modifier =
          Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(14.dp))
              .background(LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.06f))
              .padding(14.dp),
      contentAlignment = Alignment.CenterStart,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
          text = "No files found",
          style = SpatialTheme.typography.headline2Strong,
          color = LocalColorScheme.current.primaryAlphaBackground,
      )
      Text(
          text = "Push .ply/.spz files to: /storage/emulated/0/Android/data/com.meta.spatial.samples.splatsample/files/",
          style = SpatialTheme.typography.body1,
          color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.7f),
      )
    }
  }
}

@Composable
private fun SplatRowItem(
    splatPath: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
  val borderWidth = if (isSelected) 4.dp else 2.dp
  val borderColor = if (isSelected) selectedBlue else LocalColorScheme.current.primaryAlphaBackground
  val titleColor = if (isSelected) selectedBlue else LocalColorScheme.current.primaryAlphaBackground

  Row(
      modifier =
          Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(14.dp))
              .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
              .clickable(onClick = onClick)
              .padding(10.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    PlaceholderPreviewTile(
        label = getSplatDisplayName(splatPath),
        isSelected = isSelected,
        modifier =
            Modifier
                .height(88.dp)
                .fillMaxWidth(0.32f)
                .clip(RoundedCornerShape(10.dp)),
    )

    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
          text = getSplatDisplayName(splatPath),
          style = SpatialTheme.typography.headline2Strong,
          color = titleColor,
      )
      Text(
          text = getSplatShortPath(splatPath),
          style = SpatialTheme.typography.body1,
          color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.65f),
      )
    }
  }
}

@Composable
private fun PlaceholderPreviewTile(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
  val bg =
      if (isSelected) selectedBlue.copy(alpha = 0.14f)
      else LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.08f)

  Box(
      modifier = modifier.background(bg).padding(10.dp),
      contentAlignment = Alignment.BottomStart,
  ) {
    Text(
        text = label.take(24),
        style = SpatialTheme.typography.body1,
        color = LocalColorScheme.current.primaryAlphaBackground,
    )
  }
}

@Composable
private fun DebugLogPanel(lines: List<String>) {
  Box(
      modifier =
          Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(14.dp))
              .background(LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.06f))
              .padding(10.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
          text = "Debug log",
          style = SpatialTheme.typography.headline2Strong,
          color = LocalColorScheme.current.primaryAlphaBackground,
      )

      val show = lines.takeLast(8)
      if (show.isEmpty()) {
        Text(
            text = "(no log lines yet)",
            style = SpatialTheme.typography.body1,
            color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.65f),
        )
      } else {
        show.forEach { line ->
          Text(
              text = line,
              style = SpatialTheme.typography.body1,
              color = LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.75f),
          )
        }
      }
    }
  }
}

@Composable
fun getPanelTheme(): SpatialColorScheme =
    if (isSystemInDarkTheme()) darkSpatialColorScheme() else lightSpatialColorScheme()

fun getSplatDisplayName(splatPath: String): String {
  val name =
      splatPath
          .removePrefix("apk://")
          .removePrefix("file://")
          .substringAfterLast("/")
  return name
      .removeSuffix(".spz")
      .removeSuffix(".SPZ")
      .removeSuffix(".ply")
      .removeSuffix(".PLY")
}

fun getSplatShortPath(splatPath: String): String {
  return when {
    splatPath.startsWith("apk://") -> "Bundled asset"
    splatPath.startsWith("file://") -> "Device file"
    else -> "Path"
  }
}

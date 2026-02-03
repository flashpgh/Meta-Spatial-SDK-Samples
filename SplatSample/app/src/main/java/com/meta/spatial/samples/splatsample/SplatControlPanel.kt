/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * SplatControlPanel.kt
 *
 * UI control panel for the Splat demo app using Jetpack Compose.
 *
 * Updated behavior:
 * - Supports an arbitrary number of splats (scrollable list)
 * - Works with dynamically scanned assets (*.spz, *.ply)
 * - Shows preview image when available, otherwise renders a placeholder tile
 */
package com.meta.spatial.samples.splatsample

import androidx.compose.foundation.Image
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

private val panelHeadingText = "Splat Sample"
private val panelInstructionText = buildAnnotatedString {
  append("Press ")
  withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("A") }
  append(" to snap the panel in front of you. \nPress ")
  withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("B") }
  append(" to recenter the view.")
}

private val selectedBlue = Color(0xFF1877F2)

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
                .padding(28.dp),
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

      Spacer(modifier = Modifier.height(18.dp))

      // If no splats were found, show a clear message
      if (splatList.isEmpty()) {
        EmptyState()
        return@Column
      }

      // Scrollable list of splats (supports any count)
      LazyColumn(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        itemsIndexed(splatList) { index, option ->
          val isSelected = (index == selectedIndex.value)

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
  }
}

@Composable
private fun EmptyState() {
  Box(
      modifier =
          Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(16.dp))
              .background(LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.06f))
              .padding(18.dp),
      contentAlignment = Alignment.CenterStart,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
          text = "No splats found in assets/",
          style = SpatialTheme.typography.headline2Strong,
          color = LocalColorScheme.current.primaryAlphaBackground,
      )
      Text(
          text = "Add *.spz or *.ply files to app/src/main/assets/ and rebuild.",
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
              .padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    // Preview (optional)
    val previewResource = getSplatPreviewResource(splatPath)

    if (previewResource != null) {
      Image(
          painter = painterResource(id = previewResource),
          contentDescription = "Preview of $splatPath",
          modifier =
              Modifier
                  .height(110.dp)
                  .fillMaxWidth(0.35f)
                  .clip(RoundedCornerShape(10.dp)),
          contentScale = ContentScale.Crop,
      )
    } else {
      PlaceholderPreviewTile(
          label = getSplatDisplayName(splatPath),
          isSelected = isSelected,
          modifier =
              Modifier
                  .height(110.dp)
                  .fillMaxWidth(0.35f)
                  .clip(RoundedCornerShape(10.dp)),
      )
    }

    // Text area
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
      if (isSelected)
          selectedBlue.copy(alpha = 0.14f)
      else
          LocalColorScheme.current.primaryAlphaBackground.copy(alpha = 0.08f)

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

/**
 * Determines the appropriate color scheme based on system theme.
 */
@Composable
fun getPanelTheme(): SpatialColorScheme =
    if (isSystemInDarkTheme()) darkSpatialColorScheme() else lightSpatialColorScheme()

/**
 * Optional mapping to preview images. Keep your existing samples, and add more if you want:
 * - Add a drawable under res/drawable
 * - Add a case here for your filename/keyword
 */
fun getSplatPreviewResource(splatPath: String): Int? {
  return when {
    splatPath.contains("Menlo Park", ignoreCase = true) -> R.drawable.mpk_room
    splatPath.contains("Los Angeles", ignoreCase = true) -> R.drawable.lax_room
    else -> null
  }
}

/**
 * Display name from the splat path. Supports both .spz and .ply and strips "apk://".
 */
fun getSplatDisplayName(splatPath: String): String {
  return splatPath
      .removePrefix("apk://")
      .removePrefix("file://")
      .removePrefix("https://")
      .removePrefix("http://")
      .substringAfterLast("/") // handle any URL-like paths
      .removeSuffix(".spz")
      .removeSuffix(".SPZ")
      .removeSuffix(".ply")
      .removeSuffix(".PLY")
}

/**
 * A short “where did this come from” string. Keeps it readable.
 */
fun getSplatShortPath(splatPath: String): String {
  return when {
    splatPath.startsWith("apk://") -> "Bundled asset"
    splatPath.startsWith("file://") -> "Local file"
    splatPath.startsWith("http://") || splatPath.startsWith("https://") -> "Network URL"
    else -> "Path"
  }
}

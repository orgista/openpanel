/*
 * Copyright (c) 2026 Orgista.
 * SPDX-License-Identifier: MIT
 */

package com.orgista.openpanel.quest

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.spatial.uiset.theme.SpatialColor
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.darkSpatialColorScheme
import com.meta.spatial.uiset.theme.icons.SpatialIcons
import com.meta.spatial.uiset.theme.icons.regular.Bluetooth
import com.meta.spatial.uiset.theme.icons.regular.CategoryAll
import com.meta.spatial.uiset.theme.icons.regular.Environment
import com.meta.spatial.uiset.theme.icons.regular.HeadsetCasting
import com.meta.spatial.uiset.theme.icons.regular.ScaleDown
import com.meta.spatial.uiset.theme.icons.regular.Search
import com.meta.spatial.uiset.theme.icons.regular.Settings
import com.meta.spatial.uiset.theme.icons.regular.WifiOn
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

// Meta OS visual language, painted with Meta's own design tokens from the
// Spatial SDK UI Set (SpatialTheme / darkSpatialColorScheme) instead of hexes
// sampled from compressed captures: panels are the RLDS black gradient
// (#414141 → #272727), controls are white-alpha overlays (white10 idle,
// white20 focus), the active state is the white pill (#F1F4F7) with
// near-black content, and text is white at 100/90/60% opacity.
//
// Environment behaviour matches Meta OS: fully OPAQUE surfaces in the
// immersive world (translucent darks over a bright skybox force compositor
// dithering — the old dot grid), and a translucent smoke version of the same
// gradient over passthrough, where Horizon's own panels let the room read
// through.
private val BootCanvas = Color(0xFF050607)
private val PanelShape = RoundedCornerShape(32.dp)
private val PillShape = RoundedCornerShape(999.dp)
private const val PassthroughSurfaceAlpha = 0.72f

@Composable
private fun panelSurfaceBrush(passthrough: Boolean): androidx.compose.ui.graphics.Brush =
    if (passthrough) {
      androidx.compose.ui.graphics.Brush.verticalGradient(
          listOf(
              Color(0xFF414141).copy(alpha = PassthroughSurfaceAlpha),
              Color(0xFF272727).copy(alpha = PassthroughSurfaceAlpha),
          ),
      )
    } else {
      SpatialTheme.colorScheme.panel
    }

@Composable
private fun OpenPanelTheme(content: @Composable () -> Unit) {
  SpatialTheme(colorScheme = darkSpatialColorScheme(), content = content)
}

/**
 * The Navigator-style library window: a quiet title row, Horizon's left utility
 * rail (search + grid), and the app grid. Clicking a tile launches the app.
 */
@Composable
fun OpenPanelLibrary(
    apps: List<LaunchableApp>,
    status: String,
    showBoot: Boolean,
    passthroughEnabled: Boolean,
    libraryResized: Boolean,
    onResetSize: () -> Unit,
    onLaunch: (LaunchableApp) -> Unit,
) {
  OpenPanelTheme {
    if (showBoot) {
      Box(
          modifier = Modifier.fillMaxSize().clip(PanelShape).background(BootCanvas),
          contentAlignment = Alignment.Center,
      ) {
        Image(
            painter = painterResource(R.drawable.openpanel_quad_mark),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
      }
      return@OpenPanelTheme
    }

    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val visibleApps =
        if (searchActive && query.isNotBlank()) {
          apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
        } else {
          apps
        }

    Box(
        // Horizon panels separate by tone, not borders: the RLDS gradient, no
        // hairline edge. Opaque in immersive, smoke over passthrough.
        modifier =
            Modifier.fillMaxSize().clip(PanelShape).background(panelSurfaceBrush(passthroughEnabled)),
    ) {
      Column(
          modifier =
              Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 22.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
              "Library",
              color = Color.White,
              fontSize = 19.sp,
              fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.weight(1f))
          if (searchActive) {
            SearchField(query = query, onQueryChange = { query = it })
          }
          if (libraryResized) {
            // Subtle restore-original-size control; only exists while the
            // window has been pinch-resized away from its authored size.
            if (searchActive) Spacer(Modifier.width(8.dp))
            RoundIconButton(
                painter = rememberVectorPainter(SpatialIcons.Regular.ScaleDown),
                contentDescription = "Reset window size",
                onClick = onResetSize,
            )
          }
        }
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxSize()) {
          // Horizon's left utility rail, with Meta's licensed UI Set icons.
          Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            RoundIconButton(
                painter = rememberVectorPainter(SpatialIcons.Regular.Search),
                contentDescription = "Search apps",
                active = searchActive,
                size = 34.dp,
                iconSize = 17.dp,
                onClick = {
                  searchActive = !searchActive
                  if (!searchActive) query = ""
                },
            )
            RoundIconButton(
                painter = rememberVectorPainter(SpatialIcons.Regular.CategoryAll),
                contentDescription = "All apps",
                active = !searchActive,
                size = 34.dp,
                iconSize = 17.dp,
                onClick = {
                  searchActive = false
                  query = ""
                },
            )
          }
          Spacer(Modifier.width(18.dp))

          if (visibleApps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text(
                  if (apps.isEmpty()) status else "No apps match “${query.trim()}”.",
                  color = SpatialColor.white60,
                  fontSize = 16.sp,
              )
            }
          } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 112.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
              items(visibleApps, key = { it.packageName }) { app ->
                LibraryTile(app = app, onLaunch = { onLaunch(app) })
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
  // One tap on Search must be enough: the field takes focus the moment it
  // appears, which also summons the system keyboard (its mic key is Quest's
  // voice-input path — there is no RecognizerIntent activity on Horizon OS).
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }
  Box(
      modifier =
          Modifier.clip(PillShape)
              .background(SpatialTheme.colorScheme.secondaryButton)
              .padding(horizontal = 14.dp, vertical = 8.dp)
              .width(220.dp),
      contentAlignment = Alignment.CenterStart,
  ) {
    if (query.isEmpty()) {
      Text("Search apps", color = SpatialColor.white60, fontSize = 13.sp)
    }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle =
            TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(Color.White),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
  }
}

@Composable
private fun LibraryTile(app: LaunchableApp, onLaunch: () -> Unit) {
  var focused by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(20.dp)

  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.width(112.dp),
  ) {
    Box(
        modifier =
            Modifier.size(88.dp)
                .scale(if (focused) 1.06f else 1f)
                .clip(shape)
                .background(
                    if (app.icon == null) SpatialTheme.colorScheme.placeholder
                    else Color.Transparent,
                )
                .then(
                    if (focused) Modifier.border(2.5.dp, Color.White, shape) else Modifier,
                )
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .clickable(role = Role.Button, onClick = onLaunch),
        contentAlignment = Alignment.Center,
    ) {
      val icon = app.icon
      if (icon != null) {
        // asImageBitmap() allocates a fresh wrapper per call; the tile cannot
        // skip recomposition (LaunchableApp holds a Bitmap, so it is unstable),
        // so keep the wrapper keyed to the bitmap instead of the frame.
        val iconBitmap = remember(icon) { icon.asImageBitmap() }
        Image(
            bitmap = iconBitmap,
            contentDescription = app.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
      } else {
        Image(
            painter = painterResource(R.drawable.openpanel_quad_mark),
            contentDescription = app.label,
            modifier = Modifier.size(32.dp),
        )
      }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        app.label,
        color = if (focused) Color.White else SpatialColor.white90,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
  }
}

/**
 * The compact quick-settings bar: OpenPanel mark (returns home), headset
 * (environment; white while passthrough), Wi-Fi, Bluetooth, Meta AI when
 * social allows, Cast, Settings, and the clock/battery chip (opens date &
 * time settings).
 */
@Composable
fun OpenPanelSystemBar(
    showBoot: Boolean,
    passthroughEnabled: Boolean,
    metaAiAvailable: Boolean,
    castAvailable: Boolean,
    batteryPercent: () -> Int,
    batteryCharging: () -> Boolean,
    controllerBattery: () -> Pair<Int, Int>,
    onHome: () -> Unit,
    onToggleEnvironment: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onLaunchMetaAi: () -> Unit,
    onStartCasting: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDateSettings: () -> Unit,
) {
  OpenPanelTheme {
    if (showBoot) {
      Box(Modifier.fillMaxSize())
      return@OpenPanelTheme
    }

    var clock by remember { mutableStateOf(currentTime()) }
    var battery by remember { mutableIntStateOf(batteryPercent()) }
    var charging by remember { mutableStateOf(batteryCharging()) }
    var controllerLeft by remember { mutableIntStateOf(controllerBattery().first) }
    var controllerRight by remember { mutableIntStateOf(controllerBattery().second) }
    LaunchedEffect(Unit) {
      while (true) {
        clock = currentTime()
        battery = batteryPercent()
        charging = batteryCharging()
        val (l, r) = controllerBattery()
        controllerLeft = l
        controllerRight = r
        delay(30_000)
      }
    }

    Row(
        modifier =
            Modifier.fillMaxSize()
                .clip(PillShape)
                .background(panelSurfaceBrush(passthroughEnabled))
                .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
    ) {
      HomeMarkButton(onClick = onHome)
      RoundIconButton(
          painter = rememberVectorPainter(SpatialIcons.Regular.Environment),
          contentDescription =
              if (passthroughEnabled) "Switch to immersive" else "Switch to passthrough",
          active = passthroughEnabled,
          onClick = onToggleEnvironment,
      )
      RoundIconButton(
          painter = rememberVectorPainter(SpatialIcons.Regular.WifiOn),
          contentDescription = "Wi-Fi settings",
          onClick = onOpenWifiSettings,
      )
      RoundIconButton(
          painter = rememberVectorPainter(SpatialIcons.Regular.Bluetooth),
          contentDescription = "Bluetooth settings",
          onClick = onOpenBluetoothSettings,
      )
      if (metaAiAvailable) {
        RoundIconButton(
            painter = painterResource(R.drawable.ic_spark),
            contentDescription = "Meta AI",
            onClick = onLaunchMetaAi,
        )
      }
      if (castAvailable) {
        RoundIconButton(
            painter = rememberVectorPainter(SpatialIcons.Regular.HeadsetCasting),
            contentDescription = "Cast",
            onClick = onStartCasting,
        )
      }
      RoundIconButton(
          painter = rememberVectorPainter(SpatialIcons.Regular.Settings),
          contentDescription = "Settings",
          onClick = onOpenSettings,
      )
      ClockChip(
          clock = clock,
          batteryPercentValue = battery,
          batteryChargingValue = charging,
          controllerLeftPercent = controllerLeft,
          controllerRightPercent = controllerRight,
          onClick = onOpenDateSettings,
      )
    }
  }
}

@Composable
private fun HomeMarkButton(onClick: () -> Unit) {
  var focused by remember { mutableStateOf(false) }
  Box(
      modifier =
          Modifier.size(26.dp)
              .clip(CircleShape)
              .background(if (focused) SpatialTheme.colorScheme.hover else Color.Transparent)
              .onFocusChanged { focused = it.isFocused }
              .focusable()
              .clickable(role = Role.Button, onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    Image(
        painter = painterResource(R.drawable.openpanel_quad_mark),
        contentDescription = "OpenPanel home",
        modifier = Modifier.size(19.dp),
    )
  }
}

@Composable
private fun RoundIconButton(
    painter: Painter,
    contentDescription: String,
    active: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 26.dp,
    iconSize: androidx.compose.ui.unit.Dp = 13.dp,
    onClick: () -> Unit,
) {
  var focused by remember { mutableStateOf(false) }
  val scheme = SpatialTheme.colorScheme
  val background =
      when {
        active -> scheme.primaryButton
        focused -> scheme.hover
        else -> scheme.secondaryButton
      }
  Box(
      modifier =
          Modifier.size(size)
              .clip(CircleShape)
              .background(background)
              .onFocusChanged { focused = it.isFocused }
              .focusable()
              .clickable(role = Role.Button, onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = if (active) SpatialColor.RLDSBlack100 else Color.White,
        modifier = Modifier.size(iconSize),
    )
  }
}

@Composable
private fun ClockChip(
    clock: String,
    batteryPercentValue: Int,
    batteryChargingValue: Boolean,
    controllerLeftPercent: Int,
    controllerRightPercent: Int,
    onClick: () -> Unit,
) {
  var focused by remember { mutableStateOf(false) }
  Row(
      modifier =
          Modifier.clip(PillShape)
              .background(if (focused) SpatialTheme.colorScheme.hover else Color.Transparent)
              .onFocusChanged { focused = it.isFocused }
              .focusable()
              .clickable(role = Role.Button, onClick = onClick)
              .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(clock, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    if (controllerLeftPercent >= 0 || controllerRightPercent >= 0) {
      Spacer(Modifier.width(6.dp))
      ControllerBatteryGlyphs(left = controllerLeftPercent, right = controllerRightPercent)
    }
    Spacer(Modifier.width(6.dp))
    BatteryGlyph(percent = batteryPercentValue, charging = batteryChargingValue)
  }
}

@Composable
private fun BatteryGlyph(percent: Int, charging: Boolean) {
  val level = percent.coerceIn(0, 100) / 100f
  // Charge status: green fill + bolt while on power (Meta OS convention).
  val fillColor = if (charging) Color(0xB34CD964) else SpatialColor.white30
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(
        modifier =
            Modifier.size(width = 27.dp, height = 13.dp)
                .border(1.dp, Color.White, RoundedCornerShape(3.5.dp)),
        contentAlignment = Alignment.Center,
    ) {
      Box(
          modifier =
              Modifier.fillMaxSize()
                  .padding(2.dp),
      ) {
        Box(
            modifier =
                Modifier.fillMaxHeight()
                    .fillMaxWidth(level)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(fillColor),
        )
      }
      Text(
          if (charging) "\u26A1$percent" else "$percent",
          color = Color.White,
          fontSize = 7.5.sp,
          fontWeight = FontWeight.Bold,
      )
    }
    Spacer(Modifier.width(1.dp))
    Box(
        modifier =
            Modifier.size(width = 2.dp, height = 5.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color.White),
    )
  }
}

@Composable
private fun ControllerBatteryGlyphs(left: Int, right: Int) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    if (left >= 0) ControllerBatteryGlyph(label = "L", percent = left)
    if (right >= 0) ControllerBatteryGlyph(label = "R", percent = right)
  }
}

@Composable
private fun ControllerBatteryGlyph(label: String, percent: Int) {
  val level = percent.coerceIn(0, 100) / 100f
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.width(1.5.dp))
    Box(
        modifier =
            Modifier.size(width = 18.dp, height = 9.dp)
                .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(2.5.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
      Box(
          modifier = Modifier.fillMaxSize().padding(1.5.dp),
      ) {
        Box(
            modifier =
                Modifier.fillMaxHeight()
                    .fillMaxWidth(level)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color.White.copy(alpha = 0.3f)),
        )
      }
    }
    Spacer(Modifier.width(0.5.dp))
    Box(
        modifier =
            Modifier.size(width = 1.5.dp, height = 3.5.dp)
                .clip(RoundedCornerShape(0.5.dp))
                .background(Color.White.copy(alpha = 0.6f)),
    )
  }
}

private val ClockFormatter = DateTimeFormatter.ofPattern("h:mm a")

private fun currentTime(): String = LocalTime.now().format(ClockFormatter)

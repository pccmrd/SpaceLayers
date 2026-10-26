package com.example.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import com.example.data.model.HexCellSeed
import com.example.data.model.HexCoord
import com.example.data.model.SpaceLayer
import com.example.domain.gravity.GravityWellPhysics
import com.example.domain.gravity.HexGravitySample
import com.example.domain.voxel.HexPrismVoxel
import com.example.domain.voxel.HexVoxelProceduralEngine
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

private val SQRT_3 = sqrt(3f)
private const val ISO_TILT = 0.56f

@Composable
fun IsometricHexVoxelCanvas(
  gridCoords: List<HexCoord>,
  activeLayer: SpaceLayer,
  isStrataStackMode: Boolean,
  selectedCoord: HexCoord,
  shipCoord: HexCoord,
  shipLayer: SpaceLayer,
  allCells: List<HexCellSeed>,
  gravityField: Map<HexCoord, HexGravitySample>,
  onHexSelected: (HexCoord) -> Unit,
  modifier: Modifier = Modifier,
) {
  var zoom by remember { mutableFloatStateOf(1.05f) }
  var panOffset by remember { mutableStateOf(Offset.Zero) }
  var canvasSize by remember { mutableStateOf(Offset(800f, 600f)) }

  val shipVoxels = remember { HexVoxelProceduralEngine.compileCommanderShipVoxels() }

  // Pre-compute gravity fields for all 5 layers when in Strata Stack mode
  val stackedFields =
    remember(isStrataStackMode, allCells, gridCoords) {
      if (isStrataStackMode) {
        SpaceLayer.entries.associateWith { layer ->
          GravityWellPhysics.computeLayerGravityField(gridCoords, layer, allCells)
        }
      } else {
        emptyMap()
      }
    }

  Canvas(
    modifier =
      modifier
        .pointerInput(Unit) {
          detectTransformGestures { _, pan, gestureZoom, _ ->
            zoom = (zoom * gestureZoom).coerceIn(0.60f, 2.50f)
            panOffset += pan
          }
        }
        .pointerInput(gridCoords, activeLayer, isStrataStackMode, gravityField, zoom, panOffset) {
          detectTapGestures { tapPos ->
            val w = canvasSize.x.coerceAtLeast(1f)
            val h = canvasSize.y.coerceAtLeast(1f)
            val baseHexRadius = (min(w, h) / 10.2f) * zoom
            val centerScreen = Offset(w * 0.5f + panOffset.x, h * 0.50f + panOffset.y)

            var bestCoord: HexCoord? = null
            var bestDist = Float.MAX_VALUE
            for (coord in gridCoords) {
              val sink = gravityField[coord]?.wellSinkDepth ?: 0f
              val layerOffsetZ =
                if (isStrataStackMode) (activeLayer.index - 2) * baseHexRadius * 1.45f else 0f
              val hexCenter =
                projectHexCenter(
                  coord = coord,
                  hexRadius = baseHexRadius,
                  origin = centerScreen,
                  sinkDepth = sink,
                  layerOffsetPx = layerOffsetZ,
                )
              val dx = tapPos.x - hexCenter.x
              val dy = (tapPos.y - hexCenter.y) / ISO_TILT
              val d = hypot(dx, dy)
              if (d < baseHexRadius * 0.98f && d < bestDist) {
                bestDist = d
                bestCoord = coord
              }
            }
            if (bestCoord != null) {
              onHexSelected(bestCoord)
            }
          }
        }
  ) {
    canvasSize = Offset(size.width, size.height)

    // 1. Deep Cosmic Gradient & Subtle Starfield Backdrop
    drawRect(
      brush =
        Brush.radialGradient(
          colors = listOf(Color(0xFF0E1B38), Color(0xFF060A14), Color(0xFF03050B)),
          center = Offset(size.width * 0.5f, size.height * 0.45f),
          radius = size.maxDimension * 0.78f,
        )
    )
    drawDeterministicStarfield()

    val baseHexRadius = (min(size.width, size.height) / 10.2f) * zoom
    val centerScreen = Offset(size.width * 0.5f + panOffset.x, size.height * 0.50f + panOffset.y)
    val reusablePath = Path()

    val layersToRender =
      if (isStrataStackMode) {
        SpaceLayer.entries.sortedBy { it.index }
      } else {
        listOf(activeLayer)
      }

    // Painter's order for hex coords: sort back-to-front by worldY = sqrt(3)*(r + q/2)
    val sortedCoords =
      gridCoords.sortedBy { coord ->
        SQRT_3 * (coord.r + coord.q * 0.5f)
      }

    for (layer in layersToRender) {
      val isFocusedLayer = (layer == activeLayer)
      val layerAlpha = if (!isStrataStackMode || isFocusedLayer) 1f else 0.34f
      val layerOffsetPx =
        if (isStrataStackMode) (layer.index - 2) * baseHexRadius * 1.45f else 0f
      val layerField =
        if (isStrataStackMode) stackedFields[layer].orEmpty() else gravityField
      val layerCellsByCoord =
        allCells.filter { it.layerIndex.toInt() == layer.index }.associateBy { it.coord }

      // 2. Gravity Well Funnel Mesh Lines (connect adjacent hex centers in 3D curved space)
      val coordSet = gridCoords.toHashSet()
      for (coord in sortedCoords) {
        val sampleA = layerField[coord]
        val sinkA = sampleA?.wellSinkDepth ?: 0f
        val ptA = projectHexCenter(coord, baseHexRadius, centerScreen, sinkA, layerOffsetPx)

        // Draw 3 forward neighbor edges to avoid duplicate lines
        val forwardDirs = listOf(1 to 0, 0 to 1, -1 to 1)
        for ((dq, dr) in forwardDirs) {
          val nb = HexCoord(coord.q + dq, coord.r + dr)
          if (nb in coordSet) {
            val sinkB = layerField[nb]?.wellSinkDepth ?: 0f
            val ptB = projectHexCenter(nb, baseHexRadius, centerScreen, sinkB, layerOffsetPx)
            val maxSink = kotlin.math.max(sinkA, sinkB)
            val wellTint =
              lerp(
                Color(0xFF1E3A5F),
                Color(0xFF818CF8),
                (maxSink / 5.5f).coerceIn(0f, 1f),
              )
            drawLine(
              color = wellTint.copy(alpha = (0.32f + (maxSink * 0.08f).coerceAtMost(0.45f)) * layerAlpha),
              start = ptA,
              end = ptB,
              strokeWidth = if (maxSink > 1.8f) 2.2f else 1.1f,
            )
          }
        }
      }

      // 3. Render Flat-Bottom Hexagonal Plates & Micro-Hexagonal Prism Voxels Back-to-Front
      for (coord in sortedCoords) {
        val sample = layerField[coord]
        val sink = sample?.wellSinkDepth ?: 0f
        val gPull = sample?.localGravityG ?: 0.05f
        val hexCenter = projectHexCenter(coord, baseHexRadius, centerScreen, sink, layerOffsetPx)
        val isSelected = (coord == selectedCoord && isFocusedLayer)
        val isShipHere = (coord == shipCoord && layer == shipLayer)
        val cell = layerCellsByCoord[coord]

        // Base Hex Plate Fill (tinted by layer & gravity well depth)
        val basePlateColor =
          when (layer) {
            SpaceLayer.DEEP_SPACE ->
              lerp(Color(0xFF0A1326), Color(0xFF281E5D), (sink / 5f).coerceIn(0f, 1f))
            SpaceLayer.ORBITAL_WELL ->
              lerp(Color(0xFF0D1D35), Color(0xFF1D3566), (sink / 5f).coerceIn(0f, 1f))
            SpaceLayer.ATMOSPHERE ->
              lerp(Color(0xFF0E2944), Color(0xFF164E63), (sample?.atmosphereDensity ?: 0.3f).coerceIn(0f, 1f))
            SpaceLayer.SURFACE_TERRAIN -> Color(0xFF122620)
            SpaceLayer.SUBSURFACE_CORE -> Color(0xFF241324)
          }

        drawFlatBottomHexPlate(
          path = reusablePath,
          center = hexCenter,
          radius = baseHexRadius * 0.94f,
          fillColor = basePlateColor.copy(alpha = (if (isSelected) 0.92f else 0.72f) * layerAlpha),
          strokeColor =
            when {
              isSelected -> Color(0xFF00E5FF)
              isShipHere -> Color(0xFFFFB300)
              sink > 2.0f -> Color(0xFF818CF8).copy(alpha = 0.75f * layerAlpha)
              else -> Color(0xFF2B426B).copy(alpha = 0.55f * layerAlpha)
            },
          strokeWidth = if (isSelected) 3.2f else if (isShipHere) 2.4f else 1.3f,
        )

        // Equipotential inner gravity contour ring for deep gravity wells
        if (sink > 1.2f && isFocusedLayer) {
          drawFlatBottomHexPlate(
            path = reusablePath,
            center = hexCenter.copy(y = hexCenter.y + sink * 1.4f),
            radius = baseHexRadius * 0.62f,
            fillColor = Color.Transparent,
            strokeColor = Color(0xFF22D3EE).copy(alpha = (sink / 8f).coerceIn(0.18f, 0.55f)),
            strokeWidth = 1.0f,
          )
        }

        // Subtle gravity pull vector indicator toward dominant mass
        if (sample != null && isFocusedLayer && (kotlin.math.abs(sample.pullVectorX) > 0.08f || kotlin.math.abs(sample.pullVectorY) > 0.08f)) {
          val vecEnd =
            Offset(
              hexCenter.x + sample.pullVectorX * baseHexRadius * 0.32f,
              hexCenter.y + sample.pullVectorY * baseHexRadius * 0.32f * ISO_TILT,
            )
          drawLine(
            color = Color(0xFF818CF8).copy(alpha = 0.55f),
            start = hexCenter,
            end = vecEnd,
            strokeWidth = 1.8f,
          )
          drawCircle(
            color = Color(0xFF00E5FF).copy(alpha = 0.7f),
            radius = 2.4f,
            center = vecEnd,
          )
        }

        // Vertical gravimetric tether column in 5-Layer Strata Stack mode if populated
        if (isStrataStackMode && cell != null && layer.index > 0) {
          drawLine(
            color = Color(0xFF00E5FF).copy(alpha = 0.22f),
            start = hexCenter,
            end = hexCenter.copy(y = hexCenter.y + baseHexRadius * 1.45f),
            strokeWidth = 1.5f,
          )
        }

        // Ghosted lower planetary core silhouette when viewing Deep Space / Orbit in Single-Layer mode
        if (!isStrataStackMode && cell == null && gPull > 0.45f) {
          val lowerCell =
            allCells
              .filter { it.coord == coord && it.layerIndex.toInt() < layer.index }
              .maxByOrNull { it.layerIndex }
          if (lowerCell != null) {
            drawFlatBottomHexPlate(
              path = reusablePath,
              center = hexCenter.copy(y = hexCenter.y + baseHexRadius * 0.16f),
              radius = baseHexRadius * 0.42f,
              fillColor =
                HexVoxelProceduralEngine.parseHexColor(lowerCell.primaryColorHex, Color(0xFF38BDF8))
                  .copy(alpha = 0.20f),
              strokeColor = Color(0xFF38BDF8).copy(alpha = 0.35f),
              strokeWidth = 1.1f,
            )
          }
        }

        // 4. Render Micro-Hexagonal Prism Voxel Sculpture on top of this Hex!
        if (cell != null) {
          val voxels = HexVoxelProceduralEngine.compileHexCell(cell)
          val subRings = cell.voxelSubRings.toInt().coerceIn(2, 6)
          val microRadius = (baseHexRadius * 0.84f) / (2f * subRings + 1.35f)
          val zUnitPx = microRadius * 0.75f

          drawHexVoxelCluster(
            path = reusablePath,
            voxels = voxels,
            hexCenter = hexCenter,
            microRadius = microRadius,
            zUnitPx = zUnitPx,
            alpha = layerAlpha,
          )
        }

        // 5. Render Commander's Hex-Voxel Flagship if stationed at this Hex & Layer
        if (isShipHere) {
          val shipMicroRadius = (baseHexRadius * 0.62f) / 6.2f
          val shipZUnit = shipMicroRadius * 0.85f
          val hoverOffset = if (cell != null) baseHexRadius * 0.38f else 0f
          drawHexVoxelCluster(
            path = reusablePath,
            voxels = shipVoxels,
            hexCenter = hexCenter.copy(y = hexCenter.y - hoverOffset),
            microRadius = shipMicroRadius,
            zUnitPx = shipZUnit,
            alpha = 1f,
          )
        }
      }
    }
  }
}

private fun projectHexCenter(
  coord: HexCoord,
  hexRadius: Float,
  origin: Offset,
  sinkDepth: Float,
  layerOffsetPx: Float,
): Offset {
  // Flat-bottom / flat-top regular hex lattice spacing:
  // x = R * 3/2 * q, y = R * sqrt(3) * (r + q/2)
  val worldX = hexRadius * 1.5f * coord.q
  val worldY = hexRadius * SQRT_3 * (coord.r + coord.q * 0.5f)
  val sinkPx = sinkDepth * hexRadius * 0.22f
  return Offset(
    x = origin.x + worldX,
    y = origin.y + worldY * ISO_TILT + sinkPx - layerOffsetPx,
  )
}

/**
 * Draws a flat-bottom regular hexagon projected isometrically.
 * Vertices V1->V2 form the flat horizontal bottom edge; V4->V5 form the flat horizontal top edge.
 */
private fun DrawScope.drawFlatBottomHexPlate(
  path: Path,
  center: Offset,
  radius: Float,
  fillColor: Color,
  strokeColor: Color,
  strokeWidth: Float,
) {
  val halfR = radius * 0.5f
  val dy = radius * (SQRT_3 * 0.5f) * ISO_TILT

  path.reset()
  path.moveTo(center.x + radius, center.y) // V0 Right
  path.lineTo(center.x + halfR, center.y + dy) // V1 Bottom-Right
  path.lineTo(center.x - halfR, center.y + dy) // V2 Bottom-Left (Flat Bottom Edge V1->V2!)
  path.lineTo(center.x - radius, center.y) // V3 Left
  path.lineTo(center.x - halfR, center.y - dy) // V4 Top-Left
  path.lineTo(center.x + halfR, center.y - dy) // V5 Top-Right (Flat Top Edge V4->V5!)
  path.close()

  if (fillColor.alpha > 0f) {
    drawPath(path = path, color = fillColor)
  }
  if (strokeWidth > 0f && strokeColor.alpha > 0f) {
    drawPath(path = path, color = strokeColor, style = Stroke(width = strokeWidth))
  }
}

/**
 * Renders a list of 3D hexagonal-base prism voxels in strict back-to-front isometric order,
 * shading the 3 visible vertical side quads (left-front, flat-bottom center-front, right-front)
 * and the sunlit flat-bottom hexagonal cap!
 */
internal fun DrawScope.drawHexVoxelCluster(
  path: Path,
  voxels: List<HexPrismVoxel>,
  hexCenter: Offset,
  microRadius: Float,
  zUnitPx: Float,
  alpha: Float,
  yawCos: Float = 1f,
  yawSin: Float = 0f,
) {
  val sorted =
    voxels.sortedWith(
      compareBy<HexPrismVoxel> { v ->
        val lx = 1.5f * v.vq
        val ly = SQRT_3 * (v.vr + v.vq * 0.5f)
        val rotY = lx * yawSin + ly * yawCos
        rotY
      }.thenBy { it.zBase }
    )

  val r = microRadius * 0.96f
  val halfR = r * 0.5f
  val dy = r * (SQRT_3 * 0.5f) * ISO_TILT

  for (v in sorted) {
    val rawX = microRadius * 1.5f * v.vq
    val rawY = microRadius * SQRT_3 * (v.vr + v.vq * 0.5f)
    val rotX = rawX * yawCos - rawY * yawSin
    val rotY = rawX * yawSin + rawY * yawCos

    val baseScreenX = hexCenter.x + rotX
    val baseScreenY = hexCenter.y + rotY * ISO_TILT - v.zBase * zUnitPx
    val topScreenY = baseScreenY - v.zHeight * zUnitPx

    val topCol =
      lerp(v.color, Color.White, (0.14f + v.emissive * 0.35f).coerceIn(0f, 0.65f))
        .copy(alpha = alpha)
    val leftFaceCol = lerp(v.color, Color.Black, (0.16f - v.emissive * 0.1f).coerceAtLeast(0f)).copy(alpha = alpha)
    val frontFlatFaceCol = lerp(v.color, Color.Black, (0.30f - v.emissive * 0.12f).coerceAtLeast(0f)).copy(alpha = alpha)
    val rightFaceCol = lerp(v.color, Color.Black, (0.46f - v.emissive * 0.15f).coerceAtLeast(0f)).copy(alpha = alpha)

    // 1. Left-Front Vertical Quad (V3 -> V2)
    path.reset()
    path.moveTo(baseScreenX - r, topScreenY)
    path.lineTo(baseScreenX - halfR, topScreenY + dy)
    path.lineTo(baseScreenX - halfR, baseScreenY + dy)
    path.lineTo(baseScreenX - r, baseScreenY)
    path.close()
    drawPath(path, leftFaceCol)

    // 2. Center-Front Flat-Bottom Vertical Quad (V2 -> V1)
    path.reset()
    path.moveTo(baseScreenX - halfR, topScreenY + dy)
    path.lineTo(baseScreenX + halfR, topScreenY + dy)
    path.lineTo(baseScreenX + halfR, baseScreenY + dy)
    path.lineTo(baseScreenX - halfR, baseScreenY + dy)
    path.close()
    drawPath(path, frontFlatFaceCol)

    // 3. Right-Front Shadow Vertical Quad (V1 -> V0)
    path.reset()
    path.moveTo(baseScreenX + halfR, topScreenY + dy)
    path.lineTo(baseScreenX + r, topScreenY)
    path.lineTo(baseScreenX + r, baseScreenY)
    path.lineTo(baseScreenX + halfR, baseScreenY + dy)
    path.close()
    drawPath(path, rightFaceCol)

    // 4. Top Flat-Bottom Hexagonal Cap
    path.reset()
    path.moveTo(baseScreenX + r, topScreenY)
    path.lineTo(baseScreenX + halfR, topScreenY + dy)
    path.lineTo(baseScreenX - halfR, topScreenY + dy)
    path.lineTo(baseScreenX - r, topScreenY)
    path.lineTo(baseScreenX - halfR, topScreenY - dy)
    path.lineTo(baseScreenX + halfR, topScreenY - dy)
    path.close()
    drawPath(path, topCol)
  }
}

private fun DrawScope.drawDeterministicStarfield() {
  val count = 52
  for (i in 0 until count) {
    val fx = ((i * 73 + 19) % 100) / 100f
    val fy = ((i * 41 + 57) % 100) / 100f
    val alpha = 0.16f + ((i * 29) % 50) / 120f
    val rad = if (i % 7 == 0) 2.1f else 1.1f
    drawCircle(
      color = if (i % 5 == 0) Color(0xFF93C5FD).copy(alpha = alpha) else Color.White.copy(alpha = alpha),
      radius = rad,
      center = Offset(size.width * fx, size.height * fy),
    )
  }
}

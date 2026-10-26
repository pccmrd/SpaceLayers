package com.example.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.example.data.model.HexCellSeed
import com.example.domain.voxel.HexVoxelProceduralEngine
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Magnified rotatable 3D Hexagonal Voxel Loupe showing every individual tiny hexagonal prism
 * synthesized from the selected HexCellSeed's deterministic seed & formula.
 */
@Composable
fun HexInspectorLoupe(
  cell: HexCellSeed?,
  isShipPresent: Boolean,
  modifier: Modifier = Modifier,
) {
  var yawAngle by remember { mutableFloatStateOf(0.45f) }

  val voxels =
    remember(cell) {
      if (cell != null) HexVoxelProceduralEngine.compileHexCell(cell) else emptyList()
    }
  val shipVoxels = remember { HexVoxelProceduralEngine.compileCommanderShipVoxels() }

  Canvas(
    modifier =
      modifier.pointerInput(Unit) {
        detectHorizontalDragGestures { _, dragAmount ->
          yawAngle += dragAmount * 0.012f
        }
      }
  ) {
    drawRect(
      brush =
        Brush.radialGradient(
          colors = listOf(Color(0xFF132347), Color(0xFF070C18)),
          center = Offset(size.width * 0.5f, size.height * 0.58f),
          radius = size.maxDimension * 0.68f,
        )
    )

    val center = Offset(size.width * 0.5f, size.height * 0.66f)
    val outerR = min(size.width, size.height) * 0.42f
    val halfR = outerR * 0.5f
    val dy = outerR * (sqrt(3f) * 0.5f) * 0.56f
    val path = Path()

    // Base holographic flat-bottom hex pedestal
    path.moveTo(center.x + outerR, center.y)
    path.lineTo(center.x + halfR, center.y + dy)
    path.lineTo(center.x - halfR, center.y + dy)
    path.lineTo(center.x - outerR, center.y)
    path.lineTo(center.x - halfR, center.y - dy)
    path.lineTo(center.x + halfR, center.y - dy)
    path.close()
    drawPath(path, Color(0xFF0E1D3A))
    drawPath(path, Color(0xFF00E5FF).copy(alpha = 0.65f), style = Stroke(width = 2f))

    val yawCos = cos(yawAngle)
    val yawSin = sin(yawAngle)

    if (cell != null && voxels.isNotEmpty()) {
      val subRings = cell.voxelSubRings.toInt().coerceIn(2, 6)
      val microRadius = (outerR * 0.86f) / (2f * subRings + 1.25f)
      val zUnitPx = microRadius * 0.78f
      drawHexVoxelCluster(
        path = path,
        voxels = voxels,
        hexCenter = center,
        microRadius = microRadius,
        zUnitPx = zUnitPx,
        alpha = 1f,
        yawCos = yawCos,
        yawSin = yawSin,
      )
    }

    if (isShipPresent) {
      val shipMicro = (outerR * 0.48f) / 6f
      drawHexVoxelCluster(
        path = path,
        voxels = shipVoxels,
        hexCenter = center.copy(y = center.y - outerR * 0.42f),
        microRadius = shipMicro,
        zUnitPx = shipMicro * 0.85f,
        alpha = 1f,
        yawCos = yawCos,
        yawSin = yawSin,
      )
    }
  }
}

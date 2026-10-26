package com.example.domain.gravity

import com.example.data.model.HexCellSeed
import com.example.data.model.HexCoord
import com.example.data.model.SpaceLayer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class HexGravitySample(
  val coord: HexCoord,
  val layer: SpaceLayer,
  val wellSinkDepth: Float,
  val localGravityG: Float,
  val atmosphereDensity: Float,
  val pullVectorX: Float,
  val pullVectorY: Float,
  val dominantSourceTitle: String?,
)

object GravityWellPhysics {

  private val SQRT_3 = sqrt(3f)

  /**
   * Evaluates gravitational curvature (wellSinkDepth), local gravity pull (in Gs),
   * atmospheric drag, and 2D pull direction for every coordinate on [targetLayer]
   * based on all persistent [cells] across the 5 vertical layers.
   */
  fun computeLayerGravityField(
    coords: List<HexCoord>,
    targetLayer: SpaceLayer,
    cells: List<HexCellSeed>,
  ): Map<HexCoord, HexGravitySample> {
    val massiveCells = cells.filter { it.gravityMass > 0.05 }
    val cellAtLayerMap =
      cells.filter { it.layerIndex.toInt() == targetLayer.index }.associateBy { it.coord }

    val result = HashMap<HexCoord, HexGravitySample>(coords.size)

    for (coord in coords) {
      var potentialSum = 0f
      var pullX = 0f
      var pullY = 0f
      var strongestContribution = 0f
      var dominantTitle: String? = null

      val selfWorldX = 1.5f * coord.q
      val selfWorldY = SQRT_3 * (coord.r + coord.q * 0.5f)

      for (source in massiveCells) {
        val hexDist = coord.distanceTo(source.coord).toFloat()
        val layerDelta = abs(targetLayer.index - source.layerIndex.toInt()).toFloat()

        // Gravitational potential 1 / (1 + d) across horizontal hex lattice + vertical strata
        val falloff = 1f + hexDist * 0.95f + layerDelta * 0.32f
        val contribution = (source.gravityMass.toFloat()) / falloff
        potentialSum += contribution

        if (contribution > strongestContribution && source.title.isNotBlank()) {
          strongestContribution = contribution
          dominantTitle = source.title
        }

        if (hexDist > 0.01f) {
          val srcWorldX = 1.5f * source.q
          val srcWorldY = SQRT_3 * (source.r + source.q * 0.5f)
          val dx = srcWorldX - selfWorldX
          val dy = srcWorldY - selfWorldY
          val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
          val force = (source.gravityMass.toFloat()) / ((1f + hexDist) * (1f + hexDist))
          pullX += (dx / len) * force
          pullY += (dy / len) * force
        }
      }

      val directCell = cellAtLayerMap[coord]
      val elevationMod = (directCell?.elevationOffset?.toFloat() ?: 0f) * 0.28f

      // Flat Deep Space & Orbital layers visibly sink into smooth 3D funnels around gravity sources;
      // Surface & Core layers combine subtle basin curvature with physical terrain elevation.
      val curvatureWeight =
        when (targetLayer) {
          SpaceLayer.DEEP_SPACE -> 1.05f
          SpaceLayer.ORBITAL_WELL -> 0.90f
          SpaceLayer.ATMOSPHERE -> 0.55f
          SpaceLayer.SURFACE_TERRAIN -> 0.22f
          SpaceLayer.SUBSURFACE_CORE -> 0.15f
        }

      val sinkDepth = (potentialSum * curvatureWeight - elevationMod).coerceIn(-2.5f, 8.5f)
      val localG =
        (0.03f + potentialSum * 0.24f * targetLayer.baseGravityFactor).coerceIn(0.02f, 9.8f)

      val columnAtmos =
        cells
          .filter { it.coord == coord }
          .maxOfOrNull { it.atmosphereDensity.toFloat() } ?: 0f
      val effectiveAtmos =
        if (directCell != null && directCell.atmosphereDensity > 0.0) {
          directCell.atmosphereDensity.toFloat()
        } else {
          (columnAtmos * targetLayer.baseAtmosFactor).coerceIn(0f, 2f)
        }

      val pullMag = sqrt(pullX * pullX + pullY * pullY)
      val normPullX = if (pullMag > 0.001f) (pullX / pullMag) * pullMag.coerceAtMost(1.5f) else 0f
      val normPullY = if (pullMag > 0.001f) (pullY / pullMag) * pullMag.coerceAtMost(1.5f) else 0f

      result[coord] =
        HexGravitySample(
          coord = coord,
          layer = targetLayer,
          wellSinkDepth = sinkDepth,
          localGravityG = localG,
          atmosphereDensity = effectiveAtmos,
          pullVectorX = normPullX,
          pullVectorY = normPullY,
          dominantSourceTitle = dominantTitle,
        )
    }
    return result
  }

  /**
   * Calculates thruster MJ cost to navigate between two coordinates/layers.
   * Falling down into a gravity well gets a gravitational slingshot discount;
   * climbing out of a deep gravity well or ascending atmospheric strata costs extra thrust.
   */
  fun computeNavigationCost(
    fromCoord: HexCoord,
    fromLayer: SpaceLayer,
    toCoord: HexCoord,
    toLayer: SpaceLayer,
    fromSample: HexGravitySample?,
    toSample: HexGravitySample?,
  ): Long {
    val hexSteps = fromCoord.distanceTo(toCoord)
    val layerSteps = abs(toLayer.index - fromLayer.index)
    if (hexSteps == 0 && layerSteps == 0) return 0L

    val baseHorizontal = hexSteps * 14f
    val fromSink = fromSample?.wellSinkDepth ?: 0f
    val toSink = toSample?.wellSinkDepth ?: 0f

    // Climbing UP out of a deeper well (fromSink > toSink) costs extra escape thrust
    val wellClimbPenalty = max(0f, fromSink - toSink) * 16f
    // Falling INTO a deeper well grants gravity-assist efficiency
    val gravityAssistDiscount = max(0f, toSink - fromSink) * 5f

    // Ascending vertically (toLayer.index > fromLayer.index) fights gravity + atmospheric drag
    val verticalCost =
      if (toLayer.index > fromLayer.index) {
        val g = fromSample?.localGravityG ?: 1f
        val drag = fromSample?.atmosphereDensity ?: 0.2f
        layerSteps * (22f + g * 14f + drag * 12f)
      } else {
        // Controlled atmospheric descent / retro-burn
        layerSteps * 10f
      }

    return (baseHorizontal + wellClimbPenalty - gravityAssistDiscount + verticalCost)
      .roundToInt()
      .coerceAtLeast(6)
      .toLong()
  }
}

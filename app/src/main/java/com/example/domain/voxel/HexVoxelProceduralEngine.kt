package com.example.domain.voxel

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.example.data.model.HexCellSeed
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A single 3D hexagonal-base prism voxel inside a map hex's micro-grid (vq, vr).
 * Nothing is stored in the database except the parent HexCellSeed's seed & proceduralFormula;
 * these micro-hexagonal prisms are deterministically compiled in memory at runtime.
 */
data class HexPrismVoxel(
  val vq: Int,
  val vr: Int,
  val zBase: Float,
  val zHeight: Float,
  val color: Color,
  val emissive: Float = 0f,
)

object HexVoxelProceduralEngine {

  private val SQRT_3 = sqrt(3f)
  private val cache = LinkedHashMap<String, List<HexPrismVoxel>>(128, 0.75f, true)

  fun compileHexCell(cell: HexCellSeed): List<HexPrismVoxel> {
    val cacheKey =
      "${cell.id}|${cell.seed}|${cell.voxelSubRings}|${cell.proceduralFormula}|${cell.primaryColorHex}|${cell.secondaryColorHex}|${cell.accentColorHex}"
    synchronized(cache) {
      cache[cacheKey]?.let { return it }
    }

    val subRings = cell.voxelSubRings.toInt().coerceIn(2, 6)
    val primary = parseHexColor(cell.primaryColorHex, Color(0xFF00E5FF))
    val secondary = parseHexColor(cell.secondaryColorHex, Color(0xFF1E293B))
    val accent = parseHexColor(cell.accentColorHex, Color(0xFFFFB300))
    val formulaUpper = cell.proceduralFormula.uppercase()
    val archUpper = cell.archetype.uppercase()
    val seed = cell.seed

    val voxels = ArrayList<HexPrismVoxel>(160)

    val hasSphere = formulaUpper.contains("SPHERE") || archUpper.contains("CORE") || archUpper.contains("GIANT") || archUpper.contains("STAR")
    val hasTerrain = formulaUpper.contains("TERRAIN") || archUpper.contains("TERRAN") || archUpper.contains("BIOME") || archUpper.contains("SURFACE") || archUpper.contains("CAVERN")
    val hasCitadel = formulaUpper.contains("CITADEL") || archUpper.contains("CITADEL") || archUpper.contains("STATION") || archUpper.contains("OUTPOST")
    val hasVortex = formulaUpper.contains("VORTEX") || archUpper.contains("VORTEX") || archUpper.contains("ATMOS") || archUpper.contains("NEBULA")
    val hasVessel = formulaUpper.contains("VESSEL") || archUpper.contains("SHIP") || archUpper.contains("FLEET") || archUpper.contains("DREADNOUGHT")
    val hasRing = formulaUpper.contains("RING")
    val hasSpire = formulaUpper.contains("SPIRE") || archUpper.contains("CRYSTAL") || archUpper.contains("BEACON")
    val hasCrater = formulaUpper.contains("CRATER") || archUpper.contains("VOLCAN")

    // Additive evolution modifiers from gameplay actions
    val modColony = formulaUpper.contains("COLONY") || formulaUpper.contains("DOME")
    val modMining = formulaUpper.contains("MINING") || formulaUpper.contains("RIG") || formulaUpper.contains("DRILL")
    val modShield = formulaUpper.contains("SHIELD") || formulaUpper.contains("HALO")
    val modTerraform = formulaUpper.contains("TERRAFORM")

    val sphereRadius = extractNumber(formulaUpper, "R=", subRings - 1f).coerceIn(1.5f, subRings.toFloat())
    val peakHeight = extractNumber(formulaUpper, "PEAKS=", extractNumber(formulaUpper, "H=", 7f)).coerceIn(3f, 14f)
    val seaFraction = extractNumber(formulaUpper, "SEA=", 0.34f).coerceIn(0.05f, 0.75f)
    val spireCount = extractNumber(formulaUpper, "COUNT=", 4f).toInt().coerceIn(1, 6)

    for (vq in -subRings..subRings) {
      val rMin = max(-subRings, -vq - subRings)
      val rMax = min(subRings, -vq + subRings)
      for (vr in rMin..rMax) {
        val hexDist = max(abs(vq), max(abs(vr), abs(-vq - vr)))
        val lx = 1.5f * vq
        val ly = SQRT_3 * (vr + vq * 0.5f)
        val radialDist = sqrt(lx * lx + ly * ly) / SQRT_3
        val angle = atan2(ly, lx)
        val n1 = deterministicNoise(vq, vr, seed)
        val n2 = deterministicNoise(vq * 2 + 3, vr * 2 - 5, seed xor 0x5DEECE66DL)

        // 1. Planetary Terrain / Cavern Strata
        if (hasTerrain) {
          val ridge = (0.5f + 0.5f * sin(lx * 0.85f + (seed % 17))) * 0.5f + n1 * 0.5f
          val rawH = ridge * peakHeight
          val seaH = seaFraction * peakHeight
          val finalH = max(rawH, seaH)
          val color =
            when {
              rawH <= seaH ->
                if (modTerraform) lerp(Color(0xFF0284C7), Color(0xFF22D3EE), n2)
                else lerp(secondary, primary, 0.25f)
              rawH < seaH + 1.6f -> lerp(primary, accent, 0.30f)
              rawH < peakHeight * 0.78f ->
                if (modTerraform) lerp(Color(0xFF10B981), primary, n2 * 0.5f)
                else lerp(primary, secondary, n2 * 0.45f)
              else -> lerp(accent, Color.White, 0.45f)
            }
          voxels +=
            HexPrismVoxel(
              vq = vq,
              vr = vr,
              zBase = 0f,
              zHeight = finalH.coerceAtLeast(1.0f),
              color = color,
              emissive = if (rawH > peakHeight * 0.85f) 0.25f else 0f,
            )
        }

        // 2. Spherical Core / Gas Giant / Asteroid Body
        if (hasSphere && radialDist <= sphereRadius + 0.35f) {
          val normR = (radialDist / (sphereRadius + 0.4f)).coerceIn(0f, 1f)
          val domeProfile = sqrt((1f - normR * normR).coerceAtLeast(0.04f))
          var h = domeProfile * peakHeight * (0.85f + n1 * 0.3f)
          var z0 = (1f - domeProfile) * 1.4f
          var col = lerp(primary, secondary, (normR * 0.8f + n2 * 0.2f).coerceIn(0f, 1f))
          var em = if (archUpper.contains("STAR") || archUpper.contains("CORE")) 0.55f else 0.12f

          if (hasCrater && hexDist <= max(1, subRings / 2)) {
            h = (h * 0.42f).coerceAtLeast(1.2f)
            col = accent
            em = 0.75f
          }
          voxels +=
            HexPrismVoxel(
              vq = vq,
              vr = vr,
              zBase = z0,
              zHeight = h.coerceAtLeast(1.0f),
              color = col,
              emissive = em,
            )
        }

        // 3. Radial Citadel / Space Station / Tether Hub
        if (hasCitadel) {
          val onAxis = (vq == 0 || vr == 0 || (vq + vr) == 0)
          val onOuterHub = (hexDist == subRings - 1)
          if (hexDist <= 1) {
            voxels +=
              HexPrismVoxel(
                vq = vq,
                vr = vr,
                zBase = 1.5f,
                zHeight = peakHeight * 1.05f,
                color = lerp(primary, accent, if (hexDist == 0) 0.7f else 0.2f),
                emissive = if (hexDist == 0) 0.8f else 0.3f,
              )
          } else if (onAxis && hexDist <= subRings) {
            voxels +=
              HexPrismVoxel(
                vq = vq,
                vr = vr,
                zBase = 2.4f,
                zHeight = 2.6f + (subRings - hexDist) * 0.5f,
                color = secondary,
                emissive = if (hexDist == subRings) 0.65f else 0.1f,
              )
          } else if (onOuterHub) {
            voxels +=
              HexPrismVoxel(
                vq = vq,
                vr = vr,
                zBase = 2.8f,
                zHeight = 1.8f,
                color = lerp(secondary, primary, 0.55f),
                emissive = 0.25f,
              )
          }
        }

        // 4. Spiral Atmospheric Vortex / Nebula Clouds
        if (hasVortex && !hasSphere && !hasTerrain) {
          val spiralPhase = sin(angle * 3f + radialDist * 1.4f + (seed % 11))
          if (spiralPhase > -0.25f || hexDist <= 1) {
            val cloudZ = 1.2f + (1f - (hexDist.toFloat() / (subRings + 1f))) * 3.2f + n1 * 1.5f
            val cloudH = 1.4f + (spiralPhase + 0.3f) * 2.2f
            val col =
              if (hexDist == 0) accent
              else lerp(primary, secondary, ((spiralPhase + 1f) * 0.5f).coerceIn(0f, 1f))
            voxels +=
              HexPrismVoxel(
                vq = vq,
                vr = vr,
                zBase = cloudZ,
                zHeight = cloudH,
                color = col,
                emissive = if (hexDist <= 1) 0.5f else 0.18f,
              )
          }
        }

        // 5. Custom Vessel / Fleet / Rig Archetype
        if (hasVessel && !hasTerrain && !hasSphere) {
          val spine = (vr == 0 && vq in -subRings..subRings)
          val wings = (abs(vr) <= 2 && vq in -2..1 && abs(vq + vr) <= 2)
          if (spine || wings) {
            val isEngine = vq <= -subRings + 1
            val h = if (spine) 3.4f else 1.8f
            voxels +=
              HexPrismVoxel(
                vq = vq,
                vr = vr,
                zBase = 2.5f,
                zHeight = h,
                color = if (isEngine) accent else if (spine) primary else secondary,
                emissive = if (isEngine) 0.9f else 0.2f,
              )
          }
        }

        // 6. Floating Orbital / Planetary Hex-Voxel Ring
        if (hasRing && hexDist == subRings) {
          val tiltWave = sin(angle + (seed % 7)) * 1.3f
          voxels +=
            HexPrismVoxel(
              vq = vq,
              vr = vr,
              zBase = (4.2f + tiltWave).coerceAtLeast(0.8f),
              zHeight = 1.15f + n2 * 0.6f,
              color = lerp(accent, primary, n1),
              emissive = 0.45f,
            )
        }

        // 7. Crystalline / Antenna Spires
        if (hasSpire) {
          val isAnchor =
            (vq == 0 && vr == 0) ||
              (hexDist == max(1, subRings - 2) &&
                ((abs(vq * 3 + vr * 5 + (seed % 7).toInt())) % (7 - spireCount.coerceAtMost(5)) == 0))
          if (isAnchor) {
            val spireH = peakHeight * (if (vq == 0 && vr == 0) 1.35f else 0.95f)
            voxels +=
              HexPrismVoxel(
                vq = vq,
                vr = vr,
                zBase = if (hasTerrain || hasSphere) 2.5f else 0.5f,
                zHeight = spireH,
                color = lerp(accent, Color.White, 0.35f),
                emissive = 0.72f,
              )
          }
        }

        // 8. Additive Gameplay Modifiers (Colony Biodomes, Mining Drill, Shield Halo)
        if (modColony && hexDist in 1..2 && (vq + vr * 2) % 2 == 0) {
          voxels +=
            HexPrismVoxel(
              vq = vq,
              vr = vr,
              zBase = peakHeight * 0.55f,
              zHeight = 3.2f,
              color = Color(0xFF38BDF8),
              emissive = 0.65f,
            )
        }
        if (modMining && vq == 0 && vr == 0) {
          voxels +=
            HexPrismVoxel(
              vq = 0,
              vr = 0,
              zBase = 0f,
              zHeight = peakHeight + 5.5f,
              color = Color(0xFFFFB300),
              emissive = 0.95f,
            )
        }
        if (modShield && hexDist == subRings - 1 && (vq - vr) % 2 == 0) {
          voxels +=
            HexPrismVoxel(
              vq = vq,
              vr = vr,
              zBase = peakHeight + 2.5f,
              zHeight = 1.2f,
              color = Color(0xFF22D3EE),
              emissive = 0.85f,
            )
        }
      }
    }

    // Fallback sculpture if custom formula had completely novel keywords
    if (voxels.isEmpty()) {
      for (vq in -subRings..subRings) {
        val rMin = max(-subRings, -vq - subRings)
        val rMax = min(subRings, -vq + subRings)
        for (vr in rMin..rMax) {
          val dist = max(abs(vq), max(abs(vr), abs(-vq - vr)))
          val n = deterministicNoise(vq, vr, seed)
          if (dist <= subRings - 1 || n > 0.45f) {
            val h = (subRings - dist + 1) * 1.6f + n * 2.5f
            voxels +=
              HexPrismVoxel(
                vq = vq,
                vr = vr,
                zBase = 0.5f,
                zHeight = h,
                color = lerp(primary, accent, (dist.toFloat() / subRings).coerceIn(0f, 1f)),
                emissive = if (dist == 0) 0.7f else 0.15f,
              )
          }
        }
      }
    }

    synchronized(cache) {
      if (cache.size > 120) {
        val eldest = cache.entries.firstOrNull()?.key
        if (eldest != null) cache.remove(eldest)
      }
      cache[cacheKey] = voxels
    }
    return voxels
  }

  /**
   * Synthesizes the Commander's Flagship out of tiny hexagonal prism voxels!
   */
  fun compileCommanderShipVoxels(): List<HexPrismVoxel> {
    val hull = Color(0xFFE2E8F0)
    val trim = Color(0xFF0284C7)
    val thruster = Color(0xFF00E5FF)
    val bridge = Color(0xFFFFB300)

    return listOf(
      // Nose & Forward Fuselage
      HexPrismVoxel(vq = 2, vr = 0, zBase = 5.5f, zHeight = 1.6f, color = hull, emissive = 0.3f),
      HexPrismVoxel(vq = 1, vr = 0, zBase = 5.2f, zHeight = 2.4f, color = hull, emissive = 0.2f),
      // Central Command Bridge
      HexPrismVoxel(vq = 0, vr = 0, zBase = 5.0f, zHeight = 3.4f, color = bridge, emissive = 0.8f),
      // Swept Port & Starboard Hex-Wings
      HexPrismVoxel(vq = 0, vr = -1, zBase = 5.2f, zHeight = 1.6f, color = trim, emissive = 0.25f),
      HexPrismVoxel(vq = -1, vr = 0, zBase = 5.2f, zHeight = 2.2f, color = hull, emissive = 0.2f),
      HexPrismVoxel(vq = -1, vr = 1, zBase = 5.2f, zHeight = 1.6f, color = trim, emissive = 0.25f),
      HexPrismVoxel(vq = -1, vr = -1, zBase = 5.4f, zHeight = 1.3f, color = trim, emissive = 0.3f),
      HexPrismVoxel(vq = 0, vr = 1, zBase = 5.4f, zHeight = 1.3f, color = trim, emissive = 0.3f),
      // Twin Ion Thruster Nacelles
      HexPrismVoxel(vq = -2, vr = 0, zBase = 5.3f, zHeight = 2.0f, color = thruster, emissive = 0.95f),
      HexPrismVoxel(vq = -2, vr = 1, zBase = 5.3f, zHeight = 2.0f, color = thruster, emissive = 0.95f),
    )
  }

  fun parseHexColor(hex: String, fallback: Color): Color {
    val clean = hex.trim().removePrefix("#")
    return try {
      when (clean.length) {
        6 -> {
          val rgb = clean.toLong(16)
          Color((0xFF000000L or rgb).toInt())
        }
        8 -> {
          val argb = clean.toLong(16)
          Color(argb.toInt())
        }
        else -> fallback
      }
    } catch (_: Exception) {
      fallback
    }
  }

  private fun deterministicNoise(vq: Int, vr: Int, seed: Long): Float {
    var h = seed xor (vq.toLong() * 374761393L) xor (vr.toLong() * 668265263L)
    h = (h xor (h ushr 13)) * 1274126177L
    h = h xor (h ushr 16)
    val positive = (h and 0x7FFFFFFFL).toFloat()
    return (positive / Int.MAX_VALUE.toFloat()).coerceIn(0f, 1f)
  }

  private fun extractNumber(formula: String, key: String, default: Float): Float {
    val idx = formula.indexOf(key)
    if (idx < 0) return default
    val start = idx + key.length
    val sb = StringBuilder()
    for (i in start until formula.length) {
      val c = formula[i]
      if (c.isDigit() || c == '.' || (c == '-' && sb.isEmpty())) {
        sb.append(c)
      } else {
        break
      }
    }
    return sb.toString().toFloatOrNull() ?: default
  }
}

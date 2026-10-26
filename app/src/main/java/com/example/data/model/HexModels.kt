package com.example.data.model

import com.example.R
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlin.math.abs
import kotlin.math.max

enum class SpaceLayer(
  val index: Int,
  val shortTag: String,
  val titleRes: Int,
  val subtitleRes: Int,
  val baseGravityFactor: Float,
  val baseAtmosFactor: Float,
) {
  SUBSURFACE_CORE(
    index = 0,
    shortTag = "L0 CORE",
    titleRes = R.string.layer_subsurface_title,
    subtitleRes = R.string.layer_subsurface_sub,
    baseGravityFactor = 2.25f,
    baseAtmosFactor = 1.0f,
  ),
  SURFACE_TERRAIN(
    index = 1,
    shortTag = "L1 SURF",
    titleRes = R.string.layer_surface_title,
    subtitleRes = R.string.layer_surface_sub,
    baseGravityFactor = 1.55f,
    baseAtmosFactor = 0.85f,
  ),
  ATMOSPHERE(
    index = 2,
    shortTag = "L2 ATMO",
    titleRes = R.string.layer_atmosphere_title,
    subtitleRes = R.string.layer_atmosphere_sub,
    baseGravityFactor = 1.10f,
    baseAtmosFactor = 0.65f,
  ),
  ORBITAL_WELL(
    index = 3,
    shortTag = "L3 ORB",
    titleRes = R.string.layer_orbital_title,
    subtitleRes = R.string.layer_orbital_sub,
    baseGravityFactor = 0.65f,
    baseAtmosFactor = 0.18f,
  ),
  DEEP_SPACE(
    index = 4,
    shortTag = "L4 VOID",
    titleRes = R.string.layer_space_title,
    subtitleRes = R.string.layer_space_sub,
    baseGravityFactor = 0.28f,
    baseAtmosFactor = 0.0f,
  );

  companion object {
    fun fromIndex(index: Int): SpaceLayer =
      entries.firstOrNull { it.index == index } ?: DEEP_SPACE
  }
}

data class HexCoord(val q: Int, val r: Int) {
  val s: Int
    get() = -q - r

  fun distanceTo(other: HexCoord): Int {
    val dq = abs(q - other.q)
    val dr = abs(r - other.r)
    val ds = abs(s - other.s)
    return max(dq, max(dr, ds))
  }

  fun neighbors(): List<HexCoord> =
    DIRECTIONS.map { (dq, dr) -> HexCoord(q + dq, r + dr) }

  companion object {
    val DIRECTIONS =
      listOf(
        1 to 0,
        1 to -1,
        0 to -1,
        -1 to 0,
        -1 to 1,
        0 to 1,
      )

    fun spiralGrid(radius: Int): List<HexCoord> {
      val list = ArrayList<HexCoord>()
      for (q in -radius..radius) {
        val r1 = max(-radius, -q - radius)
        val r2 = kotlin.math.min(radius, -q + radius)
        for (r in r1..r2) {
          list.add(HexCoord(q, r))
        }
      }
      return list
    }
  }
}

fun buildCellDocId(sectorId: String, layerIndex: Int, q: Int, r: Int): String {
  val qToken = if (q < 0) "m${abs(q)}" else "p$q"
  val rToken = if (r < 0) "m${abs(r)}" else "p$r"
  return "${sectorId}_L${layerIndex}_${qToken}_${rToken}"
}

@IgnoreExtraProperties
data class SectorState(
  @DocumentId val id: String = "",
  val userId: String = "",
  val name: String = "",
  val galaxySeed: Long = 0L,
  val shipQ: Long = 0L,
  val shipR: Long = 0L,
  val shipLayer: Long = 4L,
  val energy: Long = 1000L,
  val turnNumber: Long = 1L,
  val lastEventLog: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class HexCellSeed(
  @DocumentId val id: String = "",
  val userId: String = "",
  val sectorId: String = "",
  val q: Long = 0L,
  val r: Long = 0L,
  val layerIndex: Long = 4L,
  val seed: Long = 1001L,
  val archetype: String = "VOID_NEBULA",
  val voxelSubRings: Long = 4L,
  val proceduralFormula: String = "ARCH=VOID_NEBULA;RINGS=4;NOISE=0.2",
  val gravityMass: Double = 0.0,
  val atmosphereDensity: Double = 0.0,
  val elevationOffset: Double = 0.0,
  val primaryColorHex: String = "#00E5FF",
  val secondaryColorHex: String = "#1E293B",
  val accentColorHex: String = "#FFB300",
  val title: String = "",
  val loreSummary: String = "",
  val lastActionContext: String = "",
  val evolutionCount: Long = 1L,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
) {
  @get:Exclude
  val coord: HexCoord
    get() = HexCoord(q.toInt(), r.toInt())
}

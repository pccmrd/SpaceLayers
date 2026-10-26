package com.example.data.ai

import android.util.Log
import com.example.data.model.HexCellSeed
import com.example.data.model.HexCoord
import com.example.data.model.SectorState
import com.example.data.model.SpaceLayer
import com.example.data.model.buildCellDocId
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "HexProceduralAiService"

class HexProceduralAiService {

  private val singleHexSchema =
    Schema.obj(
      properties =
        mapOf(
          "title" to Schema.string(description = "Evocative sci-fi name (3-60 chars)"),
          "archetype" to
            Schema.enumeration(
              values =
                listOf(
                  "SINGULARITY_CORE",
                  "TERRAN_BIOME",
                  "ATMOS_VORTEX",
                  "ORBITAL_CITADEL",
                  "GRAVITY_BEACON",
                  "CRYSTAL_ASTEROID",
                  "GAS_GIANT_WELL",
                  "PLAYER_VESSEL_FLEET",
                  "VOID_NEBULA",
                ),
              description = "Primary hexagonal voxel archetype",
            ),
          "seed" to Schema.integer(description = "Deterministic positive integer PRNG seed"),
          "voxelSubRings" to
            Schema.integer(
              description = "Micro-hexagonal voxel resolution rings from 3 to 6 (higher = tinier, finer hex voxels)"
            ),
          "proceduralFormula" to
            Schema.string(
              description =
                "Compact procedural voxel DSL string combining primitives like SPHERE(r=4,h=8), TERRAIN(rough=0.6,sea=0.35,peaks=9), CITADEL(arms=6,tiers=3), VORTEX(arms=3,lift=6), RING(rIn=3,rOut=5,z=5), SPIRE(count=4,h=9), CRATER(r=2,depth=3), VESSEL(wings=2,hull=4), and optional MOD=COLONY_DOMES+MINING_RIG+SHIELD_HALO+TERRAFORM"
            ),
          "gravityMass" to
            Schema.double(
              description = "Gravitational pull mass from 0.0 (weightless) to 12.0 (massive gravity well)"
            ),
          "atmosphereDensity" to
            Schema.double(description = "Atmospheric density from 0.0 (vacuum) to 1.8 (thick atmosphere)"),
          "elevationOffset" to
            Schema.double(description = "Vertical height/depth offset from -5.0 to 5.0"),
          "primaryColorHex" to Schema.string(description = "6-digit hex color with leading #, e.g. #00E5FF"),
          "secondaryColorHex" to Schema.string(description = "6-digit hex color with leading #, e.g. #1E293B"),
          "accentColorHex" to Schema.string(description = "6-digit hex color with leading #, e.g. #FFB300"),
          "loreSummary" to
            Schema.string(
              description = "1-2 vivid sentences explaining how this hex reflects player actions and local gravity"
            ),
        )
    )

  private val multiLayerWorldSchema =
    Schema.obj(
      properties =
        mapOf(
          "worldName" to Schema.string(description = "Name of the celestial body or phenomenon"),
          "layers" to
            Schema.array(
              items =
                Schema.obj(
                  properties =
                    mapOf(
                      "layerIndex" to
                        Schema.integer(
                          description = "0=Subsurface Core, 1=Surface Terrain, 2=Atmosphere, 3=Orbital Well, 4=Deep Space Lens"
                        ),
                      "title" to Schema.string(description = "Layer feature title"),
                      "archetype" to Schema.string(description = "Archetype code"),
                      "seed" to Schema.integer(description = "Deterministic integer seed"),
                      "voxelSubRings" to Schema.integer(description = "Sub-rings from 4 to 6"),
                      "proceduralFormula" to Schema.string(description = "Compact voxel DSL code"),
                      "gravityMass" to Schema.double(description = "Gravity mass 0.0 to 12.0"),
                      "atmosphereDensity" to Schema.double(description = "Atmosphere density 0.0 to 1.8"),
                      "elevationOffset" to Schema.double(description = "Elevation offset -5.0 to 5.0"),
                      "primaryColorHex" to Schema.string(description = "#RRGGBB hex color"),
                      "secondaryColorHex" to Schema.string(description = "#RRGGBB hex color"),
                      "accentColorHex" to Schema.string(description = "#RRGGBB hex color"),
                      "loreSummary" to Schema.string(description = "Concise layer description"),
                    )
                ),
              description = "Exactly 5 vertical layer definitions for layerIndex 0, 1, 2, 3, and 4",
            ),
        )
    )

  private val singleHexModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      generationConfig =
        generationConfig {
          responseMimeType = "application/json"
          responseSchema = singleHexSchema
        },
    )
  }

  private val multiLayerModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      generationConfig =
        generationConfig {
          responseMimeType = "application/json"
          responseSchema = multiLayerWorldSchema
        },
    )
  }

  suspend fun synthesizeOrEvolveHex(
    userId: String,
    sector: SectorState,
    targetCoord: HexCoord,
    targetLayer: SpaceLayer,
    existingCell: HexCellSeed?,
    nearbyCells: List<HexCellSeed>,
    actionOrPrompt: String,
  ): Result<HexCellSeed> {
    return try {
      val nearbyContext =
        nearbyCells.take(6).joinToString("; ") {
          "L${it.layerIndex}(${it.q},${it.r}): ${it.title} [mass=${it.gravityMass}, arch=${it.archetype}]"
        }

      val existingBlock =
        if (existingCell != null) {
          """
          EXISTING PERSISTENT HEX MEMORY (MUST REUSE/EVOLVE RATHER THAN DISCARDING):
          - Title: ${existingCell.title}
          - Seed: ${existingCell.seed}
          - Archetype: ${existingCell.archetype}
          - Previous Formula: ${existingCell.proceduralFormula}
          - Gravity Mass: ${existingCell.gravityMass}
          - Evolution Count: ${existingCell.evolutionCount}
          - Previous Lore: ${existingCell.loreSummary}
          Update the proceduralFormula (e.g. preserving base primitives while adding/adjusting SPHERE, TERRAIN, CITADEL, VORTEX, RING, SPIRE, CRATER, VESSEL, or MOD=COLONY_DOMES+MINING_RIG+SHIELD_HALO+TERRAFORM) and colors to show how the new circumstance transforms this hex!
          """.trimIndent()
        } else {
          "UNCHARTED HEX: Generate a brand-new deterministic seed and rich hexagonal voxel proceduralFormula suited for this vertical layer."
        }

      val prompt =
        """
        You are the deterministic procedural seed & hexagonal-voxel code compiler for HexWell, a multi-layer space map.
        We store ZERO 3D meshes—only your seed and compact proceduralFormula DSL!
        
        CURRENT GAME CIRCUMSTANCES:
        - Sector: ${sector.name} (Turn ${sector.turnNumber}, Ship at Q=${sector.shipQ}, R=${sector.shipR}, Layer=${sector.shipLayer}, Energy=${sector.energy} MJ)
        - Target Hex: Q=${targetCoord.q}, R=${targetCoord.r}, Layer=${targetLayer.index} (${targetLayer.name})
        - Nearby Sector Gravity/Strata Context: ${nearbyContext.ifBlank { "Open cosmic lattice" }}
        - Player Action / Input: "$actionOrPrompt"
        
        $existingBlock
        
        Use voxelSubRings between 4 and 6 so the tiny hexagonal-base voxels represent fine details beautifully.
        Ensure primaryColorHex, secondaryColorHex, and accentColorHex are valid 6-digit #RRGGBB strings.
        """.trimIndent()

      val response = withTimeout(60_000L) { singleHexModel.generateContent(prompt) }
      val rawJson =
        response.text?.takeIf { it.isNotBlank() }
          ?: return Result.failure(IllegalStateException("Gemini returned an empty procedural response."))

      val obj = JSONObject(rawJson)
      val cellId =
        existingCell?.id?.ifBlank { null }
          ?: buildCellDocId(sector.id, targetLayer.index, targetCoord.q, targetCoord.r)

      val cell =
        HexCellSeed(
          id = cellId,
          userId = userId,
          sectorId = sector.id,
          q = targetCoord.q.toLong(),
          r = targetCoord.r.toLong(),
          layerIndex = targetLayer.index.toLong(),
          seed = abs(obj.optLong("seed", System.currentTimeMillis() % 999999L)) + 1L,
          archetype = sanitizeBounded(obj.optString("archetype", "ORBITAL_CITADEL"), 40, "ORBITAL_CITADEL"),
          voxelSubRings = obj.optLong("voxelSubRings", 5L).coerceIn(3L, 6L),
          proceduralFormula =
            sanitizeBounded(
              obj.optString("proceduralFormula", "ARCH=ORBITAL_CITADEL;CITADEL(arms=6,tiers=3);SPIRE(count=3,h=8)"),
              1150,
              "ARCH=ORBITAL_CITADEL;SPIRE(count=3,h=8)",
            ),
          gravityMass = obj.optDouble("gravityMass", 2.0).coerceIn(0.0, 14.5),
          atmosphereDensity = obj.optDouble("atmosphereDensity", 0.3).coerceIn(0.0, 1.9),
          elevationOffset = obj.optDouble("elevationOffset", 0.0).coerceIn(-9.0, 9.0),
          primaryColorHex = sanitizeHexColor(obj.optString("primaryColorHex", "#00E5FF"), "#00E5FF"),
          secondaryColorHex = sanitizeHexColor(obj.optString("secondaryColorHex", "#1E293B"), "#1E293B"),
          accentColorHex = sanitizeHexColor(obj.optString("accentColorHex", "#FFB300"), "#FFB300"),
          title = sanitizeBounded(obj.optString("title", "Synthesized Hex Anomaly"), 75, "Hex Anomaly"),
          loreSummary =
            sanitizeBounded(
              obj.optString("loreSummary", "Procedurally synthesized hexagonal voxel formation."),
              580,
              "Procedurally synthesized hexagonal voxel formation.",
            ),
          lastActionContext = actionOrPrompt.take(380),
          evolutionCount = (existingCell?.evolutionCount ?: 0L) + 1L,
          createdAt = existingCell?.createdAt,
          updatedAt = null,
        )
      Result.success(cell)
    } catch (e: TimeoutCancellationException) {
      Log.e(TAG, "Hex procedural generation timed out", e)
      Result.failure(IllegalStateException("Procedural synthesis timed out after 60 seconds. Please try again."))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Hex procedural generation failed", e)
      Result.failure(e)
    }
  }

  suspend fun synthesizeFiveLayerWorld(
    userId: String,
    sector: SectorState,
    targetCoord: HexCoord,
    existingColumn: List<HexCellSeed>,
    worldPrompt: String,
  ): Result<List<HexCellSeed>> {
    return try {
      val existingByLayer = existingColumn.associateBy { it.layerIndex.toInt() }
      val prompt =
        """
        Synthesize a cohesive 5-layer vertical celestial world column at hex (Q=${targetCoord.q}, R=${targetCoord.r}) in sector "${sector.name}".
        Player directive: "$worldPrompt"
        
        Generate all 5 vertical layers (layerIndex 0, 1, 2, 3, 4):
        - Layer 0 (Subsurface & Core): High gravityMass (6.0-12.0), magma/crystal/singularity core using SPHERE, CRATER, SPIRE.
        - Layer 1 (Planetary Surface): Multi-height biomes/oceans/peaks using TERRAIN(rough=...,sea=...,peaks=...), SPIRE, COLONY_DOMES.
        - Layer 2 (Atmosphere): Swirling cloud decks, storms, aerostats using VORTEX(arms=...,lift=...), RING, high atmosphereDensity.
        - Layer 3 (Orbital Capture Well): Orbital rings, stations, shipyards, satellites using CITADEL, RING, VESSEL.
        - Layer 4 (Deep Space Gravity Lens): Top of the gravity well funnel in deep space using GRAVITY_BEACON, RING, SPIRE.
        
        Use voxelSubRings 5 or 6 for intricate micro-hexagonal voxel detail, and valid 6-digit #RRGGBB colors.
        """.trimIndent()

      val response = withTimeout(60_000L) { multiLayerModel.generateContent(prompt) }
      val rawJson =
        response.text?.takeIf { it.isNotBlank() }
          ?: return Result.failure(IllegalStateException("Gemini returned empty multi-layer response."))

      val root = JSONObject(rawJson)
      val arr = root.getJSONArray("layers")
      val results = ArrayList<HexCellSeed>(5)
      for (i in 0 until arr.length()) {
        val item = arr.getJSONObject(i)
        val layerIdx = item.optInt("layerIndex", i).coerceIn(0, 4)
        val prev = existingByLayer[layerIdx]
        val docId =
          prev?.id?.ifBlank { null }
            ?: buildCellDocId(sector.id, layerIdx, targetCoord.q, targetCoord.r)

        results +=
          HexCellSeed(
            id = docId,
            userId = userId,
            sectorId = sector.id,
            q = targetCoord.q.toLong(),
            r = targetCoord.r.toLong(),
            layerIndex = layerIdx.toLong(),
            seed = abs(item.optLong("seed", System.currentTimeMillis() + layerIdx * 97L)) + 1L,
            archetype = sanitizeBounded(item.optString("archetype", "TERRAN_BIOME"), 40, "TERRAN_BIOME"),
            voxelSubRings = item.optLong("voxelSubRings", 5L).coerceIn(4L, 6L),
            proceduralFormula =
              sanitizeBounded(
                item.optString("proceduralFormula", "SPHERE(r=4,h=8);RING(rIn=3,rOut=5,z=5)"),
                1150,
                "SPHERE(r=4,h=8)",
              ),
            gravityMass = item.optDouble("gravityMass", (5 - layerIdx) * 1.8).coerceIn(0.0, 14.5),
            atmosphereDensity = item.optDouble("atmosphereDensity", 0.5).coerceIn(0.0, 1.9),
            elevationOffset = item.optDouble("elevationOffset", 0.0).coerceIn(-9.0, 9.0),
            primaryColorHex = sanitizeHexColor(item.optString("primaryColorHex", "#00E5FF"), "#00E5FF"),
            secondaryColorHex = sanitizeHexColor(item.optString("secondaryColorHex", "#1E293B"), "#1E293B"),
            accentColorHex = sanitizeHexColor(item.optString("accentColorHex", "#FFB300"), "#FFB300"),
            title = sanitizeBounded(item.optString("title", "Layer $layerIdx Formation"), 75, "Layer Formation"),
            loreSummary =
              sanitizeBounded(
                item.optString("loreSummary", "Synthesized vertical strata layer."),
                580,
                "Synthesized vertical strata layer.",
              ),
            lastActionContext = worldPrompt.take(380),
            evolutionCount = (prev?.evolutionCount ?: 0L) + 1L,
            createdAt = prev?.createdAt,
            updatedAt = null,
          )
      }
      Result.success(results)
    } catch (e: TimeoutCancellationException) {
      Log.e(TAG, "Multi-layer world synthesis timed out", e)
      Result.failure(IllegalStateException("World synthesis timed out after 60 seconds. Please try again."))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Multi-layer world synthesis failed", e)
      Result.failure(e)
    }
  }

  private fun sanitizeBounded(raw: String, maxLen: Int, fallback: String): String {
    val trimmed = raw.trim()
    return if (trimmed.isEmpty()) fallback else trimmed.take(maxLen)
  }

  private fun sanitizeHexColor(raw: String, fallback: String): String {
    val trimmed = raw.trim()
    val candidate = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
    val regex = Regex("^#[0-9a-fA-F]{6}$")
    return if (regex.matches(candidate)) candidate.uppercase() else fallback
  }
}

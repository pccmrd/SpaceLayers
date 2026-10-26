package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.HexCellSeed
import com.example.data.model.SectorState
import com.example.data.model.buildCellDocId
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class HexSectorRepository(private val db: FirebaseFirestore) {

  constructor(
    context: Context
  ) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    )
  )

  private val auth
    get() = Firebase.auth

  private fun requireUserId(): String =
    auth.currentUser?.uid
      ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")

  fun observeSectorState(userId: String, sectorId: String): Flow<SectorState?> {
    val docRef = db.collection("users").document(userId).collection("sectors").document(sectorId)
    return docRef
      .snapshots()
      .map { snapshot ->
        if (snapshot.exists()) {
          snapshot.toObject(SectorState::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } else {
          null
        }
      }
      .catch { error ->
        if (error is Exception) {
          handleFirestoreError(error, OperationType.GET, docRef.path)
        }
        throw error
      }
  }

  fun observeSectorHexCells(userId: String, sectorId: String): Flow<List<HexCellSeed>> {
    val collectionPath = "users/$userId/hex_cells"
    return db
      .collection("users")
      .document(userId)
      .collection("hex_cells")
      .whereEqualTo("userId", userId)
      .whereEqualTo("sectorId", sectorId)
      .snapshots()
      .map { querySnapshot ->
        querySnapshot.toObjects(
          HexCellSeed::class.java,
          DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
        )
      }
      .catch { error ->
        if (error is Exception) {
          handleFirestoreError(error, OperationType.LIST, collectionPath)
        }
        throw error
      }
  }

  suspend fun getSectorById(targetUserId: String, sectorId: String): Result<SectorState?> {
    val docRef =
      db.collection("users").document(targetUserId).collection("sectors").document(sectorId)
    return try {
      val snapshot = docRef.get().await()
      val state =
        if (snapshot.exists()) {
          snapshot.toObject(
            SectorState::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          )
        } else {
          null
        }
      Result.success(state)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun createOrBootstrapSector(
    sectorId: String,
    sectorName: String,
    galaxySeed: Long,
  ): Result<SectorState> {
    val uid = requireUserId()
    val sectorRef = db.collection("users").document(uid).collection("sectors").document(sectorId)
    return try {
      val existingSnap = sectorRef.get().await()
      if (existingSnap.exists()) {
        val loaded =
          existingSnap.toObject(
            SectorState::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          )
        if (loaded != null) return Result.success(loaded)
      }

      val initialSector =
        SectorState(
          id = sectorId,
          userId = uid,
          name = sectorName.take(60),
          galaxySeed = galaxySeed,
          shipQ = 2L,
          shipR = 0L,
          shipLayer = 4L,
          energy = 1200L,
          turnNumber = 1L,
          lastEventLog = "Arrived at $sectorName rim. Gravity well detected at (0, 0).",
          createdAt = null,
          updatedAt = null,
        )

      val starterCells = buildStarterSolarBasin(uid, sectorId, galaxySeed)
      val batch = db.batch()
      batch.set(sectorRef, initialSector)
      for (cell in starterCells) {
        val cellRef =
          db.collection("users").document(uid).collection("hex_cells").document(cell.id)
        batch.set(cellRef, cell)
      }
      batch.commit().await()

      val saved =
        sectorRef
          .get()
          .await()
          .toObject(SectorState::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
          ?: initialSector
      Result.success(saved)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, sectorRef.path)
      Result.failure(e)
    }
  }

  suspend fun updateSectorNavigation(
    currentSector: SectorState,
    newQ: Int,
    newR: Int,
    newLayer: Int,
    newEnergy: Long,
    eventSummary: String,
  ): Result<Unit> {
    val uid = requireUserId()
    require(currentSector.id.isNotBlank()) { "Sector ID must not be blank" }
    requireNotNull(currentSector.createdAt) { "Sector createdAt must not be null for update" }
    val docRef =
      db.collection("users").document(uid).collection("sectors").document(currentSector.id)
    val updated =
      currentSector.copy(
        shipQ = newQ.toLong().coerceIn(-20L, 20L),
        shipR = newR.toLong().coerceIn(-20L, 20L),
        shipLayer = newLayer.toLong().coerceIn(0L, 4L),
        energy = newEnergy.coerceIn(0L, 10000L),
        turnNumber = (currentSector.turnNumber + 1L).coerceAtMost(1000000L),
        lastEventLog = eventSummary.take(500),
        updatedAt = null,
      )
    return try {
      docRef.set(updated).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun saveOrEvolveHexCell(cell: HexCellSeed): Result<String> {
    val uid = requireUserId()
    val docId =
      cell.id.ifBlank {
        buildCellDocId(
          sectorId = cell.sectorId,
          layerIndex = cell.layerIndex.toInt(),
          q = cell.q.toInt(),
          r = cell.r.toInt(),
        )
      }
    val docRef = db.collection("users").document(uid).collection("hex_cells").document(docId)
    return try {
      val existingSnap = docRef.get().await()
      if (existingSnap.exists()) {
        val existingObj =
          existingSnap.toObject(
            HexCellSeed::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          )
        val preservedCreatedAt =
          requireNotNull(existingObj?.createdAt ?: cell.createdAt) {
            "Existing HexCellSeed missing createdAt"
          }
        val payload =
          cell.copy(
            id = docId,
            userId = uid,
            createdAt = preservedCreatedAt,
            updatedAt = null,
          )
        docRef.set(payload).await()
      } else {
        val payload =
          cell.copy(
            id = docId,
            userId = uid,
            createdAt = null,
            updatedAt = null,
          )
        docRef.set(payload).await()
      }
      Result.success(docId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun saveBatchHexCells(cells: List<HexCellSeed>): Result<Int> {
    if (cells.isEmpty()) return Result.success(0)
    val uid = requireUserId()
    val collectionPath = "users/$uid/hex_cells"
    return try {
      for (cell in cells) {
        saveOrEvolveHexCell(cell).getOrThrow()
      }
      Result.success(cells.size)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, collectionPath)
      Result.failure(e)
    }
  }

  private fun buildStarterSolarBasin(
    uid: String,
    sectorId: String,
    baseSeed: Long,
  ): List<HexCellSeed> {
    val cells = mutableListOf<HexCellSeed>()
    // Central Planet Aethelgard across all 5 vertical layers at (0, 0)
    cells +=
      HexCellSeed(
        id = buildCellDocId(sectorId, 0, 0, 0),
        userId = uid,
        sectorId = sectorId,
        q = 0L,
        r = 0L,
        layerIndex = 0L,
        seed = baseSeed + 101L,
        archetype = "SINGULARITY_CORE",
        voxelSubRings = 5L,
        proceduralFormula = "ARCH=SINGULARITY_CORE;SPHERE(r=4,z=1,h=8);CRATER(r=2,depth=3);SPIRE(count=6,h=7)",
        gravityMass = 8.5,
        atmosphereDensity = 1.4,
        elevationOffset = -4.0,
        primaryColorHex = "#FF6D00",
        secondaryColorHex = "#3E1F47",
        accentColorHex = "#FFD54F",
        title = "Aethelgard Iron-Magma Dynamo",
        loreSummary = "Hyper-dense molten hexagonal iron crystal core generating the primary gravity well.",
        lastActionContext = "Initial deep-core gravimetric survey",
        evolutionCount = 1L,
      )

    cells +=
      HexCellSeed(
        id = buildCellDocId(sectorId, 1, 0, 0),
        userId = uid,
        sectorId = sectorId,
        q = 0L,
        r = 0L,
        layerIndex = 1L,
        seed = baseSeed + 102L,
        archetype = "TERRAN_BIOME",
        voxelSubRings = 5L,
        proceduralFormula = "ARCH=TERRAN_BIOME;TERRAIN(rough=0.65,sea=0.38,peaks=9);SPIRE(count=3,h=8)",
        gravityMass = 5.2,
        atmosphereDensity = 1.0,
        elevationOffset = 1.5,
        primaryColorHex = "#10B981",
        secondaryColorHex = "#0284C7",
        accentColorHex = "#FDE047",
        title = "Aethelgard Emerald Terraces",
        loreSummary = "Basalt hex-plateaus rising out of bioluminescent teal seas with highland peaks.",
        lastActionContext = "Initial planetary surface telemetry",
        evolutionCount = 1L,
      )

    cells +=
      HexCellSeed(
        id = buildCellDocId(sectorId, 2, 0, 0),
        userId = uid,
        sectorId = sectorId,
        q = 0L,
        r = 0L,
        layerIndex = 2L,
        seed = baseSeed + 103L,
        archetype = "ATMOS_VORTEX",
        voxelSubRings = 5L,
        proceduralFormula = "ARCH=ATMOS_VORTEX;VORTEX(arms=3,lift=6,twist=2);RING(rIn=3,rOut=4,z=5)",
        gravityMass = 2.8,
        atmosphereDensity = 1.35,
        elevationOffset = 0.0,
        primaryColorHex = "#38BDF8",
        secondaryColorHex = "#E0F2FE",
        accentColorHex = "#818CF8",
        title = "Jetstream Cyclone Canopy",
        loreSummary = "Super-rotating hexagonal cloud bands and ionized upper-atmosphere auroral currents.",
        lastActionContext = "Initial atmospheric envelope scan",
        evolutionCount = 1L,
      )

    cells +=
      HexCellSeed(
        id = buildCellDocId(sectorId, 3, 0, 0),
        userId = uid,
        sectorId = sectorId,
        q = 0L,
        r = 0L,
        layerIndex = 3L,
        seed = baseSeed + 104L,
        archetype = "ORBITAL_CITADEL",
        voxelSubRings = 5L,
        proceduralFormula = "ARCH=ORBITAL_CITADEL;CITADEL(arms=6,tiers=3,dome=1);RING(rIn=4,rOut=5,z=6)",
        gravityMass = 1.6,
        atmosphereDensity = 0.15,
        elevationOffset = 0.0,
        primaryColorHex = "#00E5FF",
        secondaryColorHex = "#1E293B",
        accentColorHex = "#FFB300",
        title = "Zenith Tether Ring",
        loreSummary = "Geosynchronous hexagonal shipyard hovering inside Aethelgard's upper gravity well.",
        lastActionContext = "Initial orbital capture telemetry",
        evolutionCount = 1L,
      )

    cells +=
      HexCellSeed(
        id = buildCellDocId(sectorId, 4, 0, 0),
        userId = uid,
        sectorId = sectorId,
        q = 0L,
        r = 0L,
        layerIndex = 4L,
        seed = baseSeed + 105L,
        archetype = "GRAVITY_BEACON",
        voxelSubRings = 5L,
        proceduralFormula = "ARCH=GRAVITY_BEACON;SPHERE(r=2,z=4,h=5);RING(rIn=3,rOut=5,z=5);SPIRE(count=1,h=10)",
        gravityMass = 3.4,
        atmosphereDensity = 0.0,
        elevationOffset = -2.5,
        primaryColorHex = "#6366F1",
        secondaryColorHex = "#0EA5E9",
        accentColorHex = "#22D3EE",
        title = "Aethelgard Gravity Funnel Apex",
        loreSummary = "Deep-space gravitational lens marking the steepest curvature above Aethelgard.",
        lastActionContext = "Initial deep-space gravimetric scan",
        evolutionCount = 1L,
      )

    // Secondary minor moon/asteroid well at (-2, 2) in Deep Space & Orbit
    cells +=
      HexCellSeed(
        id = buildCellDocId(sectorId, 4, -2, 2),
        userId = uid,
        sectorId = sectorId,
        q = -2L,
        r = 2L,
        layerIndex = 4L,
        seed = baseSeed + 209L,
        archetype = "CRYSTAL_ASTEROID",
        voxelSubRings = 4L,
        proceduralFormula = "ARCH=CRYSTAL_ASTEROID;SPHERE(r=3,z=2,h=5);SPIRE(count=5,h=9);RING(rIn=3,rOut=4,z=3)",
        gravityMass = 3.1,
        atmosphereDensity = 0.05,
        elevationOffset = -1.0,
        primaryColorHex = "#C084FC",
        secondaryColorHex = "#312E81",
        accentColorHex = "#F472B6",
        title = "Vespera Quartz Cluster",
        loreSummary = "Fractured resonance asteroid creating a secondary local gravity pocket.",
        lastActionContext = "Long-range passive lidar sweep",
        evolutionCount = 1L,
      )

    // Dwarf Ringed Gas Giant well at (2, -2)
    cells +=
      HexCellSeed(
        id = buildCellDocId(sectorId, 4, 2, -2),
        userId = uid,
        sectorId = sectorId,
        q = 2L,
        r = -2L,
        layerIndex = 4L,
        seed = baseSeed + 317L,
        archetype = "GAS_GIANT_WELL",
        voxelSubRings = 5L,
        proceduralFormula = "ARCH=GAS_GIANT_WELL;SPHERE(r=3,z=3,h=7);RING(rIn=4,rOut=5,z=5);VORTEX(arms=2,lift=4,twist=3)",
        gravityMass = 6.2,
        atmosphereDensity = 1.8,
        elevationOffset = -2.0,
        primaryColorHex = "#F59E0B",
        secondaryColorHex = "#7C2D12",
        accentColorHex = "#38BDF8",
        title = "Hyperion Banded Giant",
        loreSummary = "Massive amber gas giant warping surrounding deep-space hexes into a steep gravity basin.",
        lastActionContext = "Gravimetric horizon triangulation",
        evolutionCount = 1L,
      )

    return cells
  }
}

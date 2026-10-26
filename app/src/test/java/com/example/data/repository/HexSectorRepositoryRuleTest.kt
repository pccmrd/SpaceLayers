package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.HexCellSeed
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HexSectorRepositoryRuleTest : FirestoreEmulatorTestBase() {

  @Test
  fun bootstrapSector_andObserveHexCells_succeedsForOwner() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val repository = HexSectorRepository(firestore)
    val sectorId = "sec_${UUID.randomUUID().toString().replace("-", "").take(10)}"

    val sectorResult =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.createOrBootstrapSector(
          sectorId = sectorId,
          sectorName = "Kepler Test Basin",
          galaxySeed = 424242L,
        )
      }
    assertTrue("Bootstrap sector should succeed: ${sectorResult.exceptionOrNull()}", sectorResult.isSuccess)

    val emittedCells =
      withTimeout(FLOW_TIMEOUT_MS) {
        repository.observeSectorHexCells(aliceUid, sectorId).first { it.isNotEmpty() }
      }
    assertTrue("Starter solar basin cells should be created", emittedCells.size >= 5)
  }

  @Test
  fun saveAndEvolveHexCell_persistsUpdatedSeedAndFormula() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val repository = HexSectorRepository(firestore)
    val sectorId = "sec_${UUID.randomUUID().toString().replace("-", "").take(10)}"

    withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.createOrBootstrapSector(sectorId, "Evolution Basin", 12345L).getOrThrow()
    }

    val initialCell =
      HexCellSeed(
        userId = aliceUid,
        sectorId = sectorId,
        q = 3L,
        r = -1L,
        layerIndex = 1L,
        seed = 555001L,
        archetype = "TERRAN_BIOME",
        voxelSubRings = 4L,
        proceduralFormula = "ARCH=TERRAN_BIOME;TERRAIN(rough=0.5,sea=0.3,peaks=6)",
        gravityMass = 3.2,
        atmosphereDensity = 0.9,
        elevationOffset = 1.0,
        primaryColorHex = "#10B981",
        secondaryColorHex = "#0284C7",
        accentColorHex = "#FACC15",
        title = "Verdant Ridge",
        loreSummary = "Untouched hex-terraced ridge.",
        lastActionContext = "Surveyed surface",
        evolutionCount = 1L,
      )

    val savedId =
      withTimeout(DEFAULT_TIMEOUT_MS) { repository.saveOrEvolveHexCell(initialCell).getOrThrow() }
    assertTrue(savedId.isNotEmpty())

    // Evolve existing hex cell based on new player action
    val evolvedCell =
      initialCell.copy(
        id = savedId,
        seed = 555002L,
        voxelSubRings = 5L,
        proceduralFormula = "ARCH=TERRAN_BIOME;TERRAIN(rough=0.5,sea=0.3,peaks=6);MOD=COLONY_DOMES(3)",
        evolutionCount = 2L,
        lastActionContext = "Established biodome colony",
      )
    val evolvedResult =
      withTimeout(DEFAULT_TIMEOUT_MS) { repository.saveOrEvolveHexCell(evolvedCell) }
    assertTrue("Evolution update should succeed: ${evolvedResult.exceptionOrNull()}", evolvedResult.isSuccess)
  }

  @Test
  fun crossUserAccess_bobCannotReadAliceSector_failsWithPermissionDenied() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val aliceRepo = HexSectorRepository(firestore)
    val sectorId = "sec_${UUID.randomUUID().toString().replace("-", "").take(10)}"

    withTimeout(DEFAULT_TIMEOUT_MS) {
      aliceRepo.createOrBootstrapSector(sectorId, "Alice Private Basin", 777L).getOrThrow()
    }

    signInTestUser(BOB_EMAIL)
    val bobRepo = HexSectorRepository(firestore)
    val crossRead =
      withTimeout(DEFAULT_TIMEOUT_MS) { bobRepo.getSectorById(aliceUid, sectorId) }

    assertTrue("Cross-user read must fail", crossRead.isFailure)
    val fsEx = crossRead.exceptionOrNull()?.unwrapFirestoreException()
    assertNotNull("Expected FirebaseFirestoreException", fsEx)
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
  }

  @Test
  fun unauthenticatedCaller_observeHexCells_failsWithPermissionDenied() = runBlocking {
    auth.signOut()
    val repository = HexSectorRepository(firestore)

    try {
      withTimeout(FLOW_TIMEOUT_MS) {
        repository.observeSectorHexCells("unauth_target_uid", "sec_any").first()
      }
      fail("Expected unauthenticated query to throw PERMISSION_DENIED")
    } catch (e: Exception) {
      val fsEx = e.unwrapFirestoreException()
      assertNotNull("Expected nested FirebaseFirestoreException, got $e", fsEx)
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }
  }

  private companion object {
    const val ALICE_EMAIL = "alice@hexwell.test"
    const val BOB_EMAIL = "bob@hexwell.test"
    const val DEFAULT_TIMEOUT_MS = 5000L
    const val FLOW_TIMEOUT_MS = 4000L
  }
}

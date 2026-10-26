package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.HexCellSeed
import com.example.data.model.HexCoord
import com.example.data.model.SpaceLayer
import com.example.domain.gravity.GravityWellPhysics
import com.example.domain.voxel.HexVoxelProceduralEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("HexWell", appName)
  }

  @Test
  fun `procedural engine deterministically compiles seed and formula to hex prism voxels`() {
    val cell =
      HexCellSeed(
        id = "test_cell",
        seed = 808101L,
        archetype = "TERRAN_BIOME",
        voxelSubRings = 5L,
        proceduralFormula = "ARCH=TERRAN_BIOME;TERRAIN(rough=0.6,sea=0.35,peaks=8);MOD=COLONY_DOMES",
      )
    val firstRun = HexVoxelProceduralEngine.compileHexCell(cell)
    val secondRun = HexVoxelProceduralEngine.compileHexCell(cell.copy(id = "test_cell_clone"))
    assertTrue("Should generate rich micro-hexagonal voxel cluster", firstRun.size >= 40)
    assertEquals("Same seed and formula must produce identical voxel list", firstRun, secondRun)
  }

  @Test
  fun `gravity well physics sinks deep space hexes deeper near planetary mass`() {
    val coords = HexCoord.spiralGrid(3)
    val core =
      HexCellSeed(
        id = "core_0_0",
        q = 0L,
        r = 0L,
        layerIndex = 0L,
        gravityMass = 9.0,
        title = "Aethelgard Core",
      )
    val field = GravityWellPhysics.computeLayerGravityField(coords, SpaceLayer.DEEP_SPACE, listOf(core))
    val centerWell = field[HexCoord(0, 0)]!!
    val rimWell = field[HexCoord(3, 0)]!!
    assertTrue("Center of gravity well must curve deeper than rim", centerWell.wellSinkDepth > rimWell.wellSinkDepth)
  }
}

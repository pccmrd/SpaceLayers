package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.HexProceduralAiService
import com.example.data.model.HexCellSeed
import com.example.data.model.HexCoord
import com.example.data.model.SectorState
import com.example.data.model.SpaceLayer
import com.example.data.repository.HexSectorRepository
import com.example.domain.gravity.GravityWellPhysics
import com.example.domain.gravity.HexGravitySample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "HexMapViewModel"
private const val DEFAULT_SECTOR_ID = "kepler_94"
private const val GRID_RADIUS = 4

sealed interface AiSynthesisState {
  data object Idle : AiSynthesisState
  data class Generating(val statusText: String) : AiSynthesisState
  data class Error(val message: String) : AiSynthesisState
}

sealed interface SectorLoadState {
  data object Loading : SectorLoadState
  data class Ready(
    val sector: SectorState,
    val allCells: List<HexCellSeed>,
  ) : SectorLoadState
  data class Error(val message: String) : SectorLoadState
}

data class HexMapUiState(
  val loadState: SectorLoadState = SectorLoadState.Loading,
  val activeLayer: SpaceLayer = SpaceLayer.DEEP_SPACE,
  val isStrataStackMode: Boolean = false,
  val selectedCoord: HexCoord = HexCoord(0, 0),
  val gridCoords: List<HexCoord> = HexCoord.spiralGrid(GRID_RADIUS),
  val gravityField: Map<HexCoord, HexGravitySample> = emptyMap(),
  val aiState: AiSynthesisState = AiSynthesisState.Idle,
  val customPromptInput: String = "",
)

class HexMapViewModel(
  private val repository: HexSectorRepository,
  private val aiService: HexProceduralAiService,
  private val currentUserId: String,
) : ViewModel() {

  private val gridCoords = HexCoord.spiralGrid(GRID_RADIUS)
  private val _activeLayer = MutableStateFlow(SpaceLayer.DEEP_SPACE)
  private val _isStrataStackMode = MutableStateFlow(false)
  private val _selectedCoord = MutableStateFlow(HexCoord(0, 0))
  private val _aiState = MutableStateFlow<AiSynthesisState>(AiSynthesisState.Idle)
  private val _customPromptInput = MutableStateFlow("")

  private val sectorDataFlow: StateFlow<SectorLoadState> =
    combine(
        repository.observeSectorState(currentUserId, DEFAULT_SECTOR_ID),
        repository.observeSectorHexCells(currentUserId, DEFAULT_SECTOR_ID),
      ) { sector, cells ->
        if (sector == null) {
          SectorLoadState.Loading
        } else {
          SectorLoadState.Ready(sector = sector, allCells = cells)
        }
      }
      .map<SectorLoadState, SectorLoadState> { it }
      .catch { error ->
        Log.e(TAG, "Error observing sector streams", error)
        emit(SectorLoadState.Error(error.localizedMessage ?: "Failed to load sector telemetry."))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = SectorLoadState.Loading,
      )

  val uiState: StateFlow<HexMapUiState> =
    combine(
        sectorDataFlow,
        _activeLayer,
        _isStrataStackMode,
        _selectedCoord,
        combine(_aiState, _customPromptInput) { ai, prompt -> ai to prompt },
      ) { loadState, layer, stackMode, selected, (aiState, prompt) ->
        val cells = (loadState as? SectorLoadState.Ready)?.allCells.orEmpty()
        val gravityMap = GravityWellPhysics.computeLayerGravityField(gridCoords, layer, cells)
        HexMapUiState(
          loadState = loadState,
          activeLayer = layer,
          isStrataStackMode = stackMode,
          selectedCoord = selected,
          gridCoords = gridCoords,
          gravityField = gravityMap,
          aiState = aiState,
          customPromptInput = prompt,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = HexMapUiState(gridCoords = gridCoords),
      )

  init {
    bootstrapSector()
  }

  private fun bootstrapSector() {
    viewModelScope.launch {
      val seed = (currentUserId.hashCode().toLong() and 0x7FFFFFL) + 402000L
      val res =
        repository.createOrBootstrapSector(
          sectorId = DEFAULT_SECTOR_ID,
          sectorName = "Kepler-94 Gravity Basin",
          galaxySeed = seed,
        )
      res.onSuccess { sector ->
        _activeLayer.value = SpaceLayer.fromIndex(sector.shipLayer.toInt())
        _selectedCoord.value = HexCoord(0, 0)
      }
      res.onFailure { err ->
        Log.e(TAG, "Bootstrap sector failed", err)
        _aiState.value =
          AiSynthesisState.Error(err.localizedMessage ?: "Failed to initialize cloud sector.")
      }
    }
  }

  fun selectHex(coord: HexCoord) {
    _selectedCoord.value = coord
  }

  fun selectLayer(layer: SpaceLayer) {
    _activeLayer.value = layer
  }

  fun toggleStrataStackMode() {
    _isStrataStackMode.value = !_isStrataStackMode.value
  }

  fun updateCustomPrompt(text: String) {
    _customPromptInput.value = text
  }

  fun dismissAiError() {
    if (_aiState.value is AiSynthesisState.Error) {
      _aiState.value = AiSynthesisState.Idle
    }
  }

  fun navigateShipTo(targetCoord: HexCoord, targetLayer: SpaceLayer) {
    val currentReady = sectorDataFlow.value as? SectorLoadState.Ready ?: return
    val sector = currentReady.sector
    val shipCoord = HexCoord(sector.shipQ.toInt(), sector.shipR.toInt())
    val shipLayer = SpaceLayer.fromIndex(sector.shipLayer.toInt())

    val fromField = GravityWellPhysics.computeLayerGravityField(listOf(shipCoord), shipLayer, currentReady.allCells)
    val toField = GravityWellPhysics.computeLayerGravityField(listOf(targetCoord), targetLayer, currentReady.allCells)
    val cost =
      GravityWellPhysics.computeNavigationCost(
        fromCoord = shipCoord,
        fromLayer = shipLayer,
        toCoord = targetCoord,
        toLayer = targetLayer,
        fromSample = fromField[shipCoord],
        toSample = toField[targetCoord],
      )

    if (sector.energy < cost) {
      _aiState.value =
        AiSynthesisState.Error("Insufficient thruster reserves ($cost MJ required). Trigger Solar Recharge.")
      return
    }

    viewModelScope.launch {
      val summary =
        "Navigated flagship to (${targetCoord.q}, ${targetCoord.r}) on ${targetLayer.shortTag} (-$cost MJ)."
      val res =
        repository.updateSectorNavigation(
          currentSector = sector,
          newQ = targetCoord.q,
          newR = targetCoord.r,
          newLayer = targetLayer.index,
          newEnergy = sector.energy - cost,
          eventSummary = summary,
        )
      res.onSuccess {
        _activeLayer.value = targetLayer
        _selectedCoord.value = targetCoord
      }
      res.onFailure { err ->
        _aiState.value = AiSynthesisState.Error(err.localizedMessage ?: "Navigation update failed.")
      }
    }
  }

  fun rechargeSolarEnergy() {
    val currentReady = sectorDataFlow.value as? SectorLoadState.Ready ?: return
    val sector = currentReady.sector
    viewModelScope.launch {
      val boosted = (sector.energy + 300L).coerceAtMost(2000L)
      repository.updateSectorNavigation(
        currentSector = sector,
        newQ = sector.shipQ.toInt(),
        newR = sector.shipR.toInt(),
        newLayer = sector.shipLayer.toInt(),
        newEnergy = boosted,
        eventSummary = "Harvested +300 MJ via gravimetric solar sails.",
      )
    }
  }

  fun executeProceduralHexAction(actionDescription: String) {
    val currentReady = sectorDataFlow.value as? SectorLoadState.Ready ?: return
    val directive = actionDescription.trim()
    if (directive.isEmpty()) return

    val targetCoord = _selectedCoord.value
    val targetLayer = _activeLayer.value
    val existingCell =
      currentReady.allCells.firstOrNull {
        it.coord == targetCoord && it.layerIndex.toInt() == targetLayer.index
      }
    val nearby =
      currentReady.allCells.filter {
        it.coord.distanceTo(targetCoord) <= 2
      }

    viewModelScope.launch {
      _aiState.value = AiSynthesisState.Generating("Synthesizing hex (${targetCoord.q}, ${targetCoord.r})…")
      val aiResult =
        aiService.synthesizeOrEvolveHex(
          userId = currentUserId,
          sector = currentReady.sector,
          targetCoord = targetCoord,
          targetLayer = targetLayer,
          existingCell = existingCell,
          nearbyCells = nearby,
          actionOrPrompt = directive,
        )

      aiResult
        .onSuccess { synthesizedCell ->
          val saveRes = repository.saveOrEvolveHexCell(synthesizedCell)
          saveRes
            .onSuccess {
              repository.updateSectorNavigation(
                currentSector = currentReady.sector,
                newQ = currentReady.sector.shipQ.toInt(),
                newR = currentReady.sector.shipR.toInt(),
                newLayer = currentReady.sector.shipLayer.toInt(),
                newEnergy = currentReady.sector.energy,
                eventSummary = "Synthesized ${synthesizedCell.title} [Seed #${synthesizedCell.seed}] at (${targetCoord.q}, ${targetCoord.r}).",
              )
              if (_customPromptInput.value.trim() == directive) {
                _customPromptInput.value = ""
              }
              _aiState.value = AiSynthesisState.Idle
            }
            .onFailure { err ->
              _aiState.value =
                AiSynthesisState.Error(err.localizedMessage ?: "Failed to persist hex seed.")
            }
        }
        .onFailure { err ->
          _aiState.value =
            AiSynthesisState.Error(err.localizedMessage ?: "Gemini procedural synthesis failed.")
        }
    }
  }

  fun forgeFiveLayerWorld(customWorldDirective: String? = null) {
    val currentReady = sectorDataFlow.value as? SectorLoadState.Ready ?: return
    val targetCoord = _selectedCoord.value
    val prompt =
      customWorldDirective?.trim()?.takeIf { it.isNotEmpty() }
        ?: _customPromptInput.value.trim().takeIf { it.isNotEmpty() }
        ?: "Discover a rich multi-layer alien planet with molten core, terraced oceans, storm vortex atmosphere, orbital tether station, and deep-space gravity funnel"

    val existingColumn = currentReady.allCells.filter { it.coord == targetCoord }

    viewModelScope.launch {
      _aiState.value =
        AiSynthesisState.Generating("Forging 5-layer gravity world at (${targetCoord.q}, ${targetCoord.r})…")
      val worldRes =
        aiService.synthesizeFiveLayerWorld(
          userId = currentUserId,
          sector = currentReady.sector,
          targetCoord = targetCoord,
          existingColumn = existingColumn,
          worldPrompt = prompt,
        )

      worldRes
        .onSuccess { fiveCells ->
          val saveRes = repository.saveBatchHexCells(fiveCells)
          saveRes
            .onSuccess {
              repository.updateSectorNavigation(
                currentSector = currentReady.sector,
                newQ = currentReady.sector.shipQ.toInt(),
                newR = currentReady.sector.shipR.toInt(),
                newLayer = currentReady.sector.shipLayer.toInt(),
                newEnergy = currentReady.sector.energy,
                eventSummary = "Forged 5-layer celestial column at (${targetCoord.q}, ${targetCoord.r}).",
              )
              _customPromptInput.value = ""
              _aiState.value = AiSynthesisState.Idle
            }
            .onFailure { err ->
              _aiState.value =
                AiSynthesisState.Error(err.localizedMessage ?: "Failed to save 5-layer world seeds.")
            }
        }
        .onFailure { err ->
          _aiState.value =
            AiSynthesisState.Error(err.localizedMessage ?: "Multi-layer synthesis failed.")
        }
    }
  }
}

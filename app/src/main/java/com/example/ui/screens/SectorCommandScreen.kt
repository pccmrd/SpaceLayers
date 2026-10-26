package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.data.ai.HexProceduralAiService
import com.example.data.model.HexCellSeed
import com.example.data.model.HexCoord
import com.example.data.model.SectorState
import com.example.data.model.SpaceLayer
import com.example.data.repository.HexSectorRepository
import com.example.domain.gravity.GravityWellPhysics
import com.example.domain.voxel.HexVoxelProceduralEngine
import com.example.ui.auth.signOutCommander
import com.example.ui.canvas.HexInspectorLoupe
import com.example.ui.canvas.IsometricHexVoxelCanvas
import com.example.ui.viewmodel.AiSynthesisState
import com.example.ui.viewmodel.HexMapUiState
import com.example.ui.viewmodel.HexMapViewModel
import com.example.ui.viewmodel.SectorLoadState
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

@Composable
fun SectorCommandScreen(
  currentUserId: String,
  onSignedOut: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val viewModel: HexMapViewModel =
    viewModel(
      key = currentUserId,
      factory =
        viewModelFactory {
          initializer {
            val app =
              checkNotNull(this[APPLICATION_KEY]) {
                "APPLICATION_KEY missing from CreationExtras"
              }
            val databaseId = app.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(databaseId)
            HexMapViewModel(
              repository = HexSectorRepository(db),
              aiService = HexProceduralAiService(),
              currentUserId = currentUserId,
            )
          }
        },
    )

  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }

  Surface(
    modifier = modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    when (val load = uiState.loadState) {
      is SectorLoadState.Loading -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
              text = "Calibrating multi-layer gravity lattice…",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
      is SectorLoadState.Error -> {
        Box(
          modifier = Modifier.fillMaxSize().padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp),
          ) {
            Column(
              modifier = Modifier.padding(20.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Text(
                text = load.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
              )
              OutlinedButton(
                onClick = {
                  signOutCommander(credentialManager, onSignedOut, scope)
                },
                modifier = Modifier.testTag("error_sign_out_button"),
              ) {
                Text(stringResource(R.string.sign_out))
              }
            }
          }
        }
      }
      is SectorLoadState.Ready -> {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
          val isExpanded = maxWidth >= 720.dp
          if (isExpanded) {
            Row(
              modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
              Box(modifier = Modifier.weight(1.25f).fillMaxHeight()) {
                MapViewportSection(
                  uiState = uiState,
                  sector = load.sector,
                  allCells = load.allCells,
                  onHexSelected = viewModel::selectHex,
                  onLayerSelected = viewModel::selectLayer,
                  onToggleStackMode = viewModel::toggleStrataStackMode,
                  onSignOut = { signOutCommander(credentialManager, onSignedOut, scope) },
                )
              }
              Surface(
                modifier = Modifier.width(390.dp).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
              ) {
                CommandDeckPanel(
                  uiState = uiState,
                  sector = load.sector,
                  allCells = load.allCells,
                  onMoveShip = viewModel::navigateShipTo,
                  onRecharge = viewModel::rechargeSolarEnergy,
                  onExecuteAction = viewModel::executeProceduralHexAction,
                  onForgeWorld = { viewModel.forgeFiveLayerWorld() },
                  onPromptChanged = viewModel::updateCustomPrompt,
                  onDismissError = viewModel::dismissAiError,
                  modifier = Modifier.padding(16.dp),
                )
              }
            }
          } else {
            Column(modifier = Modifier.fillMaxSize()) {
              Box(
                modifier =
                  Modifier.fillMaxWidth()
                    .weight(0.53f)
              ) {
                MapViewportSection(
                  uiState = uiState,
                  sector = load.sector,
                  allCells = load.allCells,
                  onHexSelected = viewModel::selectHex,
                  onLayerSelected = viewModel::selectLayer,
                  onToggleStackMode = viewModel::toggleStrataStackMode,
                  onSignOut = { signOutCommander(credentialManager, onSignedOut, scope) },
                )
              }
              Surface(
                modifier = Modifier.fillMaxWidth().weight(0.47f),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 4.dp,
              ) {
                CommandDeckPanel(
                  uiState = uiState,
                  sector = load.sector,
                  allCells = load.allCells,
                  onMoveShip = viewModel::navigateShipTo,
                  onRecharge = viewModel::rechargeSolarEnergy,
                  onExecuteAction = viewModel::executeProceduralHexAction,
                  onForgeWorld = { viewModel.forgeFiveLayerWorld() },
                  onPromptChanged = viewModel::updateCustomPrompt,
                  onDismissError = viewModel::dismissAiError,
                  modifier =
                    Modifier.navigationBarsPadding()
                      .padding(horizontal = 16.dp, vertical = 12.dp),
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MapViewportSection(
  uiState: HexMapUiState,
  sector: SectorState,
  allCells: List<HexCellSeed>,
  onHexSelected: (HexCoord) -> Unit,
  onLayerSelected: (SpaceLayer) -> Unit,
  onToggleStackMode: () -> Unit,
  onSignOut: () -> Unit,
) {
  val shipCoord = HexCoord(sector.shipQ.toInt(), sector.shipR.toInt())
  val shipLayer = SpaceLayer.fromIndex(sector.shipLayer.toInt())

  Box(modifier = Modifier.fillMaxSize()) {
    IsometricHexVoxelCanvas(
      gridCoords = uiState.gridCoords,
      activeLayer = uiState.activeLayer,
      isStrataStackMode = uiState.isStrataStackMode,
      selectedCoord = uiState.selectedCoord,
      shipCoord = shipCoord,
      shipLayer = shipLayer,
      allCells = allCells,
      gravityField = uiState.gravityField,
      onHexSelected = onHexSelected,
      modifier = Modifier.fillMaxSize().testTag("isometric_hex_map_canvas"),
    )

    // Top Floating Sector Telemetry Bar & Layer Switcher
    Column(
      modifier =
        Modifier.align(Alignment.TopCenter)
          .statusBarsPadding()
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = sector.name,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text =
                "${stringResource(R.string.hud_turn_fmt, sector.turnNumber)} • " +
                  "${stringResource(R.string.hud_energy_fmt, sector.energy)} • " +
                  stringResource(R.string.hud_seeds_saved_fmt, allCells.size),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }

          FilterChip(
            selected = uiState.isStrataStackMode,
            onClick = onToggleStackMode,
            label = {
              Text(
                text =
                  if (uiState.isStrataStackMode) stringResource(R.string.mode_strata_stack)
                  else stringResource(R.string.mode_single_layer),
                style = MaterialTheme.typography.labelSmall,
              )
            },
            leadingIcon = {
              Icon(
                imageVector = Icons.Filled.Layers,
                contentDescription = stringResource(R.string.mode_strata_stack),
                modifier = Modifier.size(16.dp),
              )
            },
            modifier = Modifier.testTag("toggle_strata_stack_chip"),
          )

          IconButton(
            onClick = onSignOut,
            modifier = Modifier.size(48.dp).testTag("sign_out_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Logout,
              contentDescription = stringResource(R.string.sign_out),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      // 5-Layer Vertical Strata Selector Pills (Deep Space L4 -> Core L0)
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        for (layer in SpaceLayer.entries.sortedByDescending { it.index }) {
          val isSelected = (layer == uiState.activeLayer)
          val shipBadge = if (layer == shipLayer) " ★" else ""
          FilterChip(
            selected = isSelected,
            onClick = { onLayerSelected(layer) },
            colors =
              FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.primary,
              ),
            label = {
              Text(
                text = "${layer.shortTag}$shipBadge",
                style = MaterialTheme.typography.labelMedium,
              )
            },
            modifier = Modifier.testTag("layer_chip_${layer.index}"),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandDeckPanel(
  uiState: HexMapUiState,
  sector: SectorState,
  allCells: List<HexCellSeed>,
  onMoveShip: (HexCoord, SpaceLayer) -> Unit,
  onRecharge: () -> Unit,
  onExecuteAction: (String) -> Unit,
  onForgeWorld: () -> Unit,
  onPromptChanged: (String) -> Unit,
  onDismissError: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val selectedCoord = uiState.selectedCoord
  val activeLayer = uiState.activeLayer
  val selectedCell =
    allCells.firstOrNull {
      it.coord == selectedCoord && it.layerIndex.toInt() == activeLayer.index
    }
  val gravitySample = uiState.gravityField[selectedCoord]
  val shipCoord = HexCoord(sector.shipQ.toInt(), sector.shipR.toInt())
  val shipLayer = SpaceLayer.fromIndex(sector.shipLayer.toInt())
  val isShipAtSelected = (shipCoord == selectedCoord && shipLayer == activeLayer)

  val navCost =
    remember(shipCoord, shipLayer, selectedCoord, activeLayer, allCells) {
      val fromMap = GravityWellPhysics.computeLayerGravityField(listOf(shipCoord), shipLayer, allCells)
      val toMap = GravityWellPhysics.computeLayerGravityField(listOf(selectedCoord), activeLayer, allCells)
      GravityWellPhysics.computeNavigationCost(
        fromCoord = shipCoord,
        fromLayer = shipLayer,
        toCoord = selectedCoord,
        toLayer = activeLayer,
        fromSample = fromMap[shipCoord],
        toSample = toMap[selectedCoord],
      )
    }

  val voxelCount =
    remember(selectedCell) {
      if (selectedCell != null) HexVoxelProceduralEngine.compileHexCell(selectedCell).size else 0
    }

  val isGenerating = uiState.aiState is AiSynthesisState.Generating

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // AI Status / Error Banner
    AnimatedVisibility(visible = uiState.aiState !is AiSynthesisState.Idle) {
      when (val st = uiState.aiState) {
        is AiSynthesisState.Generating -> {
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().testTag("ai_generating_banner"),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
              )
              Text(
                text = st.statusText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          }
        }
        is AiSynthesisState.Error -> {
          Surface(
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().testTag("ai_error_banner"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = st.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
              )
              TextButton(
                onClick = onDismissError,
                modifier = Modifier.testTag("dismiss_error_button"),
              ) {
                Text(stringResource(R.string.dismiss))
              }
            }
          }
        }
        AiSynthesisState.Idle -> Unit
      }
    }

    // Selected Hex Header + Rotatable 3D Hex-Voxel Loupe Row
    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            HexInspectorLoupe(
              cell = selectedCell,
              isShipPresent = isShipAtSelected,
              modifier =
                Modifier.size(116.dp)
                  .clip(RoundedCornerShape(16.dp))
                  .testTag("hex_voxel_loupe"),
            )
            Text(
              text = "DRAG 3D LOUPE",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              text =
                "HEX (${selectedCoord.q}, ${selectedCoord.r}) • ${stringResource(activeLayer.titleRes)}",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = selectedCell?.title ?: stringResource(R.string.empty_hex_title),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            val gFormatted = String.format(Locale.US, "%.2fG", gravitySample?.localGravityG ?: 0.03f)
            val wellFormatted =
              String.format(Locale.US, "%.1f", gravitySample?.wellSinkDepth ?: 0f)
            val atmoFormatted =
              String.format(Locale.US, "%.2f", gravitySample?.atmosphereDensity ?: 0f)
            Text(
              text = "PULL $gFormatted • WELL -$wellFormatted • ATMO $atmoFormatted",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.secondary,
            )
            if (selectedCell != null) {
              Text(
                text =
                  "$voxelCount HEX-VOXELS • RINGS=${selectedCell.voxelSubRings} • EVO v${selectedCell.evolutionCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
              )
            }
            Text(
              text = selectedCell?.loreSummary ?: stringResource(R.string.empty_hex_body),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 4,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        // Stored Compact Procedural Seed & Formula Display ("nothing to store, but code/seed")
        if (selectedCell != null) {
          Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              Text(
                text =
                  "${stringResource(R.string.formula_label)} • SEED #${selectedCell.seed}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
              )
              Text(
                text = selectedCell.proceduralFormula,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }

    // Navigation & Vertical Gravity Well Traverse Controls
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (!isShipAtSelected) {
        Button(
          onClick = { onMoveShip(selectedCoord, activeLayer) },
          enabled = !isGenerating,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.weight(1f).height(48.dp).testTag("fly_to_hex_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.RocketLaunch,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = stringResource(R.string.btn_move_here, navCost),
            style = MaterialTheme.typography.labelLarge,
          )
        }
      } else {
        if (activeLayer.index > 0) {
          FilledTonalButton(
            onClick = {
              onMoveShip(selectedCoord, SpaceLayer.fromIndex(activeLayer.index - 1))
            },
            enabled = !isGenerating,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f).height(48.dp).testTag("descend_layer_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.ArrowDownward,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.btn_descend_layer), style = MaterialTheme.typography.labelMedium)
          }
        }
        if (activeLayer.index < 4) {
          FilledTonalButton(
            onClick = {
              onMoveShip(selectedCoord, SpaceLayer.fromIndex(activeLayer.index + 1))
            },
            enabled = !isGenerating,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f).height(48.dp).testTag("ascend_layer_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.ArrowUpward,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.btn_ascend_layer), style = MaterialTheme.typography.labelMedium)
          }
        }
      }

      if (sector.energy < 400L) {
        OutlinedButton(
          onClick = onRecharge,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.height(48.dp).testTag("solar_recharge_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Bolt,
            contentDescription = stringResource(R.string.btn_recharge),
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text("+300 MJ", style = MaterialTheme.typography.labelMedium)
        }
      }
    }

    // Gemini Context-Driven Gameplay Actions
    Text(
      text = stringResource(R.string.section_ai_actions),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      AssistChip(
        onClick = {
          val action =
            if (selectedCell == null)
              "Deep-sensor scan at (${selectedCoord.q}, ${selectedCoord.r}) on ${activeLayer.name}: reveal indigenous anomaly or celestial structure"
            else
              "Re-scan and evolve ${selectedCell.title} based on flagship proximity at turn ${sector.turnNumber}"
          onExecuteAction(action)
        },
        enabled = !isGenerating,
        leadingIcon = {
          Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        label = { Text(stringResource(R.string.action_scan_hex)) },
        modifier = Modifier.testTag("action_chip_scan"),
      )

      AssistChip(
        onClick = onForgeWorld,
        enabled = !isGenerating,
        colors =
          AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
          ),
        leadingIcon = {
          Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        label = { Text(stringResource(R.string.action_spawn_world)) },
        modifier = Modifier.testTag("action_chip_forge_world"),
      )

      AssistChip(
        onClick = {
          onExecuteAction(
            "Establish hexagonal biodome outpost and orbital beacon on this hex (include MOD=COLONY_DOMES+SHIELD_HALO)"
          )
        },
        enabled = !isGenerating,
        label = { Text(stringResource(R.string.action_colonize)) },
        modifier = Modifier.testTag("action_chip_colonize"),
      )

      AssistChip(
        onClick = {
          onExecuteAction(
            "Deploy gravimetric core harvester & plasma mining rig here (include MOD=MINING_RIG and increase spire detail)"
          )
        },
        enabled = !isGenerating,
        label = { Text(stringResource(R.string.action_mine_core)) },
        modifier = Modifier.testTag("action_chip_mine"),
      )

      AssistChip(
        onClick = {
          onExecuteAction(
            "Terraform strata: enrich atmospheric/oceanic equilibrium and luminous flora (include MOD=TERRAFORM)"
          )
        },
        enabled = !isGenerating,
        label = { Text(stringResource(R.string.action_terraform)) },
        modifier = Modifier.testTag("action_chip_terraform"),
      )
    }

    // Direct Player Free-Form Procedural Input
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = uiState.customPromptInput,
        onValueChange = onPromptChanged,
        enabled = !isGenerating,
        placeholder = {
          Text(
            text = stringResource(R.string.prompt_placeholder),
            style = MaterialTheme.typography.bodyMedium,
          )
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.weight(1f).testTag("custom_hex_prompt_input"),
      )

      Button(
        onClick = { onExecuteAction(uiState.customPromptInput) },
        enabled = !isGenerating && uiState.customPromptInput.isNotBlank(),
        shape = RoundedCornerShape(14.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
          ),
        modifier = Modifier.height(54.dp).testTag("synthesize_prompt_button"),
      ) {
        Icon(
          imageVector = Icons.Filled.AutoAwesome,
          contentDescription = stringResource(R.string.btn_generate_prompt),
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = stringResource(R.string.btn_generate_prompt),
          style = MaterialTheme.typography.labelLarge,
        )
      }
    }

    if (sector.lastEventLog.isNotBlank()) {
      Text(
        text = "LOG > ${sector.lastEventLog}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.firebase.configureAppCheck
import com.example.ui.auth.authStateFlow
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.SectorCommandScreen
import com.example.ui.theme.HexWellTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // STEP 1: Configure App Check with launch intent BEFORE UI, ViewModels, or Firebase calls
    configureAppCheck(this, intent)

    enableEdgeToEdge()

    // STEP 2: Initialize Compose UI with Navigation-Level Auth Gating
    setContent {
      HexWellTheme {
        AppNavigation()
      }
    }
  }
}

@Composable
fun AppNavigation(auth: FirebaseAuth = Firebase.auth) {
  val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
  val user = currentUser
  if (user == null) {
    AuthScreen(onAuthSuccess = {})
  } else {
    SectorCommandScreen(
      currentUserId = user.uid,
      onSignedOut = {},
    )
  }
}

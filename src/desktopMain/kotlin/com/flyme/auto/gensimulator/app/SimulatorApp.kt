package com.flyme.auto.gensimulator.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.flyme.auto.gensimulator.presentation.SimulatorViewModel
import com.flyme.auto.gensimulator.ui.SimulatorScreen

@Composable
fun SimulatorApp(viewModel: SimulatorViewModel) {
    MaterialTheme {
        SimulatorScreen(viewModel)
    }
}

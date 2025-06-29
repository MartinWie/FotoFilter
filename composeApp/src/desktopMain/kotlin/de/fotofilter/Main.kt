package de.fotofilter

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ui.PhotoGridScreen

fun main() = application {
    val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)

    Window(
        onCloseRequest = ::exitApplication,
        title = "Foto-Filter",
        state = windowState
    ) {
        AppTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                PhotoGridScreen()
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val isDarkMode = isSystemInDarkTheme()
    val colorScheme = if (isDarkMode) {
        darkColorScheme()
    } else {
        lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

package com.wajahatnaeem.oneplusota.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput

class MainActivity : ComponentActivity() {
    private val vm: OtaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = when (vm.themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            val view = LocalView.current
            // Keep status / navigation bar icons readable when the app theme differs from the system theme.
            SideEffect {
                val c = WindowCompat.getInsetsController(window, view)
                c.isAppearanceLightStatusBars = !dark
                c.isAppearanceLightNavigationBars = !dark
            }
            val ctx = LocalContext.current
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { Root(vm) }
            }
        }
    }
}

@Composable
private fun Root(vm: OtaViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // The bar floats over the content (no hard clip line); lists keep bottom padding so the last item scrolls above it.
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.statusBarsPadding().fillMaxSize().pointerInput(vm.device.isOnePlus) {
            if (!vm.device.isOnePlus) return@pointerInput
            val threshold = 80.dp.toPx()
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onHorizontalDrag = { _, dx -> total += dx },
                onDragEnd = { if (total < -threshold) tab = (tab + 1).coerceAtMost(3) else if (total > threshold) tab = (tab - 1).coerceAtLeast(0) },
            )
        }) {
            when {
                tab == 0 || !vm.device.isOnePlus -> UpdateTab(vm)
                tab == 1 -> OtherTab(vm)
                tab == 2 -> ExtractTab()
                else -> SettingsTab(vm)
            }
        }
        if (vm.device.isOnePlus) Box(Modifier.align(Alignment.BottomCenter)) { FloatingBar(tab) { tab = it } }
    }
}

@Composable
private fun FloatingBar(selected: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(30.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, shadowElevation = 6.dp) {
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BarItem(0, selected, AppIcons.SystemUpdate, "My phone", onSelect)
                BarItem(1, selected, AppIcons.Devices, "Devices", onSelect)
                BarItem(2, selected, AppIcons.Extract, "Extract", onSelect)
                BarItem(3, selected, AppIcons.Settings, "Settings", onSelect)
            }
        }
    }
}

@Composable
private fun BarItem(index: Int, selected: Int, icon: ImageVector, label: String, onSelect: (Int) -> Unit) {
    val on = index == selected
    val bg = if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val fg = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).background(bg).clickable { onSelect(index) }.padding(horizontal = if (on) 20.dp else 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(20.dp))
        // Four tabs would not fit with all labels, so only the selected one shows its name.
        if (on) Text(label, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

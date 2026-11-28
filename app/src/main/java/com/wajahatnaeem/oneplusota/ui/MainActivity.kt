package com.wajahatnaeem.oneplusota.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.abs
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
            val trigger = 48.dp.toPx()
            val flingV = 500.dp.toPx()
            val slop = viewConfiguration.touchSlop
            // Watched in the Initial pass: the lists underneath grab slightly-diagonal finger drags first, so this never sees them in the Main pass.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var dx = 0f
                var dy = 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val c = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) {
                        val v = tracker.calculateVelocity().x
                        if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f && abs(v) > flingV) tab = (tab + if (dx < 0) 1 else -1).coerceIn(0, 3)
                        break
                    }
                    tracker.addPosition(c.uptimeMillis, c.position)
                    dx += c.position.x - c.previousPosition.x
                    dy += c.position.y - c.previousPosition.y
                    if (abs(dx) > trigger && abs(dx) > abs(dy) * 1.5f) {
                        tab = (tab + if (dx < 0) 1 else -1).coerceIn(0, 3)
                        break
                    }
                }
            }
        }) {
            // Slides in the direction of travel, so swiping and tapping feel the same.
            AnimatedContent(
                targetState = if (vm.device.isOnePlus) tab else 0,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(180, easing = FastOutSlowInEasing)) { it / 5 * dir } + fadeIn(tween(140))) togetherWith
                        (slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { -it / 5 * dir } + fadeOut(tween(90)))
                },
                label = "tabs",
            ) { page ->
                when (page) {
                    0 -> UpdateTab(vm)
                    1 -> OtherTab(vm)
                    2 -> ExtractTab()
                    else -> SettingsTab(vm)
                }
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
    val bg by animateColorAsState(
        if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        tween(280, easing = FastOutSlowInEasing), label = "bg",
    )
    val fg by animateColorAsState(
        if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(280, easing = FastOutSlowInEasing), label = "fg",
    )
    // No ripple: it was drawing a lighter patch over the selected pill.
    val noRipple = remember { MutableInteractionSource() }
    Row(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .clickable(interactionSource = noRipple, indication = null) { onSelect(index) }
            .padding(horizontal = if (on) 20.dp else 16.dp, vertical = 11.dp)
            .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(20.dp))
        // Four tabs would not fit with all labels, so only the selected one shows its name.
        AnimatedVisibility(
            visible = on,
            enter = expandHorizontally(tween(280, easing = FastOutSlowInEasing)) + fadeIn(tween(240, delayMillis = 60)),
            exit = shrinkHorizontally(tween(220, easing = FastOutSlowInEasing)) + fadeOut(tween(120)),
        ) {
            Text(label, color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

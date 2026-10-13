package com.os4.musiccover.ui.screen.settings

import com.os4.musiccover.ui.util.nextCard
import com.os4.musiccover.ui.util.firstCard
import com.os4.musiccover.ui.util.FoldUnder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.os4.musiccover.R
import com.os4.musiccover.ui.util.PageScaffold
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * The theme settings, on their own screen instead of in the settings list.
 *
 * KernelSU keeps its settings page to one row that opens a second-level screen holding the whole
 * appearance block, and this follows that: the settings page says "theme" and nothing more, and
 * everything that repaints the app lives here. Two reasons it is worth the extra screen. The
 * list was long enough that the settings page read as a wall, and the switches that follow are
 * not all visible at once - liquid glass only exists when the floating navbar is on, so on the
 * settings page a row appeared or vanished underneath the finger that opened the dropdown above
 * it. Here the dropdown is the last thing that moves.
 *
 * Nothing is read or saved here: the values arrive from the screen that hosts this
 * ([ThemeActivity]), which owns them because it owns the theme this page is drawn in.
 */
@Composable
fun ThemePageView(
    currentMode: ColorSchemeMode,
    onModeChange: (ColorSchemeMode) -> Unit,
    isFloatingNavbar: Boolean,
    onFloatingNavbarChange: (Boolean) -> Unit,
    isLiquidGlass: Boolean,
    onLiquidGlassChange: (Boolean) -> Unit,
    isBlurEnabled: Boolean,
    onBlurEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    PageScaffold(
        title = stringResource(R.string.settings_theme),
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
    ) {
        item(key = "theme") {
            Card(
                modifier = Modifier.firstCard()
                    .padding(bottom = 12.dp)
            ) {
                Column {
                    val modes = listOf(
                        stringResource(R.string.theme_system),
                        stringResource(R.string.theme_light),
                        stringResource(R.string.theme_dark),
                        stringResource(R.string.theme_monet_system),
                        stringResource(R.string.theme_monet_light),
                        stringResource(R.string.theme_monet_dark)
                    )
                    val modesEnum = listOf(
                        ColorSchemeMode.System,
                        ColorSchemeMode.Light,
                        ColorSchemeMode.Dark,
                        ColorSchemeMode.MonetSystem,
                        ColorSchemeMode.MonetLight,
                        ColorSchemeMode.MonetDark
                    )
                    var expanded by remember { mutableStateOf(false) }
                    val currentIndex =
                        modesEnum.indexOf(currentMode).takeIf { it >= 0 } ?: 0

                    WindowDropdownPreference(
                        title = stringResource(R.string.theme_mode),
                        summary = modes[currentIndex],
                        items = modes,
                        selectedIndex = currentIndex,
                        onSelectedIndexChange = { onModeChange(modesEnum[it]) },
                        onExpandedChange = { expanded = it }
                    )

                    SwitchPreference(
                        title = stringResource(R.string.floating_navbar),
                        summary = stringResource(R.string.floating_navbar_summary),
                        checked = isFloatingNavbar,
                        onCheckedChange = onFloatingNavbarChange
                    )

                    // Glass is a style of the floating bar, so the row for it only exists while
                    // that bar is the one on screen. Expanding rather than appearing keeps the
                    // card's height continuous - the switch below does not jump.
                    FoldUnder(isFloatingNavbar) {
                        SwitchPreference(
                            title = stringResource(R.string.liquid_glass),
                            summary = stringResource(R.string.liquid_glass_summary),
                            checked = isLiquidGlass,
                            onCheckedChange = onLiquidGlassChange
                        )
                    }

                    SwitchPreference(
                        title = stringResource(R.string.blur_enabled),
                        summary = stringResource(R.string.blur_enabled_summary),
                        checked = isBlurEnabled,
                        onCheckedChange = onBlurEnabledChange
                    )
                }
            }
        }
    }
}

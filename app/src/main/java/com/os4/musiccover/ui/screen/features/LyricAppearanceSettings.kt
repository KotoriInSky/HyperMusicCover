package com.os4.musiccover.ui.screen.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.os4.musiccover.R
import com.os4.musiccover.app.ModuleBridge
import com.os4.musiccover.lyrics.LyricAppearance
import kotlin.math.roundToInt
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun LyricAppearanceSettings(
    enabled: Boolean,
    module: ModuleBridge.State,
    onChange: (ModuleBridge.State) -> Unit,
) {
    val context = LocalContext.current
    val current by rememberUpdatedState(module)
    val appearance = remember(module.lyricAppearance) { LyricAppearance.parse(module.lyricAppearance) }
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(Color.White) }
    fun update(vararg values: Pair<String, Any>) {
        val json = JSONObject(LyricAppearance.parse(current.lyricAppearance).toJson())
        values.forEach { (key, value) -> json.put(key, value) }
        val normalized = LyricAppearance.parse(json.toString()).toJson()
        onChange(current.copy(lyricAppearance = normalized))
        ModuleBridge.setLyricAppearance(context, normalized)
    }
    ValueSlider(
        title = stringResource(R.string.lyrics_bg_font_size),
        value = module.lyricBgSizeSp,
        valueRange = 10f..30f,
        enabled = enabled,
        label = { "${it.roundToInt()} sp" },
        onValueChange = {
            val size = it.roundToInt().toFloat()
            onChange(current.copy(lyricBgSizeSp = size))
            ModuleBridge.setLyricStyle(context, "bgsize", size)
        },
    )
    val modes = listOf(
        stringResource(R.string.lyrics_color_official),
        stringResource(R.string.lyrics_color_cover),
        stringResource(R.string.lyrics_color_custom),
    )
    for (backing in listOf(false, true)) {
        val role = if (backing) "bg" else "main"
        val mode = if (backing) appearance.bgMode else appearance.mainMode
        val rgb = if (backing) appearance.bgColor else appearance.mainColor
        val opacity = if (backing) appearance.bgOpacity else appearance.mainOpacity
        WindowDropdownPreference(
            title = stringResource(if (backing) R.string.lyrics_bg_color else R.string.lyrics_main_color),
            summary = if (mode == LyricAppearance.COVER) stringResource(R.string.lyrics_color_cover_summary) else null,
            items = modes,
            selectedIndex = mode,
            enabled = enabled,
            onSelectedIndexChange = { update("${role}Mode" to it) },
        )
        if (mode == LyricAppearance.CUSTOM) {
            BasicComponent(
                title = stringResource(R.string.lyrics_color_choose),
                enabled = enabled,
                endActions = { Box(Modifier.size(24.dp).background(Color(rgb), CircleShape)) },
                onClick = {
                    if (enabled) {
                        draft = Color(rgb).copy(alpha = opacity)
                        editing = role
                    }
                },
            )
        }
        ValueSlider(
            title = stringResource(if (backing) R.string.lyrics_bg_opacity else R.string.lyrics_main_opacity),
            value = opacity * 100f,
            valueRange = 0f..100f,
            enabled = enabled,
            label = { "${it.roundToInt()}%" },
            onValueChange = { update("${role}Opacity" to (it.roundToInt() / 100.0)) },
        )
    }
    TextButton(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        text = stringResource(R.string.lyrics_appearance_reset),
        enabled = enabled,
        onClick = {
            val json = LyricAppearance.DEFAULT.toJson()
            onChange(current.copy(lyricSizeSp = 25f, lyricBgSizeSp = 17f, lyricAppearance = json))
            ModuleBridge.setLyricStyle(context, "size", 25f)
            ModuleBridge.setLyricStyle(context, "bgsize", 17f)
            ModuleBridge.setLyricAppearance(context, json)
        },
    )
    WindowDialog(
        show = editing != null,
        title = stringResource(if (editing == "bg") R.string.lyrics_bg_color else R.string.lyrics_main_color),
        onDismissRequest = { editing = null },
    ) {
        Column {
            ColorPicker(color = draft, onColorChanged = { draft = it })
            TextButton(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                text = stringResource(R.string.lyrics_priority_done),
                enabled = enabled,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    editing?.let { role ->
                        update("${role}Color" to draft.toArgb(), "${role}Opacity" to draft.alpha.toDouble())
                    }
                    editing = null
                },
            )
        }
    }
}

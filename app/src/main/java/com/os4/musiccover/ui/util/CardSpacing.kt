package com.os4.musiccover.ui.util

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The one spacing every page's blocks - cards, a tab row - are laid out on, miuix's example app's
 * (SettingsPage): the first block 12 all round, every block after it 12 at the sides and 12
 * under it. A SmallTitle above a block brings its own gap and needs nothing added; nothing ends a
 * page but the last block's own 12.
 *
 * There were five spellings of this across the pages (top-only, vertical, bottom-only...), and
 * the gaps between cards differed from page to page because of it (2026-10-14).
 */
internal fun Modifier.firstCard(): Modifier = padding(12.dp)

internal fun Modifier.nextCard(): Modifier = padding(horizontal = 12.dp).padding(bottom = 12.dp)

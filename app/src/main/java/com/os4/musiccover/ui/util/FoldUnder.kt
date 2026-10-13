package com.os4.musiccover.ui.util

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * What hangs off a switch: shown under it while it is on, folded away while it is off.
 *
 * miuix's own pattern (its example app, SwitchSection): the rows a switch governs fade and
 * expand in under it, and fold away rather than grey out - a page of disabled rows says nothing
 * about why, where an empty card under an off switch says it all. Every page in this app that
 * has a master switch, or a row that only exists under another, uses this one so they all move
 * the same way.
 *
 * [settled] is whether [visible] is the real value yet. A page opens on its defaults and the
 * module answers a broadcast later; folding on that answer played an "opening" every time the
 * page was entered, with the switch going off and back on under it (2026-10-14). Until settled
 * - and on the frame it becomes so - the rows are simply where they belong; after that, only a
 * change of [visible] moves them.
 */
@Composable
internal fun FoldUnder(
    visible: Boolean,
    settled: Boolean = true,
    content: @Composable () -> Unit,
) {
    // A fresh state is a snap: it starts where it is told to be. Made again on every change
    // while unsettled, and once when it settles; kept, and so animated, from then on.
    val state = remember(settled, if (settled) Unit else visible) { MutableTransitionState(visible) }
    state.targetState = visible
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        content()
    }
}

package com.manishlad.glasslauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.manishlad.glasslauncher.LaunchableApp

/** All apps: 6-column grid over the heavy-blur backdrop. Back returns to the dock. */
@Composable
fun AllAppsScreen(
    apps: List<LaunchableApp>,
    onLaunch: (LaunchableApp) -> Unit,
    addMode: Boolean,
    onAddToDock: (LaunchableApp) -> String,
) {
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { if (message != null) { kotlinx.coroutines.delay(2500); message = null } }
    val first = remember { FocusRequester() }
    LaunchedEffect(apps.isNotEmpty()) { if (apps.isNotEmpty()) runCatching { first.requestFocus() } }

    // Grid gets 20 px of padding (taken from the 160 px page margin) so focused tiles can scale.
    val edge = 20
    Column(
        Modifier.fillMaxSize().padding(start = (160 - edge).px, end = (160 - edge).px, top = 72.px),
        verticalArrangement = Arrangement.spacedBy((48 - edge).px),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = edge.px),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.px)) {
                T(if (addMode) "Add to dock" else "Apps", 64, weight = 300, tracking = -1.5)
                T("${apps.size} apps", 24, alpha = .75f)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.px)) {
                if (message != null) Chip(message!!)
                else if (addMode) Chip("OK adds the app · Back returns")
                else { Chip("Hold OK to add to dock"); Chip("Back to Home") }
            }
        }
        AppsGrid(
            apps = apps,
            onSelect = { if (addMode) onAddToDock(it) else onLaunch(it) },
            onHold = { message = onAddToDock(it) },
            first = first,
            edge = edge,
        )
    }
}

/**
 * The 6-column app grid (All apps screen and the Home "Apps" tab). Tiles are filled with each
 * app's banner; OK selects, hold OK adds to the dock. [upTarget]: where ▲ from the top row goes.
 */
@Composable
fun AppsGrid(
    apps: List<LaunchableApp>,
    onSelect: (LaunchableApp) -> Unit,
    onHold: (LaunchableApp) -> Unit,
    modifier: Modifier = Modifier,
    first: FocusRequester? = null,
    upTarget: FocusRequester? = null,
    edge: Int = 20,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        modifier = modifier,
        contentPadding = PaddingValues(edge.px, edge.px, edge.px, 72.px),
        horizontalArrangement = Arrangement.spacedBy(40.px),
        verticalArrangement = Arrangement.spacedBy(40.px),
    ) {
        itemsIndexed(apps, key = { _, a -> a.packageName }) { i, app ->
            var focused by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(14.px), horizontalAlignment = Alignment.CenterHorizontally) {
                GlassButton(
                    onClick = { onSelect(app) },
                    onLongClick = { onHold(app) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.px)
                        .then(if (i < 6 && upTarget != null) Modifier.focusProperties { up = upTarget } else Modifier),
                    radius = 28.px,
                    focusScale = 1.08f,
                    focusRequester = if (i == 0) first else null,
                    onFocus = { focused = it },
                ) { AppArt(app, 84) }
                T(
                    app.label, 21, Modifier.fillMaxWidth(),
                    weight = if (focused) 600 else 500,
                    alpha = if (focused) 1f else .86f,
                    align = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    GlassSurface(radius = 999.dp) {
        T(text, 22, Modifier.padding(horizontal = 24.px, vertical = 12.px))
    }
}

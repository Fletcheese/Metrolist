/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R

/** FletchTunes: the "Hide played" chip above an episode list, with how many it is hiding. */
@Composable
fun HidePlayedChip(
    selected: Boolean,
    hiddenCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text("Hide played") },
            leadingIcon =
                if (selected) {
                    { Icon(painterResource(R.drawable.check), null) }
                } else {
                    null
                },
        )
        if (selected && hiddenCount > 0) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = "$hiddenCount hidden",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

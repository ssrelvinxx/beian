package com.beian.tracker.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.data.LOCAL_SOURCE

/**
 * 来源切换条：本机 / 已导入的各个对方数据包。
 * 只在有导入数据时显示。
 */
@Composable
fun SourceSelector(vm: MainViewModel, modifier: Modifier = Modifier) {
    val current by vm.sourceId.collectAsStateWithLifecycle()
    val sources by vm.importedSources.collectAsStateWithLifecycle()
    val myNickname by vm.myNickname.collectAsStateWithLifecycle()

    if (sources.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = current == LOCAL_SOURCE,
            onClick = { vm.selectSource(LOCAL_SOURCE) },
            label = { Text(myNickname.ifBlank { "本机" }) },
            colors = FilterChipDefaults.filterChipColors(),
        )
        sources.forEach { s ->
            FilterChip(
                selected = current == s.sourceId,
                onClick = { vm.selectSource(s.sourceId) },
                label = { Text(s.nickname) },
            )
        }
    }
}

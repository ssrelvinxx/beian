package com.beian.tracker.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
import com.beian.tracker.util.ReportExporter
import com.beian.tracker.util.UsageStatsReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val amapKey by vm.amapKey.collectAsStateWithLifecycle()
    val day by vm.selectedDay.collectAsStateWithLifecycle()
    val points by vm.todayPoints.collectAsStateWithLifecycle()

    var keyInput by remember(amapKey) { mutableStateOf(amapKey) }
    val scope = remember { CoroutineScope(Dispatchers.Main) }

    val locationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val usageGranted = UsageStatsReader.hasPermission(context)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        // 高德 Key
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = stringResource(R.string.settings_amap_key), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (amapKey.isBlank()) {
                        stringResource(R.string.settings_amap_key_hint)
                    } else {
                        stringResource(R.string.settings_amap_configured)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { vm.setAmapKey(keyInput) }) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        }

        // 权限
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = stringResource(R.string.settings_permissions), style = MaterialTheme.typography.titleMedium)

                PermRow(
                    label = stringResource(R.string.settings_perm_location),
                    granted = locationGranted,
                    onGrant = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                )
                PermRow(
                    label = stringResource(R.string.settings_perm_usage),
                    granted = usageGranted,
                    onGrant = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                )
            }
        }

        // 导出报备
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = stringResource(R.string.settings_export), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.settings_export_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        scope.launch {
                            val snapshots = vm.snapshotsOfDayOnce(day)
                            val file = ReportExporter.export(
                                context = context,
                                day = day,
                                points = points,
                                snapshots = snapshots,
                                distanceMeters = vm.totalDistance(points),
                            )
                            Toast.makeText(
                                context,
                                context.getString(R.string.export_done),
                                Toast.LENGTH_SHORT,
                            ).show()
                            ReportExporter.share(
                                context,
                                file,
                                context.getString(R.string.export_share_title),
                            )
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.settings_share))
                }
            }
        }

        Text(
            text = stringResource(R.string.settings_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        if (granted) {
            Text(
                text = stringResource(R.string.settings_granted),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            OutlinedButton(onClick = onGrant) {
                Text(text = stringResource(R.string.settings_grant))
            }
        }
    }
}
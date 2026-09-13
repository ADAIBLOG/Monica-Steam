package takagi.ru.monica.steam.workshop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import takagi.ru.monica.R

@Composable
internal fun SteamWorkshopEntry(appId: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var retry by remember(appId) { mutableIntStateOf(0) }
    val service = remember { SteamWorkshopService() }
    val support by produceState<Result<Boolean>?>(null, appId, retry) {
        value = null
        value = try { Result.success(service.supportsWorkshop(appId)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }
    when {
        support?.getOrNull() == false -> Unit
        support?.getOrNull() == true -> ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Extension, contentDescription = null)
                Column {
                    Text(stringResource(R.string.workshop_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.workshop_entry), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        support == null -> Text(stringResource(R.string.workshop_checking), modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> TextButton(onClick = { retry++ }, modifier = modifier) {
            Text(stringResource(R.string.workshop_check_failed) + " · " + stringResource(R.string.workshop_retry))
        }
    }
}

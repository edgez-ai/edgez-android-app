package ai.edgez.edgez

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
fun SensorInfoCard(
    sensor: DeviceSensorDefinition?,
    modifier: Modifier = Modifier,
) {
    if (sensor == null || sensor.key.isBlank()) return

    val context = LocalContext.current
    val imageResource = sensor.image
        .takeIf { it.isNotBlank() }
        ?.let { context.resources.getIdentifier(it, "drawable", context.packageName) }
        ?: 0

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (imageResource != 0) {
                Image(
                    painter = painterResource(imageResource),
                    contentDescription = sensor.name,
                    modifier = Modifier.size(104.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(sensor.name, style = MaterialTheme.typography.titleSmall)
                if (sensor.description.isNotBlank()) {
                    Text(sensor.description, style = MaterialTheme.typography.bodySmall)
                }
                if (sensor.purchaseUrl.isNotBlank()) {
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(sensor.purchaseUrl)),
                                )
                            }
                        },
                    ) {
                        Text("Where to buy")
                    }
                }
            }
        }
    }
}

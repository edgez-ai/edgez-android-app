package ai.edgez.edgez

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

private data class SensorSeries(
    val label: String,
    val unit: String,
    val color: Color,
    val values: List<Pair<Long, Double>>,
)

@Composable
fun DeviceDetailScreen(
    user: HaLowUser,
    samples: List<SensorSample>,
    onBack: () -> Unit,
) {
    val latest = samples.lastOrNull()?.data

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                    Column {
                        Text("Conversation · ${user.deviceType.label}", style = MaterialTheme.typography.titleLarge)
                        Text(user.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text("Node ${user.nodeId}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                DeviceSummaryCard(user)
            }

            item {
                GeoFenceCard(user.geoFence, user.geoIndex)
            }

            item {
                SensorLatestCard(latest, samples.lastOrNull()?.timestampMs)
            }

            item {
                SensorChartCard(samples)
            }
        }
    }
}

@Composable
private fun DeviceSummaryCard(user: HaLowUser) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Device", style = MaterialTheme.typography.titleMedium)
            Text("Type ${user.deviceType.label}", style = MaterialTheme.typography.bodyMedium)
            Text("Marker ${NodeMapMarker.fromId(user.marker).label}", style = MaterialTheme.typography.bodyMedium)
            Text("User ${user.userIdText}", style = MaterialTheme.typography.bodySmall)
            if (user.sleeping) {
                Text("Sleeping", style = MaterialTheme.typography.bodySmall)
            }
            if (user.latitude != null && user.longitude != null) {
                Text(
                    "Location ${formatCoordinate(user.latitude)}, ${formatCoordinate(user.longitude)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun GeoFenceCard(geoFence: DeviceGeoFence?, geoIndex: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Geo fence", style = MaterialTheme.typography.titleMedium)
            if (geoFence == null) {
                Text("None", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(geoFence.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${NodeMapMarker.fromId(geoFence.marker).label} · ${geoFence.alertCondition.label}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Index $geoIndex", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SensorLatestCard(data: EdgeZSensorData?, timestampMs: Long?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Sensor", style = MaterialTheme.typography.titleMedium)
            if (data == null || !data.hasAnyValue) {
                Text("No sensor data received yet", style = MaterialTheme.typography.bodyMedium)
            } else {
                SensorValueRows(data)
                timestampMs?.let {
                    Text("Updated ${formatSensorAge(it)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SensorValueRows(data: EdgeZSensorData) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SensorValueRow("Temperature", data.temperature, "°C")
        SensorValueRow("Humidity", data.humidity, "%")
        SensorValueRow("Pressure", data.pressure, "hPa")
        SensorValueRow("Altitude", data.altitude, "m")
        if (data.latitude != null && data.longitude != null) {
            Text(
                "Position ${formatCoordinate(data.latitude)}, ${formatCoordinate(data.longitude)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SensorValueRow(label: String, value: Double?, unit: String) {
    if (value == null) return
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text("${formatSensorValue(value)} $unit", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SensorChartCard(samples: List<SensorSample>) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val series = listOf(
        SensorSeries("Temperature", "°C", primary, samples.mapNotNull { sample -> sample.data.temperature?.let { sample.timestampMs to it } }),
        SensorSeries("Humidity", "%", secondary, samples.mapNotNull { sample -> sample.data.humidity?.let { sample.timestampMs to it } }),
        SensorSeries("Pressure", "hPa", tertiary, samples.mapNotNull { sample -> sample.data.pressure?.let { sample.timestampMs to it } }),
    ).filter { it.values.isNotEmpty() }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Sensor time series", style = MaterialTheme.typography.titleMedium)
            if (series.isEmpty()) {
                Text("No chartable sensor values yet", style = MaterialTheme.typography.bodyMedium)
            } else {
                SensorLineChart(series = series, outline = outline)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    series.forEach {
                        Row {
                            Canvas(modifier = Modifier.width(12.dp).height(12.dp)) {
                                drawCircle(it.color)
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("${it.label} ${it.unit}", style = MaterialTheme.typography.bodySmall, color = onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SensorLineChart(series: List<SensorSeries>, outline: Color) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
        val paddingLeft = 8.dp.toPx()
        val paddingRight = 8.dp.toPx()
        val paddingTop = 12.dp.toPx()
        val paddingBottom = 20.dp.toPx()
        val chartLeft = paddingLeft
        val chartRight = size.width - paddingRight
        val chartTop = paddingTop
        val chartBottom = size.height - paddingBottom
        val allValues = series.flatMap { it.values }
        val minTime = allValues.minOf { it.first }
        val maxTime = allValues.maxOf { it.first }.takeIf { it > minTime } ?: (minTime + 1L)
        val minValue = allValues.minOf { it.second }
        val maxValue = allValues.maxOf { it.second }.takeIf { it > minValue } ?: (minValue + 1.0)

        drawLine(outline, Offset(chartLeft, chartBottom), Offset(chartRight, chartBottom), strokeWidth = 1.dp.toPx())
        drawLine(outline, Offset(chartLeft, chartTop), Offset(chartLeft, chartBottom), strokeWidth = 1.dp.toPx())

        series.forEach { sensorSeries ->
            val points = sensorSeries.values.map { (timestamp, value) ->
                val x = chartLeft + ((timestamp - minTime).toFloat() / (maxTime - minTime).toFloat()) * (chartRight - chartLeft)
                val y = chartBottom - ((value - minValue).toFloat() / (maxValue - minValue).toFloat()) * (chartBottom - chartTop)
                Offset(x, y)
            }
            points.zipWithNext().forEach { (start, end) ->
                drawLine(
                    color = sensorSeries.color,
                    start = start,
                    end = end,
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            points.forEach { point ->
                drawCircle(sensorSeries.color, radius = 3.dp.toPx(), center = point, style = Stroke(width = 1.5.dp.toPx()))
            }
        }
    }
}

private fun formatSensorValue(value: Double): String {
    return if (value.roundToInt().toDouble() == value) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.2f", value)
    }
}

private fun formatCoordinate(value: Double): String {
    return String.format(Locale.US, "%.6f", value)
}

private fun formatSensorAge(timestampMs: Long): String {
    val seconds = ((System.currentTimeMillis() - timestampMs).coerceAtLeast(0L)) / 1000L
    if (seconds < 60L) return "just now"
    val minutes = seconds / 60L
    if (minutes < 60L) return "${minutes}min ago"
    val hours = minutes / 60L
    return "${hours}h ago"
}

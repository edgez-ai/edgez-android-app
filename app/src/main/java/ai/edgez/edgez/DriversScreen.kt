package ai.edgez.edgez

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun DriversScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uartI2cDrivers = remember(context) {
        DeviceSensorCatalog.sensorDefinitionsFor(context, DeviceSensorConnector.UART_I2C)
            .filter { it.key.isNotBlank() }
    }
    val rs485Drivers = remember(context) {
        DeviceSensorCatalog.sensorDefinitionsFor(context, DeviceSensorConnector.RS485)
            .filter { it.key.isNotBlank() }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Drivers", style = MaterialTheme.typography.headlineMedium)
            }
            item {
                Text("UART / I2C", style = MaterialTheme.typography.titleMedium)
            }
            items(uartI2cDrivers.size) { index ->
                SensorInfoCard(sensor = uartI2cDrivers[index])
            }
            item {
                Text("RS485", style = MaterialTheme.typography.titleMedium)
            }
            items(rs485Drivers.size) { index ->
                SensorInfoCard(sensor = rs485Drivers[index])
            }
        }
    }
}

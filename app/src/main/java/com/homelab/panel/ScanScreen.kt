package com.homelab.panel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Pasos del buscador. */
private enum class ScanStep { INTRO, DEVICES, SERVICES }

/**
 * Buscador de servicios en la red, en dos pasos: primero los equipos, y del equipo que
 * elija el usuario, todos sus paneles web.
 */
@Composable
fun ScanScreen(
    config: PanelConfig,
    onConfigChange: (PanelConfig) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val alcance = rememberCoroutineScope()

    var paso by remember { mutableStateOf(ScanStep.INTRO) }
    var trabajando by remember { mutableStateOf(false) }
    var tarea by remember { mutableStateOf<Job?>(null) }
    var progreso by remember { mutableStateOf<NetworkScan.Progress?>(null) }

    var equipos by remember { mutableStateOf<List<NetworkScan.Device>>(emptyList()) }
    var equipoElegido by remember { mutableStateOf<NetworkScan.Device?>(null) }
    var servicios by remember { mutableStateOf<List<NetworkScan.WebService>>(emptyList()) }
    /** Puertos del equipo que ya están en el panel: se enseñan, pero no se vuelven a añadir. */
    var yaEnElPanel by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val elegidos = remember { mutableStateMapOf<Int, Boolean>() }
    var aviso by remember { mutableStateOf<String?>(null) }

    // Las redes se releen cada vez que se abre el selector: encender la WiFi con la VPN
    // puesta cambia la respuesta, y antes se quedaba con la primera lectura para siempre.
    var redes by remember { mutableStateOf(NetworkScan.networks(context)) }
    var red by remember { mutableStateOf(redes.firstOrNull()) }
    var eligiendoRed by remember { mutableStateOf(false) }

    fun buscarEquipos() {
        val donde = red ?: return
        paso = ScanStep.DEVICES
        trabajando = true
        equipos = emptyList()
        progreso = null

        tarea = alcance.launch {
            equipos = NetworkScan.findDevices(context, donde) { avance -> progreso = avance }
            trabajando = false
        }
    }

    fun buscarServicios(equipo: NetworkScan.Device) {
        equipoElegido = equipo
        paso = ScanStep.SERVICES
        trabajando = true
        servicios = emptyList()
        elegidos.clear()
        progreso = null

        // Los que ya están en el panel se muestran igual, para que se vea que también se
        // han encontrado; simplemente vienen desmarcados y no se pueden duplicar.
        yaEnElPanel = config.allServices.mapNotNull { servicio ->
            val url = config.urlOf(servicio, away = false)
            if (hostOf(url) == equipo.host) {
                runCatching { java.net.URI(url).port }.getOrNull()
            } else {
                null
            }
        }.toSet()

        tarea = alcance.launch {
            servicios = NetworkScan.scanDevice(
                host = equipo.host,
                remote = red?.remote == true
            ) { avance, parciales ->
                progreso = avance
                servicios = parciales
            }

            servicios.forEach { elegidos[it.port] = it.port !in yaEnElPanel }
            trabajando = false
        }
    }

    fun anadir() {
        val equipo = equipoElegido ?: return
        val seleccionados = servicios.filter { elegidos[it.port] == true }
        if (seleccionados.isEmpty()) return

        var nueva = config

        // Un solo servidor por equipo, reutilizando el que ya hubiera.
        val existente = nueva.servers.firstOrNull { it.hostHome == equipo.host }
        val servidorId = existente?.id ?: "srv-${System.currentTimeMillis()}"

        if (existente == null) {
            nueva = nueva.copy(
                servers = nueva.servers + Server(
                    id = servidorId,
                    name = equipo.name,
                    hostHome = equipo.host
                )
            )
        }

        val nuevos = seleccionados.mapIndexed { indice, servicio ->
            Service(
                id = "svc-${System.currentTimeMillis()}-$indice",
                name = servicio.name.ifBlank {
                    context.getString(R.string.scan_unknown_service, servicio.port)
                },
                serverId = servidorId,
                scheme = servicio.scheme,
                port = servicio.port,
                path = servicio.path,
                category = servicio.category,
                desktopMode = servicio.desktop
            )
        }

        val nombreGrupo = equipo.name
        val grupo = nueva.groups.firstOrNull { it.name == nombreGrupo }

        nueva = if (grupo != null) {
            nueva.copy(
                groups = nueva.groups.map {
                    if (it.id == grupo.id) it.copy(services = it.services + nuevos) else it
                }
            )
        } else {
            nueva.copy(
                groups = nueva.groups + ServiceGroup(
                    id = "grp-${System.currentTimeMillis()}",
                    name = nombreGrupo,
                    services = nuevos
                )
            )
        }

        onConfigChange(nueva)
        aviso = context.resources.getQuantityString(
            R.plurals.scan_added,
            nuevos.size,
            nuevos.size
        )
        paso = ScanStep.DEVICES
        servicios = emptyList()
    }

    fun atras() {
        tarea?.cancel()
        trabajando = false

        when (paso) {
            ScanStep.SERVICES -> paso = ScanStep.DEVICES
            ScanStep.DEVICES -> paso = ScanStep.INTRO
            ScanStep.INTRO -> onClose()
        }
    }

    BackHandler { atras() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 4.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { atras() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.back),
                    tint = Color.White
                )
            }
            Text(
                equipoElegido?.name?.takeIf { paso == ScanStep.SERVICES }
                    ?: stringResource(R.string.scan_title),
                color = Color.White,
                fontSize = 17.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }

        when {
            paso == ScanStep.INTRO -> Intro(
                red = red,
                aviso = aviso,
                onCambiarRed = {
                    redes = NetworkScan.networks(context)
                    eligiendoRed = true
                },
                onEmpezar = { buscarEquipos() }
            )

            paso == ScanStep.DEVICES -> PasoDeEquipos(
                equipos = equipos,
                trabajando = trabajando,
                progreso = progreso,
                aviso = aviso,
                onElegir = { buscarServicios(it) },
                onRepetir = { aviso = null; buscarEquipos() }
            )

            else -> PasoDeServicios(
                equipo = equipoElegido,
                servicios = servicios,
                yaEnElPanel = yaEnElPanel,
                trabajando = trabajando,
                progreso = progreso,
                elegidos = elegidos,
                onAnadir = { anadir() },
                onParar = { tarea?.cancel(); trabajando = false }
            )
        }
    }

    if (eligiendoRed) {
        SelectorDeRed(
            redes = redes,
            actual = red,
            onCancel = { eligiendoRed = false },
            onPick = { red = it; eligiendoRed = false }
        )
    }
}

@Composable
private fun Intro(
    red: NetworkScan.NetworkOption?,
    aviso: String?,
    onCambiarRed: () -> Unit,
    onEmpezar: () -> Unit
) {
    Column(Modifier.padding(vertical = 20.dp)) {
        aviso?.let {
            Text(
                it,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
            )
        }

        Text(
            stringResource(R.string.scan_intro),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.scan_where),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 18.dp, bottom = 4.dp)
        )

        ClickableRow(
            title = red?.let { tituloDeRed(it) }
                ?: stringResource(R.string.scan_network_none),
            subtitle = red?.let { stringResource(tipoDeRed(it.kind)) },
            leading = {
                Icon(
                    Icons.Default.Wifi,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            },
            onClick = onCambiarRed
        )

        // Por el túnel cada respuesta tarda diez veces más, y sin avisar parece que se ha
        // quedado colgado.
        if (red?.remote == true) {
            Text(
                stringResource(R.string.scan_slow_warning),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp)
            )
        }

        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onEmpezar,
            enabled = red != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Text(stringResource(R.string.scan_start))
        }
    }
}

/**
 * Elección de la red donde buscar.
 *
 * Hace falta porque el móvil puede estar en varias a la vez y el sistema no siempre da la
 * que interesa: con la VPN puesta, la red por omisión es la VPN aunque la WiFi esté
 * encendida. Y con un enrutador de subred, la red de casa aparece aquí también cuando se
 * está fuera, que es justo cuando más se agradece.
 */
@Composable
private fun SelectorDeRed(
    redes: List<NetworkScan.NetworkOption>,
    actual: NetworkScan.NetworkOption?,
    onCancel: () -> Unit,
    onPick: (NetworkScan.NetworkOption) -> Unit
) {
    var manual by remember { mutableStateOf("") }
    val escrita = NetworkScan.manualNetwork(manual)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.scan_where_title)) },
        text = {
            Column {
                if (redes.isEmpty()) {
                    Text(
                        stringResource(R.string.scan_no_network),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    Spacer(Modifier.height(12.dp))
                }

                redes.forEach { opcion ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(opcion) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = opcion.prefix == actual?.prefix,
                            onClick = { onPick(opcion) }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                tituloDeRed(opcion),
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(tipoDeRed(opcion.kind)),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    label = { Text(stringResource(R.string.scan_manual)) },
                    placeholder = { Text("192.168.1.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { escrita?.let(onPick) },
                    enabled = escrita != null
                ) {
                    Text(stringResource(R.string.scan_manual_use))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** Lo que se lee en la fila: un rango, o cuántos equipos trae la VPN. */
@Composable
private fun tituloDeRed(red: NetworkScan.NetworkOption): String =
    if (red.hosts.isNotEmpty()) {
        pluralStringResource(R.plurals.scan_mesh_hosts, red.hosts.size, red.hosts.size)
    } else {
        "${red.prefix}x"
    }

private fun tipoDeRed(kind: NetworkScan.NetworkKind): Int = when (kind) {
    NetworkScan.NetworkKind.WIFI -> R.string.scan_network_wifi
    NetworkScan.NetworkKind.ETHERNET -> R.string.scan_network_ethernet
    NetworkScan.NetworkKind.VPN -> R.string.scan_network_vpn
    NetworkScan.NetworkKind.ROUTED -> R.string.scan_network_routed
    NetworkScan.NetworkKind.MESH -> R.string.scan_network_mesh
    NetworkScan.NetworkKind.MANUAL -> R.string.scan_network_manual
    NetworkScan.NetworkKind.OTHER -> R.string.scan_network_other
}

@Composable
private fun PasoDeEquipos(
    equipos: List<NetworkScan.Device>,
    trabajando: Boolean,
    progreso: NetworkScan.Progress?,
    aviso: String?,
    onElegir: (NetworkScan.Device) -> Unit,
    onRepetir: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        aviso?.let {
            Text(
                it,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }

        if (trabajando) {
            Avance(stringResource(R.string.scan_step_devices), progreso)
        } else {
            Text(
                stringResource(
                    if (equipos.isEmpty()) R.string.scan_no_devices
                    else R.string.scan_choose_device
                ),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            items(equipos, key = { it.host }) { equipo ->
                ClickableRow(
                    title = equipo.name,
                    monospaceLine = equipo.host,
                    leading = {
                        Icon(
                            Icons.Default.Computer,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    onClick = { onElegir(equipo) }
                )
            }
        }

        if (!trabajando) {
            OutlinedButton(
                onClick = onRepetir,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(stringResource(R.string.scan_again))
            }
        }
    }
}

@Composable
private fun PasoDeServicios(
    equipo: NetworkScan.Device?,
    servicios: List<NetworkScan.WebService>,
    yaEnElPanel: Set<Int>,
    trabajando: Boolean,
    progreso: NetworkScan.Progress?,
    elegidos: MutableMap<Int, Boolean>,
    onAnadir: () -> Unit,
    onParar: () -> Unit
) {
    val cuenta = servicios.count { elegidos[it.port] == true && it.port !in yaEnElPanel }

    Column(Modifier.fillMaxSize()) {
        if (trabajando) {
            Avance(
                stringResource(R.string.scan_step_ports, equipo?.name.orEmpty()),
                progreso
            )
        } else {
            Text(
                if (servicios.isEmpty()) {
                    stringResource(R.string.scan_no_web)
                } else {
                    pluralStringResource(R.plurals.scan_found, servicios.size, servicios.size)
                },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            items(servicios, key = { it.port }) { servicio ->
                FilaDeServicioEncontrado(
                    servicio = servicio,
                    marcado = elegidos[servicio.port] == true,
                    yaEsta = servicio.port in yaEnElPanel,
                    onToggle = { elegidos[servicio.port] = it }
                )
            }
        }

        Column(Modifier.padding(16.dp)) {
            if (trabajando) {
                OutlinedButton(onClick = onParar, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.scan_stop))
                }
            } else if (servicios.isNotEmpty()) {
                Text(
                    stringResource(R.string.scan_rename_hint),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                Button(
                    onClick = onAnadir,
                    enabled = cuenta > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(pluralStringResource(R.plurals.scan_add, cuenta, cuenta))
                }
            }
        }
    }
}

@Composable
private fun FilaDeServicioEncontrado(
    servicio: NetworkScan.WebService,
    marcado: Boolean,
    yaEsta: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val opacidad = if (yaEsta) 0.5f else 1f

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .clickable(enabled = !yaEsta) { onToggle(!marcado) }
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = marcado, enabled = !yaEsta, onCheckedChange = onToggle)

            Icon(
                Categories.icon(servicio.category),
                null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = opacidad),
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(24.dp)
            )

            Column(Modifier.weight(1f)) {
                Text(
                    servicio.name.ifBlank {
                        stringResource(R.string.scan_unknown_service, servicio.port)
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = opacidad)
                )
                Text(
                    "${servicio.scheme}://${servicio.host}:${servicio.port}${servicio.path}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = opacidad * 0.6f)
                )
                if (yaEsta) {
                    Text(
                        stringResource(R.string.scan_already_added),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Avance(texto: String, progreso: NetworkScan.Progress?) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(texto, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(10.dp))

        val total = progreso?.total ?: 1
        val hechos = progreso?.done ?: 0

        LinearProgressIndicator(
            progress = { if (total > 0) hechos.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "$hechos / $total",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}


package com.homelab.panel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Etiqueta traducida de un tipo de enlace. */
@Composable
fun linkKindLabel(kind: LinkKind): String = stringResource(
    when (kind) {
        LinkKind.ED2K -> R.string.link_ed2k
        LinkKind.MAGNET -> R.string.link_magnet
        LinkKind.TORRENT -> R.string.link_torrent
        LinkKind.NZB -> R.string.link_nzb
    }
)

/**
 * Todas las etiquetas de una vez. Hace falta porque los textos solo se pueden leer desde
 * una función de interfaz, y dentro de un `joinToString` o un `map` ya no se puede.
 */
@Composable
fun linkKindLabels(): Map<LinkKind, String> = mapOf(
    LinkKind.ED2K to stringResource(R.string.link_ed2k),
    LinkKind.MAGNET to stringResource(R.string.link_magnet),
    LinkKind.TORRENT to stringResource(R.string.link_torrent),
    LinkKind.NZB to stringResource(R.string.link_nzb)
)

/**
 * Ajustes de descargas: los destinos y qué hacer con cada tipo de enlace.
 *
 * Está en su propia sección a propósito: metido entre los demás ajustes, con cinco
 * clases de servicio y sus credenciales, la lista se haría interminable.
 */
@Composable
fun DownloadsScreen(
    config: PanelConfig,
    onConfigChange: (PanelConfig) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var enEdicion by remember { mutableStateOf<DownloadTarget?>(null) }
    val etiquetas = linkKindLabels()

    enEdicion?.let { destino ->
        TargetEditor(
            target = destino,
            config = config,
            onSave = { nuevo ->
                onConfigChange(guardarDestino(config, nuevo))
                enEdicion = null
            },
            onDelete = if (config.downloadTargets.any { it.id == destino.id }) {
                {
                    // La contraseña del destino no vive en la configuración sino en el
                    // almacén cifrado, así que quitarlo de la lista no la borra: sin esto
                    // se queda en el móvil para siempre. Con los servicios ya se hacía
                    // —ver `AutoLogin.forget`—, y aquí faltaba.
                    SecureStore.forgetTarget(context, destino.id)
                    onConfigChange(borrarDestino(config, destino.id))
                    enEdicion = null
                }
            } else {
                null
            },
            onCancel = { enEdicion = null }
        )
        return
    }

    BackHandler { onClose() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Sitio para el teclado: sin esto tapa los últimos campos de la ficha.
            .imePadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 4.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.back),
                    tint = Color.White
                )
            }
            Text(
                stringResource(R.string.settings_downloads),
                color = Color.White,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { enEdicion = DownloadTarget() }) {
                Icon(Icons.Default.Add, stringResource(R.string.add), tint = Color.White)
            }
        }

        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {

            Text(
                stringResource(R.string.downloads_help),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
            )

            if (config.downloadTargets.isEmpty()) {
                Text(
                    stringResource(R.string.downloads_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            }

            config.downloadTargets.forEach { destino ->
                ClickableRow(
                    title = destino.name,
                    monospaceLine = config.urlOf(destino, away = false),
                    accentLine = destino.acceptedKinds.joinToString(" · ") {
                        etiquetas[it].orEmpty()
                    },
                    onClick = { enEdicion = destino }
                )
            }

            if (config.downloadTargets.isNotEmpty()) {
                TituloDeSeccion(
                    stringResource(R.string.routing_title),
                    Modifier.padding(start = 18.dp)
                )

                LinkKind.entries.forEach { kind ->
                    val candidatos = config.targetsFor(kind)
                    if (candidatos.isEmpty()) return@forEach

                    FilaDeEnrutado(
                        kind = kind,
                        candidatos = candidatos,
                        elegido = config.linkRouting[kind.name].orEmpty(),
                        onChange = { id ->
                            onConfigChange(
                                config.copy(linkRouting = config.linkRouting + (kind.name to id))
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilaDeEnrutado(
    kind: LinkKind,
    candidatos: List<DownloadTarget>,
    elegido: String,
    onChange: (String) -> Unit
) {
    var abierto by remember { mutableStateOf(false) }
    val destino = candidatos.firstOrNull { it.id == elegido }

    Box {
        ClickableRow(
            title = linkKindLabel(kind),
            trailingText = destino?.name ?: stringResource(R.string.routing_ask),
            onClick = { abierto = true }
        )

        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.routing_ask)) },
                onClick = { abierto = false; onChange("") }
            )
            candidatos.forEach { candidato ->
                DropdownMenuItem(
                    text = { Text(candidato.name) },
                    onClick = { abierto = false; onChange(candidato.id) }
                )
            }
        }
    }
}

@Composable
private fun TargetEditor(
    target: DownloadTarget,
    config: PanelConfig,
    onSave: (DownloadTarget) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    var nombre by remember { mutableStateOf(target.name) }
    var clase by remember { mutableStateOf(target.targetKind) }
    var servidorId by remember { mutableStateOf(target.serverId) }
    var esquema by remember { mutableStateOf(target.scheme) }
    var puerto by remember { mutableStateOf(if (target.port > 0) target.port.toString() else "") }
    var ruta by remember { mutableStateOf(target.path) }
    var urlPropia by remember { mutableStateOf(target.urlOwn) }
    var urlPropiaFuera by remember { mutableStateOf(target.urlOwnAway) }
    var usuario by remember { mutableStateOf(target.username) }
    var contrasena by remember { mutableStateOf("") }
    var marcadorVisible by remember { mutableStateOf(true) }
    var aceptados by remember { mutableStateOf(target.acceptedKinds) }

    val hayContrasena = remember {
        SecureStore.has(context, SecureStore.targetKey(target.id))
    }

    var claseAbierta by remember { mutableStateOf(false) }
    var servidorAbierto by remember { mutableStateOf(false) }

    val conServidor = servidorId.isNotBlank()
    val valido = nombre.isNotBlank() && (conServidor || urlPropia.isNotBlank())

    // Igual que en las fichas de servicio y de servidor: salir sin guardar avisa, y sin
    // haber tocado nada no molesta.
    fun estadoDeLaFicha(): List<Any?> = listOf(
        nombre, clase, servidorId, esquema, puerto, ruta, urlPropia, urlPropiaFuera,
        usuario, contrasena, marcadorVisible, aceptados
    )

    val estadoInicial = remember { estadoDeLaFicha() }
    val hayCambios = estadoDeLaFicha() != estadoInicial

    var confirmandoSalida by remember { mutableStateOf(false) }

    fun salir() {
        if (hayCambios) confirmandoSalida = true else onCancel()
    }

    BackHandler { salir() }

    if (confirmandoSalida) {
        AvisoDeCambiosSinGuardar(
            onDescartar = {
                confirmandoSalida = false
                onCancel()
            },
            onSeguir = { confirmandoSalida = false }
        )
    }

    fun guardar() {
        val id = target.id.ifBlank { "dl-${System.currentTimeMillis()}" }
        if (contrasena.isNotEmpty()) {
            SecureStore.save(context, SecureStore.targetKey(id), contrasena)
        }
        onSave(
            target.copy(
                id = id,
                name = nombre.trim(),
                kind = clase.name,
                serverId = servidorId,
                scheme = esquema,
                port = puerto.toIntOrNull() ?: 0,
                path = ruta.trim().ifBlank { "/" },
                urlOwn = urlPropia.trim(),
                urlOwnAway = urlPropiaFuera.trim(),
                username = usuario.trim(),
                accepts = aceptados.map { it.name }
            )
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Sitio para el teclado: sin esto tapa los últimos campos de la ficha.
            .imePadding()
    ) {
        // La misma barra que las fichas de servicio y servidor: guardar y borrar como
        // iconos. Antes iban con su nombre escrito y esta era la única ficha distinta.
        BarraDeEdicion(
            titulo = stringResource(
                if (onDelete == null) R.string.target_new else R.string.target_edit
            ),
            guardarActivado = valido,
            onGuardar = { guardar() },
            onCancelar = { salir() },
            onBorrar = onDelete
        )

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(10.dp))

            EtiquetaDeSelector(stringResource(R.string.target_kind))

            Box {
                OutlinedButton(
                    onClick = { claseAbierta = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(clase.displayName)
                }
                DropdownMenu(expanded = claseAbierta, onDismissRequest = { claseAbierta = false }) {
                    TargetKind.entries.forEach { candidata ->
                        DropdownMenuItem(
                            text = { Text(candidata.displayName) },
                            onClick = {
                                claseAbierta = false
                                clase = candidata
                                aceptados = candidata.defaults
                                if (nombre.isBlank()) nombre = candidata.displayName
                                ServiceTemplates.all
                                    .firstOrNull { it.name.equals(candidata.displayName, true) }
                                    ?.let { if (puerto.isBlank()) puerto = it.port.toString() }
                            }
                        )
                    }
                }
            }

            // Cómo se le manda un enlace, dicho aquí mismo: el botón está en el panel y no
            // en esta pantalla, así que quien acaba de dar de alta su aMule no tiene por
            // qué haberlo encontrado. Con ed2k además es el único camino de verdad.
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (clase == TargetKind.AMULE) {
                    stringResource(R.string.target_send_help_ed2k)
                } else {
                    stringResource(R.string.target_send_help)
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
            )

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = nombre,
                onValueChange = { nombre = it },
                label = { Text(stringResource(R.string.field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            EtiquetaDeSelector(stringResource(R.string.field_server))

            Box {
                OutlinedButton(
                    onClick = { servidorAbierto = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(config.server(servidorId)?.name ?: stringResource(R.string.server_none))
                }
                DropdownMenu(
                    expanded = servidorAbierto,
                    onDismissRequest = { servidorAbierto = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.server_none)) },
                        onClick = { servidorId = ""; servidorAbierto = false }
                    )
                    config.servers.forEach { servidor ->
                        DropdownMenuItem(
                            text = { Text("${servidor.name} · ${servidor.hostHome}") },
                            onClick = { servidorId = servidor.id; servidorAbierto = false }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (conServidor) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = puerto,
                        onValueChange = { puerto = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.field_port)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = ruta,
                        onValueChange = { ruta = it },
                        label = { Text(stringResource(R.string.field_path)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                OutlinedTextField(
                    value = urlPropia,
                    onValueChange = { urlPropia = it },
                    label = { Text(stringResource(R.string.field_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = urlPropiaFuera,
                    onValueChange = { urlPropiaFuera = it },
                    label = { Text(stringResource(R.string.field_url_away)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(10.dp))

            // SABnzbd no tiene usuario ni contraseña: se identifica con una clave API que
            // el usuario copia de sus ajustes. Enseñarle dos casillas que no le sirven de
            // nada es la forma más rápida de que configure mal el destino.
            if (clase == TargetKind.SABNZBD) {
                PasswordField(
                    value = contrasena,
                    onValueChange = { contrasena = it },
                    hasSaved = hayContrasena,
                    showingSavedMarker = marcadorVisible,
                    onMarkerCleared = { marcadorVisible = false },
                    label = stringResource(R.string.field_api_key)
                )
                Text(
                    stringResource(R.string.field_api_key_help),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            } else {
                OutlinedTextField(
                    value = usuario,
                    onValueChange = { usuario = it },
                    label = { Text(stringResource(R.string.field_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = contrasena,
                    onValueChange = { contrasena = it },
                    hasSaved = hayContrasena,
                    showingSavedMarker = marcadorVisible,
                    onMarkerCleared = { marcadorVisible = false }
                )
            }

            TituloDeSeccion(stringResource(R.string.target_accepts))

            clase.defaults.forEach { kind ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = kind in aceptados,
                        onCheckedChange = { marcado ->
                            aceptados = if (marcado) aceptados + kind else aceptados - kind
                        }
                    )
                    Text(linkKindLabel(kind), fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/** Rótulo encima de un botón desplegable, para que se sepa qué se está eligiendo. */
@Composable
private fun EtiquetaDeSelector(texto: String) {
    Text(
        texto,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

private fun guardarDestino(config: PanelConfig, destino: DownloadTarget): PanelConfig {
    val existe = config.downloadTargets.any { it.id == destino.id }

    return config.copy(
        downloadTargets = if (existe) {
            config.downloadTargets.map { if (it.id == destino.id) destino else it }
        } else {
            config.downloadTargets + destino
        }
    )
}

private fun borrarDestino(config: PanelConfig, id: String): PanelConfig = config.copy(
    downloadTargets = config.downloadTargets.filter { it.id != id },
    linkRouting = config.linkRouting.filterValues { it != id }
)

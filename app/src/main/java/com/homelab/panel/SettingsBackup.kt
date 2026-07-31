package com.homelab.panel

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun PaginaDeCopia(config: PanelConfig, onConfigChange: (PanelConfig) -> Unit) {
    val context = LocalContext.current

    // Los resultados van en un aviso flotante, no en un texto al final de la página: la
    // página se desplaza, y quien exporta desde arriba no llegaba a ver si había salido
    // bien.
    fun avisar(texto: String) = Toast.makeText(context, texto, Toast.LENGTH_LONG).show()

    // Nada se aplica sin confirmar: reemplazar la configuración a ciegas sería un
    // destrozo irreversible para quien ya la tenga montada.
    var copiaPendiente by remember { mutableStateOf<PanelConfig?>(null) }
    var panelPendiente by remember { mutableStateOf<PanelImport.Result?>(null) }
    var direccionOrigen by remember { mutableStateOf("") }
    var trayendoIconos by remember { mutableStateOf(false) }
    var fechaAnterior by remember { mutableStateOf(ConfigStore.previousDate(context)) }
    var restableciendo by remember { mutableStateOf(false) }

    val alcance = rememberCoroutineScope()

    /**
     * Aplica lo importado. Antes guarda la configuración actual, para poder deshacer, y
     * si el usuario ha dicho de dónde viene el panel, trae también sus iconos.
     */
    fun aplicarImportacion(leido: PanelImport.Result, reemplazar: Boolean) {
        val base = direccionOrigen.trim()

        alcance.launch {
            ConfigStore.keepPrevious(context, config)
            fechaAnterior = ConfigStore.previousDate(context)

            var nueva = if (reemplazar) reemplazar(config, leido) else fusionar(config, leido)

            if (base.isNotBlank()) {
                trayendoIconos = true
                nueva = PanelImport.withOriginIcons(context, nueva, leido, base)
                trayendoIconos = false
            }

            onConfigChange(nueva)
            panelPendiente = null
            direccionOrigen = ""
            avisar(
                context.resources.getQuantityString(
                    R.plurals.import_done,
                    leido.serviceCount,
                    leido.serviceCount
                )
            )
        }
    }

    fun leer(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
    }.getOrNull()

    val exportar = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { salida ->
                salida.write(ConfigStore.export(config).toByteArray())
            }
        }.isSuccess

        avisar(
            context.getString(
                if (ok) R.string.backup_exported else R.string.backup_export_failed
            )
        )
    }

    /**
     * Guarda la configuración en un fichero y, solo si se ha guardado, restablece.
     *
     * Si el usuario cancela el selector, no se toca nada: cancelar el paso de guardar es
     * justo la señal de que se lo está pensando.
     */
    val guardarAntesDeReset = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) {
            avisar(context.getString(R.string.reset_cancelled))
            return@rememberLauncherForActivityResult
        }

        val guardado = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { salida ->
                salida.write(ConfigStore.export(config).toByteArray())
            }
        }.isSuccess

        if (!guardado) {
            avisar(context.getString(R.string.reset_save_failed))
            return@rememberLauncherForActivityResult
        }

        // La actual pasa además a ser la anterior, para poder deshacer desde la propia
        // aplicación sin buscar el fichero.
        ConfigStore.keepPrevious(context, config)

        // `tutorialSeen` vuelve a falso: lo que se pide es quedarse como recién instalada,
        // y eso incluye la presentación.
        ConfigStore.save(context, DefaultConfig.create(context))

        // Y se relanza de cero. Sin esto la pantalla seguiría viva con los datos viejos en
        // memoria y el tutorial no volvería a salir, porque se decide al arrancar.
        context.startActivity(
            android.content.Intent(context, MainActivity::class.java).addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        (context as? android.app.Activity)?.finish()
    }

    val restaurar = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        val copia = leer(uri)?.let { ConfigStore.import(it) }
        if (copia == null) {
            avisar(context.getString(R.string.backup_import_failed))
        } else {
            copiaPendiente = copia
        }
    }

    val importarPanel = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        val leido = leer(uri)?.let { PanelImport.parse(it) }
        if (leido == null || leido.serviceCount == 0) {
            avisar(context.getString(R.string.import_nothing_found))
        } else {
            panelPendiente = leido
        }
    }

    // Segundo panel, el de las direcciones de fuera de casa.
    val importarFuera = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        val leido = leer(uri)?.let { PanelImport.parse(it) }

        if (leido == null || leido.serviceCount == 0) {
            avisar(context.getString(R.string.import_nothing_found))
            return@rememberLauncherForActivityResult
        }

        val (nueva, cuenta) = PanelImport.applyAwayAddresses(config, leido)

        if (cuenta == 0 && nueva == config) {
            avisar(context.getString(R.string.import_away_none))
        } else {
            ConfigStore.keepPrevious(context, config)
            fechaAnterior = ConfigStore.previousDate(context)
            // Con dos direcciones ya hay algo que decidir: el perfil automático las usa.
            onConfigChange(nueva.copy(profile = NetworkProfile.AUTO.name))
            avisar(
                context.resources.getQuantityString(
                    R.plurals.import_away_done,
                    cuenta,
                    cuenta
                )
            )
        }
    }

    Column(Modifier.padding(top = 6.dp, bottom = 24.dp)) {

        // Las dos primeras filas van sin título de sección: la barra ya dice «Copia de
        // seguridad» y repetirlo justo debajo no añade nada.
        ClickableRow(
            title = stringResource(R.string.backup_export),
            subtitle = stringResource(R.string.backup_export_sub),
            onClick = { exportar.launch(nombreDeCopia()) }
        )
        ClickableRow(
            title = stringResource(R.string.backup_import),
            subtitle = stringResource(R.string.backup_import_sub),
            onClick = { restaurar.launch(TIPOS_DE_COPIA) }
        )

        TituloDeSeccion(
            stringResource(R.string.backup_import_panel),
            Modifier.padding(horizontal = 18.dp)
        )
        ClickableRow(
            title = stringResource(R.string.import_choose_file),
            subtitle = stringResource(R.string.backup_import_panel_sub),
            onClick = { importarPanel.launch(TIPOS_DE_PANEL) }
        )

        // Solo tiene sentido cuando ya hay servidores a los que asignar la dirección.
        if (config.servers.isNotEmpty()) {
            TituloDeSeccion(
                stringResource(R.string.import_away_title),
                Modifier.padding(horizontal = 18.dp)
            )
            Text(
                stringResource(R.string.import_away_help),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(6.dp))
            ClickableRow(
                title = stringResource(R.string.import_away_choose),
                onClick = { importarFuera.launch(TIPOS_DE_PANEL) }
            )
        }

        fechaAnterior?.let { cuando ->
            TituloDeSeccion(
                stringResource(R.string.undo_title),
                Modifier.padding(horizontal = 18.dp)
            )
            ClickableRow(
                title = stringResource(R.string.undo_restore),
                // Sin la fecha, «volver a la anterior» obliga a probar para saber qué
                // se recupera.
                subtitle = stringResource(R.string.undo_saved_at, fechaLegible(cuando)),
                onClick = {
                    val anterior = ConfigStore.previous(context)
                    if (anterior == null) {
                        avisar(context.getString(R.string.undo_failed))
                    } else {
                        // La actual pasa a ser la anterior: así se puede ir y volver.
                        ConfigStore.keepPrevious(context, config)
                        fechaAnterior = ConfigStore.previousDate(context)
                        onConfigChange(anterior)
                        avisar(context.getString(R.string.undo_done))
                    }
                }
            )
        }

        // Volver al estado de recién instalada. Va al final y en rojo porque se lleva por
        // delante todo lo que el usuario haya montado.
        TituloDeSeccion(
            stringResource(R.string.reset_title),
            Modifier.padding(horizontal = 18.dp)
        )
        Text(
            stringResource(R.string.reset_help),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BotonDeBorrado(stringResource(R.string.reset_button)) { restableciendo = true }
        }

        if (trayendoIconos) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.import_icons_downloading),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }
    }

    if (restableciendo) {
        AlertDialog(
            onDismissRequest = { restableciendo = false },
            title = { Text(stringResource(R.string.reset_ask_title)) },
            text = { Text(stringResource(R.string.reset_ask), fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    restableciendo = false
                    // Primero el fichero, y el restablecimiento solo si se guarda. La copia
                    // interna existe, pero es invisible: quien va a borrar veinte servicios
                    // quiere ver el fichero con sus datos antes de seguir.
                    guardarAntesDeReset.launch(nombreDeCopia())
                }) {
                    Text(stringResource(R.string.reset_save_and_do))
                }
            },
            dismissButton = {
                TextButton(onClick = { restableciendo = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    copiaPendiente?.let { copia ->
        AlertDialog(
            onDismissRequest = { copiaPendiente = null },
            title = { Text(stringResource(R.string.backup_import)) },
            text = {
                Text(
                    stringResource(
                        R.string.import_restore_warning,
                        pluralStringResource(
                            R.plurals.count_services,
                            copia.allServices.size,
                            copia.allServices.size
                        ),
                        pluralStringResource(
                            R.plurals.count_servers,
                            copia.servers.size,
                            copia.servers.size
                        )
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ConfigStore.keepPrevious(context, config)
                    fechaAnterior = ConfigStore.previousDate(context)
                    onConfigChange(copia)
                    copiaPendiente = null
                    avisar(context.getString(R.string.backup_imported))
                }) { Text(stringResource(R.string.import_replace)) }
            },
            dismissButton = {
                TextButton(onClick = { copiaPendiente = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    panelPendiente?.let { leido ->
        AlertDialog(
            onDismissRequest = { panelPendiente = null },
            title = { Text(stringResource(R.string.import_found_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.import_found,
                            pluralStringResource(
                                R.plurals.count_services,
                                leido.serviceCount,
                                leido.serviceCount
                            ),
                            pluralStringResource(
                                R.plurals.count_groups,
                                leido.groups.size,
                                leido.groups.size
                            )
                        )
                    )
                    if (leido.servers.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            pluralStringResource(
                                R.plurals.import_found_servers,
                                leido.servers.size,
                                leido.servers.size
                            ),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Los iconos del fichero son rutas dentro del panel de origen, así que
                    // solo se pueden traer sabiendo en qué dirección está ese panel.
                    if (leido.hasRelativeLogos) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.import_icons_help),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = direccionOrigen,
                            onValueChange = { direccionOrigen = it },
                            label = { Text(stringResource(R.string.import_icons_address)) },
                            placeholder = { Text("http://192.168.1.254:8090") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.import_undo_note),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !trayendoIconos,
                    onClick = { aplicarImportacion(leido, reemplazar = false) }
                ) { Text(stringResource(R.string.import_add)) }
            },
            dismissButton = {
                TextButton(
                    enabled = !trayendoIconos,
                    onClick = { aplicarImportacion(leido, reemplazar = true) }
                ) { Text(stringResource(R.string.import_replace)) }
            }
        )
    }
}


/**
 * Tipos que acepta cada selector de fichero.
 *
 * Llevan el comodín al final porque muchos gestores de archivos y las nubes declaran los
 * `.yml` y hasta los `.json` como tipo desconocido, y sin él aparecen en gris.
 */
private val TIPOS_DE_COPIA = arrayOf("application/json", "text/plain", "*/*")
private val TIPOS_DE_PANEL =
    arrayOf("application/json", "text/yaml", "application/x-yaml", "text/plain", "*/*")

/** Nombre de fichero con la fecha, para no acabar con diez «panel-config.json». */
private fun nombreDeCopia(): String {
    val fecha = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .format(java.util.Date())
    return "panel-$fecha.json"
}

/** Fecha y hora en el formato del móvil, no en uno inventado. */
private fun fechaLegible(instante: Long): String =
    java.text.DateFormat
        .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(instante))

/** Añade lo importado a lo que ya hay, sin tocar nada de lo existente. */
private fun fusionar(actual: PanelConfig, leido: PanelImport.Result): PanelConfig {
    // Los servidores que ya existan con la misma dirección se reutilizan, y los servicios
    // que apuntaban al importado se reapuntan al que ya estaba.
    val equivalencias = mutableMapOf<String, String>()
    val servidoresNuevos = mutableListOf<Server>()

    leido.servers.forEach { importado ->
        val existente = actual.servers.firstOrNull { it.hostHome == importado.hostHome }
        if (existente != null) {
            equivalencias[importado.id] = existente.id
        } else {
            servidoresNuevos += importado
        }
    }

    val grupos = leido.groups.map { grupo ->
        grupo.copy(
            services = grupo.services.map { servicio ->
                val destino = equivalencias[servicio.serverId] ?: servicio.serverId
                servicio.copy(serverId = destino)
            }
        )
    }

    return actual.copy(
        servers = actual.servers + servidoresNuevos,
        groups = actual.groups + grupos
    )
}

/**
 * Deja solo los servicios importados, conservando el resto de los ajustes.
 *
 * Lo delicado son los destinos de descarga: se conservan, pero cuelgan de servidores que
 * al reemplazar dejan de existir. Se reapuntan al servidor importado que tenga la misma
 * dirección y, si no hay ninguno, se les fija la dirección completa que tenían. Sin esto
 * la configuración de descargas sobrevivía al fichero pero se quedaba sin dirección, que
 * es como perderla.
 */
private fun reemplazar(actual: PanelConfig, leido: PanelImport.Result): PanelConfig {
    val destinos = actual.downloadTargets.map { destino ->
        val servidorViejo = actual.server(destino.serverId) ?: return@map destino

        val equivalente = leido.servers.firstOrNull {
            it.hostHome.equals(servidorViejo.hostHome, ignoreCase = true)
        }

        if (equivalente != null) {
            destino.copy(serverId = equivalente.id)
        } else {
            destino.copy(
                serverId = "",
                urlOwn = actual.urlOf(destino, away = false),
                urlOwnAway = if (servidorViejo.hostAway.isNotBlank()) {
                    actual.urlOf(destino, away = true)
                } else {
                    destino.urlOwnAway
                }
            )
        }
    }

    return actual.copy(
        title = leido.title.ifBlank { actual.title },
        subtitle = leido.subtitle.ifBlank { actual.subtitle },
        servers = leido.servers,
        groups = leido.groups,
        downloadTargets = destinos
    )
}

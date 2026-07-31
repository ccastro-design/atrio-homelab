package com.homelab.panel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Esta pantalla es solo el armazón de los ajustes: la barra, el menú y a qué página se
// va. Cada página vive en su propio fichero —`SettingsAppearance`, `SettingsSecurity`,
// `SettingsBackup`, `SettingsAbout`— porque juntas pasaban de dos mil líneas y encontrar
// algo dentro era imposible.

/** Pantallas dentro de Ajustes. */
private enum class SettingsPage {
    MENU, SERVICES, SERVERS, APPEARANCE, SECURITY, BACKUP, SUPPORT, ABOUT
}

@Composable
fun SettingsScreen(
    config: PanelConfig,
    onConfigChange: (PanelConfig) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenScan: () -> Unit,
    onShowTutorial: () -> Unit,
    /** Entrar directamente en Seguridad, para el «Llévame allí» de la presentación. */
    empezarEnSeguridad: Boolean = false,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    var pagina by remember {
        mutableStateOf(if (empezarEnSeguridad) SettingsPage.SECURITY else SettingsPage.MENU)
    }

    // Edición en curso: un servicio o un servidor.
    var servicioEnEdicion by remember { mutableStateOf<Pair<Service, String>?>(null) }
    var servidorEnEdicion by remember { mutableStateOf<Server?>(null) }
    /** Borrado de servidor pendiente de confirmar, porque tiene servicios colgando. */
    var confirmarBorradoDeServidor by remember { mutableStateOf(false) }
    // El alta de grupo vive aquí porque se lanza desde el botón de la barra.
    var creandoGrupo by remember { mutableStateOf(false) }

    servicioEnEdicion?.let { (servicio, grupoId) ->
        ServiceEditor(
            service = servicio,
            config = config,
            groupId = grupoId,
            onSave = { nuevo, grupoDestino ->
                onConfigChange(ConfigOps.saveService(config, nuevo, grupoDestino))
                servicioEnEdicion = null
            },
            onDelete = if (config.allServices.any { it.id == servicio.id }) {
                {
                    // Igual que en la ficha del panel: las credenciales y la imagen no
                    // viven en la configuración y no se van con el servicio.
                    AutoLogin.forget(context, servicio.id)
                    IconStore.deleteServiceIcon(context, config, servicio)
                    onConfigChange(ConfigOps.deleteService(config, servicio.id))
                    servicioEnEdicion = null
                }
            } else {
                null
            },
            onCancel = { servicioEnEdicion = null }
        )
        return
    }

    servidorEnEdicion?.let { servidor ->
        ServerEditor(
            server = servidor,
            onSave = { nuevo ->
                onConfigChange(guardarServidor(config, nuevo))
                servidorEnEdicion = null
            },
            onDelete = if (config.servers.any { it.id == servidor.id }) {
                {
                    // Borrarlo deja sin dirección a todos sus servicios, que se quedan en
                    // el panel apuntando a un servidor que ya no existe. Si los tiene, se
                    // avisa antes; si no cuelga nada de él, no hay nada que advertir.
                    if (config.allServices.any { it.serverId == servidor.id }) {
                        confirmarBorradoDeServidor = true
                    } else {
                        onConfigChange(
                            config.copy(servers = config.servers.filter { it.id != servidor.id })
                        )
                        servidorEnEdicion = null
                    }
                }
            } else {
                null
            },
            onCancel = { servidorEnEdicion = null }
        )

        if (confirmarBorradoDeServidor) {
            val afectados = config.allServices.count { it.serverId == servidor.id }

            AlertDialog(
                onDismissRequest = { confirmarBorradoDeServidor = false },
                title = { Text(stringResource(R.string.server_delete_title, servidor.name)) },
                text = {
                    Text(
                        pluralStringResource(
                            R.plurals.server_delete_warning,
                            afectados,
                            afectados
                        ),
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onConfigChange(
                            config.copy(servers = config.servers.filter { it.id != servidor.id })
                        )
                        confirmarBorradoDeServidor = false
                        servidorEnEdicion = null
                    }) { Text(stringResource(R.string.server_delete_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmarBorradoDeServidor = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
        return
    }

    // El botón atrás del sistema navega dentro de la aplicación: sube a la lista de
    // ajustes y, desde ahí, vuelve al panel. Sin esto cerraba la aplicación entera.
    BackHandler {
        if (pagina == SettingsPage.MENU) onClose() else pagina = SettingsPage.MENU
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        BarraDeAjustes(
            titulo = when (pagina) {
                SettingsPage.MENU -> stringResource(R.string.settings)
                SettingsPage.SERVICES -> stringResource(R.string.settings_services)
                SettingsPage.SERVERS -> stringResource(R.string.settings_servers)
                SettingsPage.APPEARANCE -> stringResource(R.string.settings_appearance)
                SettingsPage.SECURITY -> stringResource(R.string.settings_security)
                SettingsPage.BACKUP -> stringResource(R.string.settings_backup)
                SettingsPage.SUPPORT -> stringResource(R.string.settings_support)
                SettingsPage.ABOUT -> stringResource(R.string.settings_about)
            },
            // Con una sola opción el botón la ejecuta; con varias, despliega el menú.
            acciones = when (pagina) {
                SettingsPage.SERVICES -> listOf(
                    stringResource(R.string.group_new) to { creandoGrupo = true },
                    stringResource(R.string.service_new) to {
                        servicioEnEdicion = Service() to config.groups.firstOrNull()?.id.orEmpty()
                    },
                    stringResource(R.string.welcome_scan) to onOpenScan
                )

                SettingsPage.SERVERS -> listOf(
                    stringResource(R.string.server_new) to { servidorEnEdicion = Server() }
                )

                else -> emptyList()
            },
            onAtras = {
                if (pagina == SettingsPage.MENU) onClose() else pagina = SettingsPage.MENU
            }
        )

        Box(Modifier.weight(1f)) {
            // La lista de servicios trae su propio desplazamiento, porque es una lista
            // perezosa: meterla dentro de otra que se desplaza revienta la medida.
            if (pagina == SettingsPage.SERVICES) {
                ServicesPage(
                    config = config,
                    onConfigChange = onConfigChange,
                    onEdit = { servicio, grupoId -> servicioEnEdicion = servicio to grupoId },
                    onAddIn = { grupoId -> servicioEnEdicion = Service() to grupoId }
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when (pagina) {
                        SettingsPage.MENU -> Menu(
                            onNavigate = { pagina = it },
                            onOpenDownloads = onOpenDownloads,
                            onShowTutorial = onShowTutorial
                        )

                        SettingsPage.SERVERS -> PaginaDeServidores(
                            config = config,
                            onEdit = { servidorEnEdicion = it }
                        )

                        SettingsPage.APPEARANCE -> PaginaDeApariencia(config, onConfigChange)
                        SettingsPage.SECURITY -> PaginaDeSeguridad(config, onConfigChange)
                        SettingsPage.BACKUP -> PaginaDeCopia(config, onConfigChange)
                        SettingsPage.SUPPORT -> PaginaDeApoyo()
                        SettingsPage.ABOUT -> PaginaAcercaDe()
                        SettingsPage.SERVICES -> Unit
                    }
                }
            }
        }
    }

    if (creandoGrupo) {
        // La misma ficha que al editar: si el color se puede elegir después, no hay
        // motivo para no elegirlo al crearlo.
        EditorDeGrupo(
            grupo = ServiceGroup(),
            titulo = stringResource(R.string.group_new),
            onCancel = { creandoGrupo = false },
            onAccept = { nombre, color ->
                onConfigChange(ConfigOps.addGroup(config, nombre, color))
                creandoGrupo = false
            }
        )
    }
}

@Composable
private fun BarraDeAjustes(
    titulo: String,
    acciones: List<Pair<String, () -> Unit>>,
    onAtras: () -> Unit
) {
    var menuAbierto by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onAtras) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.back),
                tint = Color.White
            )
        }
        Text(titulo, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))

        if (acciones.isNotEmpty()) {
            Box {
                IconButton(
                    onClick = {
                        if (acciones.size == 1) acciones.first().second() else menuAbierto = true
                    }
                ) {
                    Icon(Icons.Default.Add, stringResource(R.string.add), tint = Color.White)
                }

                DropdownMenu(expanded = menuAbierto, onDismissRequest = { menuAbierto = false }) {
                    acciones.forEach { (texto, accion) ->
                        DropdownMenuItem(
                            // El mismo azul de los nombres de grupo, para que el menú se
                            // vea como parte del panel y no como un menú del sistema.
                            text = { Text(texto, color = MaterialTheme.colorScheme.primary) },
                            onClick = { menuAbierto = false; accion() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Menu(
    onNavigate: (SettingsPage) -> Unit,
    onOpenDownloads: () -> Unit,
    onShowTutorial: () -> Unit
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        FilaDeMenu(Icons.Default.Widgets, stringResource(R.string.settings_services)) {
            onNavigate(SettingsPage.SERVICES)
        }
        FilaDeMenu(Icons.Default.Dns, stringResource(R.string.settings_servers)) {
            onNavigate(SettingsPage.SERVERS)
        }
        FilaDeMenu(Icons.Default.Download, stringResource(R.string.settings_downloads)) {
            onOpenDownloads()
        }
        FilaDeMenu(Icons.Default.Palette, stringResource(R.string.settings_appearance)) {
            onNavigate(SettingsPage.APPEARANCE)
        }
        FilaDeMenu(Icons.Default.Security, stringResource(R.string.settings_security)) {
            onNavigate(SettingsPage.SECURITY)
        }
        FilaDeMenu(Icons.Default.Backup, stringResource(R.string.settings_backup)) {
            onNavigate(SettingsPage.BACKUP)
        }
        FilaDeMenu(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.settings_tour)) {
            onShowTutorial()
        }
        // Sin ninguna dirección puesta no hay nada que enseñar, y una pantalla vacía es
        // peor que no tener la fila.
        if (Proyecto.hayApoyo) {
            FilaDeMenu(Icons.Default.VolunteerActivism, stringResource(R.string.settings_support)) {
                onNavigate(SettingsPage.SUPPORT)
            }
        }
        FilaDeMenu(Icons.Default.Info, stringResource(R.string.settings_about)) {
            onNavigate(SettingsPage.ABOUT)
        }
    }
}

@Composable
private fun FilaDeMenu(icono: ImageVector, titulo: String, onClick: () -> Unit) {
    ClickableRow(
        title = titulo,
        leading = {
            Icon(
                icono,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(23.dp)
            )
        },
        onClick = onClick
    )
}


@Composable
private fun PaginaDeServidores(config: PanelConfig, onEdit: (Server) -> Unit) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Text(
            stringResource(R.string.server_help),
            // El mismo tamaño que en la ficha del servidor: es el mismo texto.
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
        )

        if (config.servers.isEmpty()) {
            Text(
                stringResource(R.string.servers_empty),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 18.dp)
            )
        }

        config.servers.forEach { servidor ->
            val enUso = config.allServices.count { it.serverId == servidor.id }

            ClickableRow(
                title = servidor.name,
                monospaceLine = buildString {
                    append(servidor.hostHome)
                    if (servidor.hostAway.isNotBlank()) append("  ·  ${servidor.hostAway}")
                },
                subtitle = pluralStringResource(R.plurals.server_services_count, enUso, enUso),
                onClick = { onEdit(servidor) }
            )
        }
    }
}

// ---- Operaciones sobre la configuración ----

private fun guardarServidor(config: PanelConfig, servidor: Server): PanelConfig {
    val existe = config.servers.any { it.id == servidor.id }

    return config.copy(
        servers = if (existe) {
            config.servers.map { if (it.id == servidor.id) servidor else it }
        } else {
            config.servers + servidor
        }
    )
}

package com.homelab.panel

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ficha de un servicio: alta y edición.
 *
 * Toda la configuración se hace desde aquí; el usuario nunca tiene que tocar un fichero.
 */
@Composable
fun ServiceEditor(
    service: Service,
    config: PanelConfig,
    groupId: String,
    onSave: (Service, String) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    var nombre by remember { mutableStateOf(service.name) }
    var subtitulo by remember { mutableStateOf(service.subtitle) }

    // Una sola forma de escribir la dirección: la completa. Elegir una máquina guardada
    // solo rellena estos campos; no cambia el formulario ni esconde nada.
    var urlLocal by remember { mutableStateOf(direccionLocalDe(config, service)) }
    var urlFuera by remember { mutableStateOf(direccionDeFueraDe(config, service)) }
    var categoria by remember { mutableStateOf(service.category) }
    var ficheroIcono by remember { mutableStateOf(service.iconFile) }
    var usarFavicon by remember { mutableStateOf(service.useFavicon) }
    var abrirFuera by remember { mutableStateOf(service.openExternal) }
    var aplicacionExterna by remember { mutableStateOf(service.externalPackage) }
    var modoEscritorio by remember { mutableStateOf(service.desktopMode) }
    var comprobar by remember { mutableStateOf(service.checkStatus) }
    var avisarSinCifrar by remember { mutableStateOf(service.warnCleartext) }
    var grupo by remember { mutableStateOf(groupId) }

    var autoLogin by remember { mutableStateOf(service.autoLogin) }
    var usuario by remember { mutableStateOf(AutoLogin.savedUser(context, service.id)) }
    var contrasena by remember { mutableStateOf("") }
    var marcadorVisible by remember { mutableStateOf(true) }
    val hayContrasenaGuardada = remember {
        SecureStore.has(context, SecureStore.servicePasswordKey(service.id))
    }

    /**
     * Todo lo que el usuario puede tocar en la ficha, junto, para saber si ha cambiado algo.
     *
     * Se compara con cómo estaba al abrirla. Antes, salir sin guardar —con «Cancelar» o con
     * el botón atrás— se llevaba por delante el trabajo sin decir ni pío, y en una ficha
     * larga como esta se pierde un buen rato.
     */
    fun estadoDeLaFicha(): List<Any?> = listOf(
        nombre, subtitulo, urlLocal, urlFuera, categoria, ficheroIcono, usarFavicon,
        abrirFuera, aplicacionExterna, modoEscritorio, comprobar, avisarSinCifrar, grupo,
        autoLogin, usuario, contrasena, marcadorVisible
    )

    val estadoInicial = remember { estadoDeLaFicha() }
    val hayCambios = estadoDeLaFicha() != estadoInicial

    var confirmandoSalida by remember { mutableStateOf(false) }

    /** Sin tocar nada se sale sin preguntar: avisar de lo que no ha pasado sobra. */
    fun salir() {
        if (hayCambios) confirmandoSalida = true else onCancel()
    }

    // El botón atrás cancela la edición en vez de cerrar la aplicación.
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

    var eligiendoCategoria by remember { mutableStateOf(false) }
    var eligiendoAplicacion by remember { mutableStateOf(false) }
    var menuDeIcono by remember { mutableStateOf(false) }
    var eligiendoPropio by remember { mutableStateOf(false) }

    val elegirImagen = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val id = service.id.ifBlank { "nuevo" }
            IconStore.saveUserIcon(context, uri, id)?.let { guardado ->
                ficheroIcono = guardado
                // Se apaga el otro interruptor, que a partir de ahora estaría diciendo
                // que se usa el icono del servicio mientras se ve la imagen elegida.
                usarFavicon = false
            }
        }
    }

    // Máquina guardada a la que corresponde la dirección escrita, si es alguna.
    val servidorDetectado = remember(urlLocal, urlFuera, config.servers) {
        servidorPara(config, urlLocal, urlFuera)
    }

    fun construir(): Service {
        val base = service.copy(
            id = service.id.ifBlank { "svc-${System.currentTimeMillis()}" },
            name = nombre.trim(),
            subtitle = subtitulo.trim(),
            category = categoria,
            iconFile = ficheroIcono,
            useFavicon = usarFavicon,
            openExternal = abrirFuera,
            externalPackage = aplicacionExterna,
            desktopMode = modoEscritorio,
            autoLogin = autoLogin,
            checkStatus = comprobar,
            warnCleartext = avisarSinCifrar,
            // Al guardar deja de ser un ejemplo: pasa a ser un servicio del usuario.
            isExample = false
        )

        return conDireccion(base, urlLocal.trim(), urlFuera.trim(), servidorDetectado)
    }

    val valido = nombre.isNotBlank() && urlLocal.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Sitio para el teclado: sin esto tapa los últimos campos de la ficha.
            .imePadding()
    ) {
        BarraDeEdicion(
            titulo = if (onDelete == null) {
                stringResource(R.string.service_new)
            } else {
                stringResource(R.string.service_edit)
            },
            guardarActivado = valido,
            onGuardar = {
                val nuevo = construir()

                // La imagen que se deja de usar se borra aquí y no al quitarla de la
                // ficha: cancelar la edición tiene que dejarlo todo como estaba, y
                // borrándola en el acto quien se arrepentía se quedaba sin ella.
                if (service.iconFile.isNotBlank() && service.iconFile != nuevo.iconFile) {
                    IconStore.deleteUserIcon(context, service.iconFile)
                }

                // Se conserva la contraseña guardada solo si el usuario no ha tocado el
                // campo: si lo vacía a propósito, es que quiere quitarla. Antes se
                // conservaba igualmente y no había forma de borrarla desde la ficha.
                val intacta = marcadorVisible && hayContrasenaGuardada

                if (autoLogin && (contrasena.isNotEmpty() || intacta)) {
                    AutoLogin.save(context, nuevo.id, usuario.trim(), contrasena)
                } else {
                    AutoLogin.forget(context, nuevo.id)
                }
                onSave(nuevo, grupo)
            },
            onCancelar = { salir() },
            onBorrar = onDelete
        )

        // Cada sección dentro de su recuadro y el título fuera, como en las pantallas de
        // Ajustes. El margen es de 14 para que las burbujas queden alineadas con las filas
        // del resto de la aplicación, y los títulos se meten 4 más.
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
        ) {
            Spacer(Modifier.height(10.dp))

            // Las dos primeras van sin título: la barra ya dice si se está creando o
            // editando un servicio, y «Nombre» encima de un campo que se llama «Nombre»
            // sobra.
            Burbuja {
                Campo(nombre, { nombre = it }, stringResource(R.string.field_name))
                Campo(subtitulo, { subtitulo = it }, stringResource(R.string.field_subtitle))
            }

            Apartado(stringResource(R.string.section_address), Modifier.padding(start = 4.dp))

            Burbuja {
            Campo(urlLocal, { urlLocal = it }, stringResource(R.string.field_url))
            Campo(
                urlFuera,
                { urlFuera = it },
                stringResource(R.string.field_url_away),
                ayuda = stringResource(R.string.field_url_away_help)
            )

            // Atajo para no teclear la dirección de una máquina que ya está guardada.
            if (config.servers.isNotEmpty()) {
                RellenarDesdeServidor(
                    servers = config.servers,
                    onPick = { servidor ->
                        urlLocal = conHost(urlLocal, servidor.hostHome)
                        if (servidor.hostAway.isNotBlank()) {
                            urlFuera = conHost(urlFuera.ifBlank { urlLocal }, servidor.hostAway)
                        }
                    }
                )
            }

            servidorDetectado?.let { servidor ->
                Text(
                    stringResource(R.string.address_from_server, servidor.name),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            }

            Apartado(stringResource(R.string.section_icon), Modifier.padding(start = 4.dp))

            Burbuja {
            // Un solo botón que despliega de dónde sale el icono, igual que el logotipo del
            // panel en Apariencia. Antes eran dos botones y un interruptor para lo mismo, y
            // las combinaciones se pisaban entre ellas: la imagen tapaba al interruptor, el
            // interruptor tapaba a la lista, y ninguno decía cuál estaba mandando. Aquí solo
            // hay una respuesta posible a la vez.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceIcon(service = construir(), url = "", size = 52.dp)
                Spacer(Modifier.width(14.dp))

                Column {
                    Text(
                        stringResource(
                            when {
                                ficheroIcono.isNotBlank() -> R.string.icon_choose_image
                                usarFavicon -> R.string.icon_from_service
                                OwnIcons.isOwn(categoria) -> R.string.icon_choose_own
                                categoria != "generic" -> R.string.icon_choose_category
                                else -> R.string.icon_default
                            }
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 12.dp)
                    )

                    Box {
                        TextButton(onClick = { menuDeIcono = true }) {
                            Text(stringResource(R.string.icon_change))
                        }

                        // El mismo orden y los mismos nombres que el menú del logotipo del
                        // panel: es la misma pregunta hecha en otra pantalla.
                        DropdownMenu(
                            expanded = menuDeIcono,
                            onDismissRequest = { menuDeIcono = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.icon_default)) },
                                onClick = {
                                    menuDeIcono = false
                                    usarFavicon = false
                                    ficheroIcono = ""
                                    categoria = "generic"
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.icon_choose_category)) },
                                onClick = { menuDeIcono = false; eligiendoCategoria = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.icon_choose_image)) },
                                onClick = { menuDeIcono = false; elegirImagen.launch("image/*") }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.icon_from_service)) },
                                onClick = {
                                    menuDeIcono = false
                                    usarFavicon = true
                                    ficheroIcono = ""
                                    categoria = "generic"
                                }
                            )
                            if (OwnIcons.hayAlguno) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.icon_choose_own)) },
                                    onClick = { menuDeIcono = false; eligiendoPropio = true }
                                )
                            }
                        }
                    }
                }
            }

            }

            Apartado(stringResource(R.string.section_behaviour), Modifier.padding(start = 4.dp))

            Burbuja {
            Interruptor(
                abrirFuera,
                { abrirFuera = it },
                stringResource(R.string.open_external),
                stringResource(R.string.open_external_help)
            )

            // La aplicación se elige a mano: ver [ExternalApps] para por qué Android no
            // puede adivinarla a partir de la dirección.
            if (abrirFuera) {
                SelectorDeAplicacion(
                    paquete = aplicacionExterna,
                    onClick = { eligiendoAplicacion = true }
                )
            }

            Interruptor(
                modoEscritorio,
                { modoEscritorio = it },
                stringResource(R.string.desktop_mode),
                stringResource(R.string.desktop_mode_help)
            )
            Interruptor(
                comprobar,
                { comprobar = it },
                stringResource(R.string.check_status),
                // Si la comprobación está apagada para toda la aplicación, este
                // interruptor no hace nada aunque esté encendido. Sin decirlo, la ficha
                // promete algo que no va a pasar.
                if (config.checkStatus) {
                    pluralStringResource(
                        R.plurals.check_status_help,
                        STATUS_REFRESH_SECONDS,
                        STATUS_REFRESH_SECONDS
                    )
                } else {
                    stringResource(R.string.check_status_disabled)
                }
            )
            Interruptor(
                avisarSinCifrar,
                { avisarSinCifrar = it },
                stringResource(R.string.warn_cleartext),
                if (config.warnCleartext) {
                    stringResource(R.string.warn_cleartext_help)
                } else {
                    stringResource(R.string.warn_cleartext_disabled)
                }
            )

            }

            Apartado(stringResource(R.string.section_autologin), Modifier.padding(start = 4.dp))

            Burbuja {
            Interruptor(
                autoLogin,
                { activo ->
                    autoLogin = activo
                    // Apagarlo vacía lo que hubiera escrito: al volver a encenderlo se
                    // parte de cero, en vez de seguir arrastrando unas credenciales que
                    // el usuario creía haber quitado.
                    if (!activo) {
                        usuario = ""
                        contrasena = ""
                        marcadorVisible = false
                    }
                },
                stringResource(R.string.autologin)
            )

            if (autoLogin) {
                Campo(usuario, { usuario = it }, stringResource(R.string.field_user_optional))
                PasswordField(
                    value = contrasena,
                    onValueChange = { contrasena = it },
                    hasSaved = hayContrasenaGuardada,
                    showingSavedMarker = marcadorVisible,
                    onMarkerCleared = { marcadorVisible = false },
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (eligiendoCategoria) {
        SelectorDeCategoria(
            actual = categoria,
            onCancel = { eligiendoCategoria = false },
            onPick = { elegido ->
                categoria = elegido
                // Elegir un icono a mano es decir «quiero este»: se retira lo que lo
                // taparía, que es la imagen propia y el icono que sirva el servicio.
                ficheroIcono = ""
                usarFavicon = false
                eligiendoCategoria = false
            }
        )
    }

    if (eligiendoPropio) {
        SelectorDeIconoPropio(
            actual = categoria,
            onCancel = { eligiendoPropio = false },
            onPick = { elegido ->
                categoria = OwnIcons.value(elegido)
                ficheroIcono = ""
                usarFavicon = false
                eligiendoPropio = false
            }
        )
    }

    if (eligiendoAplicacion) {
        SelectorDeAplicaciones(
            actual = aplicacionExterna,
            sugerencia = nombre,
            onCancel = { eligiendoAplicacion = false },
            onPick = { aplicacionExterna = it; eligiendoAplicacion = false }
        )
    }
}

/** Ficha de un servidor: la dirección que comparten sus servicios. */
@Composable
fun ServerEditor(
    server: Server,
    onSave: (Server) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit
) {
    var nombre by remember { mutableStateOf(server.name) }
    var casa by remember { mutableStateOf(server.hostHome) }
    var fuera by remember { mutableStateOf(server.hostAway) }

    // Lo mismo que en la ficha del servicio: salir sin guardar no puede llevarse lo escrito
    // sin decir nada.
    val estadoInicial = remember { listOf(server.name, server.hostHome, server.hostAway) }
    val hayCambios = listOf(nombre, casa, fuera) != estadoInicial

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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        BarraDeEdicion(
            titulo = if (onDelete == null) {
                stringResource(R.string.server_new)
            } else {
                stringResource(R.string.server_edit)
            },
            guardarActivado = nombre.isNotBlank() && casa.isNotBlank(),
            onGuardar = {
                onSave(
                    server.copy(
                        id = server.id.ifBlank { "srv-${System.currentTimeMillis()}" },
                        name = nombre.trim(),
                        hostHome = limpiarHost(casa),
                        hostAway = limpiarHost(fuera)
                    )
                )
            },
            onCancelar = { salir() },
            onBorrar = onDelete
        )

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.server_help),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(14.dp))

            Campo(nombre, { nombre = it }, stringResource(R.string.field_server_name))
            Campo(
                casa,
                { casa = it },
                stringResource(R.string.field_host_home),
                ayuda = stringResource(R.string.field_host_home_help)
            )
            Campo(
                fuera,
                { fuera = it },
                stringResource(R.string.field_host_away),
                ayuda = stringResource(R.string.field_host_away_help)
            )
        }
    }
}

// ---- Direcciones del servicio ----
//
// El usuario escribe siempre direcciones completas. Que un servicio quede colgado de una
// máquina guardada se deduce de lo escrito, no de un modo aparte del formulario: si la
// dirección local es la de una máquina, se vincula, y así el día que cambie la dirección
// de esa máquina el servicio se actualiza solo.

private fun direccionLocalDe(config: PanelConfig, service: Service): String =
    if (service.id.isBlank()) "" else config.urlOf(service, away = false)

private fun direccionDeFueraDe(config: PanelConfig, service: Service): String {
    if (service.id.isBlank()) return ""

    val servidor = config.server(service.serverId)
    return when {
        servidor != null && servidor.hostAway.isNotBlank() -> config.urlOf(service, away = true)
        servidor != null -> ""
        else -> service.urlOwnAway
    }
}

/**
 * Máquina guardada a la que corresponden estas direcciones, si hay alguna.
 *
 * Hace falta que **las dos** encajen: si la de fuera apunta a otro sitio, el servicio no
 * puede colgar de esa máquina, porque entonces heredaría una dirección de fuera que no es
 * la que el usuario ha escrito.
 */
private fun servidorPara(config: PanelConfig, local: String, fuera: String): Server? {
    val hostLocal = hostOf(local.trim())
    if (hostLocal.isBlank()) return null

    val servidor = config.servers.firstOrNull { it.hostHome.equals(hostLocal, ignoreCase = true) }
        ?: return null

    val hostFuera = hostOf(fuera.trim())

    return when {
        hostFuera.isBlank() -> servidor
        hostFuera.equals(servidor.hostAway, ignoreCase = true) -> servidor
        else -> null
    }
}

/** Guarda la dirección, vinculada a una máquina o completa. */
private fun conDireccion(
    service: Service,
    local: String,
    fuera: String,
    servidor: Server?
): Service {
    if (servidor == null) {
        return service.copy(serverId = "", urlOwn = local, urlOwnAway = fuera)
    }

    val uri = runCatching { java.net.URI(local) }.getOrNull()

    return service.copy(
        serverId = servidor.id,
        scheme = uri?.scheme?.lowercase() ?: "http",
        port = uri?.port?.takeIf { it > 0 } ?: 0,
        path = buildString {
            append(uri?.path?.takeIf { it.isNotBlank() } ?: "/")
            uri?.rawQuery?.let { append("?").append(it) }
            uri?.rawFragment?.let { append("#").append(it) }
        },
        urlOwn = "",
        urlOwnAway = ""
    )
}

/** Cambia el equipo de una dirección conservando esquema, puerto y ruta. */
private fun conHost(url: String, host: String): String {
    val limpio = url.trim()
    if (limpio.isBlank()) return "http://$host/"

    val uri = runCatching { java.net.URI(limpio) }.getOrNull() ?: return "http://$host/"
    val esquema = uri.scheme?.lowercase() ?: "http"
    val puerto = if (uri.port > 0) ":${uri.port}" else ""
    val ruta = uri.path?.takeIf { it.isNotBlank() } ?: "/"
    val consulta = uri.rawQuery?.let { "?$it" }.orEmpty()
    val ancla = uri.rawFragment?.let { "#$it" }.orEmpty()

    return "$esquema://$host$puerto$ruta$consulta$ancla"
}

/** Quita el esquema y la barra final si el usuario los pega por costumbre. */
private fun limpiarHost(valor: String): String = valor.trim()
    .removePrefix("http://")
    .removePrefix("https://")
    .trimEnd('/')
    .substringBefore('/')

/**
 * Aviso al salir de una ficha con cambios sin guardar.
 *
 * Es el mismo en las tres fichas —servicio, servidor y destino de descarga— a propósito:
 * son la misma situación y no hay motivo para que se pregunten de tres maneras distintas.
 */
@Composable
fun AvisoDeCambiosSinGuardar(onDescartar: () -> Unit, onSeguir: () -> Unit) {
    AlertDialog(
        onDismissRequest = onSeguir,
        title = { Text(stringResource(R.string.discard_title)) },
        text = { Text(stringResource(R.string.discard_body), fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = onDescartar) {
                Text(stringResource(R.string.discard_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onSeguir) {
                Text(stringResource(R.string.discard_keep))
            }
        }
    )
}

@Composable
fun BarraDeEdicion(
    titulo: String,
    guardarActivado: Boolean,
    onGuardar: () -> Unit,
    onCancelar: () -> Unit,
    onBorrar: (() -> Unit)?
) {
    // El título va en una capa aparte, centrado respecto a la pantalla entera. Repartir
    // el ancho sobrante entre los botones no vale: sin el botón de borrar, que solo
    // aparece al editar, el hueco de la derecha es más pequeño y el título se descentra.
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Text(
            titulo,
            color = Color.White,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 96.dp)
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onCancelar) {
                Text(stringResource(R.string.cancel), color = Color.White)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                onBorrar?.let {
                    IconButton(onClick = it) {
                        Icon(
                            Icons.Default.Delete,
                            stringResource(R.string.delete),
                            tint = Color.White
                        )
                    }
                }
                IconButton(onClick = onGuardar, enabled = guardarActivado) {
                    Icon(
                        Icons.Default.Check,
                        stringResource(R.string.save),
                        tint = if (guardarActivado) Color.White else Color.White.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

@Composable
private fun Campo(
    valor: String,
    onChange: (String) -> Unit,
    etiqueta: String,
    modifier: Modifier = Modifier,
    ayuda: String? = null,
    numerico: Boolean = false,
    contrasena: Boolean = false
) {
    Column(modifier.padding(bottom = 6.dp)) {
        OutlinedTextField(
            value = valor,
            onValueChange = onChange,
            label = { Text(etiqueta) },
            singleLine = true,
            visualTransformation = if (contrasena) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    numerico -> KeyboardType.Number
                    contrasena -> KeyboardType.Password
                    else -> KeyboardType.Uri
                }
            ),
            modifier = Modifier.fillMaxWidth()
        )
        ayuda?.let {
            Text(
                it,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}

@Composable
private fun Interruptor(
    valor: Boolean,
    onChange: (Boolean) -> Unit,
    titulo: String,
    ayuda: String? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(titulo, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
            ayuda?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                )
            }
        }
        Switch(checked = valor, onCheckedChange = onChange)
    }
}

@Composable
private fun Apartado(titulo: String, modifier: Modifier = Modifier) {
    Text(
        titulo,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 22.dp, bottom = 8.dp)
    )
}

/**
 * Botón que rellena la dirección con la de una máquina ya guardada.
 *
 * Es solo un atajo de escritura: no cambia los campos ni el modo del formulario, que era
 * lo que despistaba. Lo que vale es lo que quede escrito en la dirección.
 */
@Composable
private fun RellenarDesdeServidor(servers: List<Server>, onPick: (Server) -> Unit) {
    var abierto by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { abierto = true }) {
            Text(stringResource(R.string.address_use_server))
        }
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            servers.forEach { servidor ->
                DropdownMenuItem(
                    text = { Text("${servidor.name} · ${servidor.hostHome}") },
                    onClick = { abierto = false; onPick(servidor) }
                )
            }
        }
    }
}


/**
 * Fila que enseña con qué aplicación se abre el servicio y deja cambiarla.
 *
 * Con recuadro, rótulo encima y flecha, como el resto de lo pulsable: una línea de texto
 * suelta no se reconoce como botón.
 */
@Composable
private fun SelectorDeAplicacion(paquete: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val nombre = remember(paquete) { ExternalApps.label(context, paquete) }
    val icono = remember(paquete) { ExternalApps.icon(context, paquete) }

    Column(Modifier.padding(top = 6.dp, bottom = 6.dp)) {
        Text(
            stringResource(R.string.open_external_app),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )

        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icono != null) {
                    Image(icono, null, Modifier.size(30.dp))
                } else {
                    Icon(
                        Icons.Default.Apps,
                        null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    // Sin aplicación elegida la fila es la propia invitación a elegirla;
                    // decir además que abre el navegador sobraba y hacía dos textos.
                    Text(
                        when {
                            paquete.isBlank() -> stringResource(R.string.open_external_pick)
                            nombre != null -> nombre
                            else -> paquete
                        },
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // Una aplicación desinstalada deja de abrir el servicio, y hay que
                    // verlo aquí y no solo al pulsar la tarjeta.
                    if (paquete.isNotBlank() && nombre == null) {
                        Text(
                            stringResource(R.string.open_external_app_gone),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Icon(
                    Icons.Default.ChevronRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

/**
 * Lista de aplicaciones instaladas.
 *
 * La primera opción vuelve al navegador, para poder deshacer la elección. Se ponen arriba
 * las que se parecen al nombre del servicio: quien escribe «Immich» encuentra Immich sin
 * bajar por doscientas aplicaciones.
 */
@Composable
private fun SelectorDeAplicaciones(
    actual: String,
    sugerencia: String,
    onCancel: () -> Unit,
    onPick: (String) -> Unit
) {
    val context = LocalContext.current
    var todas by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var busqueda by remember { mutableStateOf("") }

    // Listar las aplicaciones del móvil tarda lo suyo; fuera del hilo de la interfaz.
    LaunchedEffect(Unit) {
        todas = withContext(Dispatchers.IO) { ExternalApps.installed(context) }
    }

    val lista = remember(todas, busqueda, sugerencia) {
        val filtradas = todas.orEmpty().filter {
            busqueda.isBlank() || it.label.contains(busqueda.trim(), ignoreCase = true)
        }
        val clave = sugerencia.trim().lowercase()
        if (clave.length < 3) {
            filtradas
        } else {
            // Orden estable: las parecidas primero, y dentro de cada grupo el alfabético.
            filtradas.sortedByDescending {
                val etiqueta = it.label.lowercase()
                etiqueta.contains(clave) || clave.contains(etiqueta)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.open_external_pick)) },
        text = {
            Column {
                OutlinedTextField(
                    value = busqueda,
                    onValueChange = { busqueda = it },
                    label = { Text(stringResource(R.string.open_external_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                if (todas == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        item {
                            FilaDeAplicacion(
                                icono = null,
                                nombre = stringResource(R.string.open_external_app_none),
                                elegida = actual.isBlank(),
                                onClick = { onPick("") }
                            )
                        }
                        items(lista, key = { it.packageName }) { app ->
                            FilaDeAplicacion(
                                icono = ExternalApps.icon(context, app.packageName),
                                nombre = app.label,
                                elegida = app.packageName == actual,
                                onClick = { onPick(app.packageName) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun FilaDeAplicacion(
    icono: androidx.compose.ui.graphics.ImageBitmap?,
    nombre: String,
    elegida: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icono != null) {
            Image(icono, null, Modifier.size(32.dp))
        } else {
            Icon(
                Icons.Default.Apps,
                null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            nombre,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (elegida) {
            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Los dibujos que trae la aplicación. Ver [OwnIcons]. */
@Composable
private fun SelectorDeIconoPropio(
    actual: String,
    onCancel: () -> Unit,
    onPick: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.icon_choose_own)) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 76.dp),
                modifier = Modifier.heightIn(max = 380.dp)
            ) {
                items(OwnIcons.all, key = { it.id }) { propio ->
                    val elegido = OwnIcons.isOwn(actual) && OwnIcons.idOf(actual) == propio.id

                    Column(
                        Modifier
                            .padding(6.dp)
                            .clickable { onPick(propio.id) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (elegido) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                androidx.compose.ui.res.painterResource(propio.drawable),
                                null,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Text(
                            stringResource(propio.labelRes),
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** Rejilla de iconos de categoría. La usan la ficha del servicio y el logotipo del panel. */
@Composable
fun SelectorDeCategoria(actual: String, onCancel: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.icon_choose_category)) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 76.dp),
                modifier = Modifier.heightIn(max = 380.dp)
            ) {
                items(Categories.all, key = { it.id }) { categoria ->
                    Column(
                        Modifier
                            .padding(6.dp)
                            .clickable { onPick(categoria.id) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (categoria.id == actual) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                categoria.icon,
                                null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        // Sin centrar el texto, los nombres que ocupan dos líneas
                        // («Máquinas virtuales», «Copias de seguridad») quedaban
                        // alineados a la izquierda dentro de su hueco y parecían
                        // descuadrados respecto al icono.
                        Text(
                            stringResource(categoria.labelRes),
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    )
}

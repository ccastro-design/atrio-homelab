package com.homelab.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pantalla principal: los servicios agrupados, con su estado y el buscador.
 *
 * Barra azul arriba y los servicios en una sola columna, uno por línea, como en la
 * versión de la que parte este proyecto: con el nombre a la derecha del icono se lee de
 * un vistazo y caben la descripción y el estado sin apretar nada.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dashboard(
    config: PanelConfig,
    away: Boolean,
    status: StatusMap,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onServiceClick: (Service) -> Unit,
    onProfileChange: (NetworkProfile) -> Unit,
    onSettings: () -> Unit,
    onSendLink: () -> Unit,
    onAddService: () -> Unit,
    onScanNetwork: () -> Unit,
    onRemoveExamples: () -> Unit
) {
    // El buscador vive en una ventana aparte, que abre la lupa de al lado del selector de
    // red. En la barra no cabía —tres acciones son el tope— y filtrando la lista de debajo
    // obligaba a tener siempre un campo de texto puesto, quitando sitio a los servicios.
    var buscadorAbierto by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        // La imagen de fondo va detrás de todo y atenuada: sin atenuar, sobre una foto
        // cualquiera no hay color de texto que se lea siempre.
        FondoDelPanel(config)

        Column(Modifier.fillMaxSize()) {
        BarraDelPanel(
            titulo = config.title.ifBlank { stringResource(R.string.app_full_name) },
            subtitulo = config.subtitle.ifBlank { stringResource(R.string.panel_subtitle_default) },
            logoFile = config.logoFile,
            logoIcon = config.logoIcon,
            mostrarRefrescar = config.checkStatus && !config.isEmpty,
            refrescando = refreshing,
            onRefresh = onRefresh,
            onSendLink = onSendLink,
            onSettings = onSettings
        )

        // Los dos mandos del panel, en una fila: el selector de red a la izquierda y la
        // lupa a la derecha. El selector solo aparece si hay algo que elegir, es decir, si
        // algún servidor tiene una dirección distinta para cuando no estás en casa; la
        // lupa, solo si hay servicios propios que buscar. Si no hay ninguno de los dos la
        // fila entera se va, para no dejar un hueco en blanco encima de las tarjetas.
        val hayPerfil = config.servers.any { it.hostAway.isNotBlank() }
        val hayQueBuscar = !config.isEmpty

        if (hayPerfil || hayQueBuscar) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hayPerfil) {
                    SelectorDePerfil(
                        config = config,
                        away = away,
                        onProfileChange = onProfileChange
                    )
                }

                Spacer(Modifier.weight(1f))

                if (hayQueBuscar) {
                    BurbujaDeBusqueda(onClick = { buscadorAbierto = true })
                }
            }
        }

        when {
            config.isEmpty && !config.hasExamples -> Bienvenida(
                onAddService = onAddService,
                onScanNetwork = onScanNetwork
            )

            // Tirar hacia abajo para volver a comprobar el estado, como en un navegador.
            // El botón de la barra se queda: el gesto no se ve, y quien no lo conozca
            // tiene que poder refrescar igual.
            else -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh
            ) {
            LazyColumn(
                contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                config.groups.forEach { grupo ->
                    val colorDelGrupo = colorDeAjuste(grupo.color)

                    // El color del grupo manda sobre el general: quien se lo ha puesto a
                    // un grupo concreto lo ha hecho para distinguirlo del resto.
                    item(key = "g-${grupo.id}") {
                        CabeceraDeGrupo(
                            nombre = grupo.name,
                            color = colorDelGrupo ?: LocalColoresDelPanel.current.nombreDeGrupo
                        )
                    }

                    items(grupo.services, key = { "s-${it.id}" }) { servicio ->
                        TarjetaDeServicio(
                            service = servicio,
                            url = config.urlOf(servicio, away),
                            origen = config.iconOrigin(servicio),
                            status = status[servicio.id],
                            opacidad = config.cardOpacity.coerceIn(0, 100) / 100f,
                            tamano = config.cardSize,
                            mostrarDireccion = config.showAddress,
                            avisarSinCifrar = config.warnCleartext && servicio.warnCleartext,
                            colorDeGrupo = colorDelGrupo,
                            colorDelNombre = LocalColoresDelPanel.current.nombreDeServicio,
                            onClick = { onServiceClick(servicio) }
                        )
                    }
                }

                if (config.isEmpty && config.hasExamples) {
                    item(key = "ayuda-ejemplos") {
                        AyudaDeEjemplos(
                            onAddService = onAddService,
                            onScanNetwork = onScanNetwork,
                            onRemoveExamples = onRemoveExamples
                        )
                    }
                }
            }
            }
        }
        }

        if (buscadorAbierto) {
            VentanaDeBusqueda(
                config = config,
                away = away,
                onServiceClick = { servicio ->
                    buscadorAbierto = false
                    onServiceClick(servicio)
                },
                onDismiss = { buscadorAbierto = false }
            )
        }
    }
}

/**
 * Imagen de fondo del panel, si el usuario ha puesto una.
 *
 * Se recorta para llenar la pantalla y se oscurece con un velo del color del fondo. El
 * velo no es un adorno: una foto con zonas claras y oscuras se come cualquier color de
 * texto, y sin él el panel deja de leerse en cuanto se elige una imagen con contraste.
 */
@Composable
private fun FondoDelPanel(config: PanelConfig) {
    val context = LocalContext.current
    if (config.backgroundImage.isBlank()) return

    // Los fondos que trae la aplicación se dibujan, así que no hay imagen que cargar ni
    // encuadre que respetar: la composición ya se adapta sola a la pantalla.
    if (SystemBackgrounds.isSystem(config.backgroundImage)) {
        SystemBackgroundCanvas(
            id = SystemBackgrounds.idOf(config.backgroundImage),
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (config.backgroundBlur > 0) {
                        Modifier.blur(config.backgroundBlur.dp)
                    } else {
                        Modifier
                    }
                )
        )
        Velo(config)
        return
    }

    val imagen = produceState<ImageBitmap?>(null, config.backgroundImage) {
        value = withContext(Dispatchers.IO) {
            IconStore.userIcon(context, config.backgroundImage)?.let { fichero ->
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(fichero.absolutePath)
                        ?.asImageBitmap()
                }.getOrNull()
            }
        }
    }.value ?: return

    // Mismo cálculo que la vista previa de Ajustes, para que lo encuadrado allí sea
    // exactamente lo que se ve aquí. Ver `ImagenEncuadrada`: con `Crop` el recorte se
    // hace antes del zoom y lo que se descarta no vuelve al empequeñecer.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cabe = minOf(
            constraints.maxWidth.toFloat() / imagen.width,
            constraints.maxHeight.toFloat() / imagen.height
        )
        val llena = maxOf(
            constraints.maxWidth.toFloat() / imagen.width,
            constraints.maxHeight.toFloat() / imagen.height
        )
        val dePartida = if (cabe > 0f) llena / cabe else 1f

        Image(
            bitmap = imagen,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Aumento y desplazamiento elegidos en Ajustes. El desplazamiento va
                    // en fracción de pantalla, así que vale igual en cualquier móvil.
                    scaleX = dePartida * config.backgroundScale
                    scaleY = dePartida * config.backgroundScale
                    translationX = config.backgroundOffsetX * size.width
                    translationY = config.backgroundOffsetY * size.height
                }
                .then(
                    if (config.backgroundBlur > 0) {
                        Modifier.blur(config.backgroundBlur.dp)
                    } else {
                        Modifier
                    }
                )
        )
    }
    Velo(config)
}

/**
 * El velo que oscurece el fondo.
 *
 * No es un adorno: una imagen con zonas claras y oscuras se come cualquier color de texto,
 * y sin él el panel deja de leerse en cuanto se pone un fondo con contraste.
 */
@Composable
private fun Velo(config: PanelConfig) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                MaterialTheme.colorScheme.background
                    .copy(alpha = config.backgroundDim.coerceIn(0, 100) / 100f)
            )
    )
}

@Composable
private fun BarraDelPanel(
    titulo: String,
    subtitulo: String,
    logoFile: String,
    logoIcon: String,
    mostrarRefrescar: Boolean,
    refrescando: Boolean,
    onRefresh: () -> Unit,
    onSendLink: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LogoDelPanel(logoFile, logoIcon)
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                titulo,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitulo,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // La lupa se ha retirado a propósito: con cuatro acciones la barra se apiñaba y
        // el subtítulo no cabía. El buscador sigue construido y funcionando (ver
        // `buscando` más arriba); solo le falta un punto de entrada, pendiente de dar
        // con una colocación mejor.

        if (mostrarRefrescar) {
            IconButton(onClick = onRefresh, enabled = !refrescando) {
                if (refrescando) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        stringResource(R.string.refresh_status),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
        IconButton(onClick = onSendLink) {
            Icon(Icons.Default.FileDownload, stringResource(R.string.send_link), tint = MaterialTheme.colorScheme.onPrimary)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, stringResource(R.string.settings), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/**
 * Logotipo del panel. Por omisión el de la aplicación; el usuario puede poner el suyo
 * desde Ajustes › Apariencia.
 */
@Composable
private fun LogoDelPanel(logoFile: String, logoIcon: String) {
    val context = LocalContext.current

    val propio = produceState<ImageBitmap?>(null, logoFile) {
        value = withContext(Dispatchers.IO) {
            IconStore.userIcon(context, logoFile)?.let { fichero ->
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(fichero.absolutePath)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }.value

    Box(
        Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        when {
            propio != null ->
                Image(bitmap = propio, contentDescription = null, modifier = Modifier.size(42.dp))

            // Un icono del juego propio, elegido en Ajustes. En blanco, que va sobre la
            // barra de color.
            logoIcon.isNotBlank() -> Icon(
                Categories.icon(logoIcon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(26.dp)
            )

            else -> Icon(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(42.dp)
            )
        }
    }
}

@Composable
private fun SelectorDePerfil(
    config: PanelConfig,
    away: Boolean,
    onProfileChange: (NetworkProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    var abierto by remember { mutableStateOf(false) }

    val etiquetaCasa = config.labelHome.ifBlank { stringResource(R.string.profile_home) }
    val etiquetaFuera = config.labelAway.ifBlank { stringResource(R.string.profile_away) }

    val texto = when (config.networkProfile) {
        NetworkProfile.HOME -> etiquetaCasa
        NetworkProfile.AWAY -> etiquetaFuera

        // En automático se dice qué red ha salido elegida, y con el nombre que le haya
        // puesto el usuario: si él llama «Red Local» a la de casa, leer «en casa» aquí
        // parece otra cosa distinta.
        NetworkProfile.AUTO -> stringResource(
            R.string.profile_auto_with,
            if (away) etiquetaFuera else etiquetaCasa
        )
    }

    Box(modifier) {
        // Con los colores de la barra: es el mismo mando del panel, aunque caiga debajo.
        // Va la pastilla entera y no solo el texto, porque el selector se dibuja sobre el
        // fondo —que puede ser una imagen cualquiera— y un texto suelto del color de la
        // barra desaparecería en cuanto el fondo se le pareciera.
        AssistChip(
            onClick = { abierto = true },
            label = { Text(texto, fontSize = 13.sp) },
            trailingIcon = { Icon(Icons.Default.ExpandMore, null, Modifier.size(17.dp)) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primary,
                labelColor = MaterialTheme.colorScheme.onPrimary,
                trailingIconContentColor = MaterialTheme.colorScheme.onPrimary
            ),
            border = null
        )

        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.profile_auto)) },
                onClick = { abierto = false; onProfileChange(NetworkProfile.AUTO) }
            )
            DropdownMenuItem(
                text = { Text(etiquetaCasa) },
                onClick = { abierto = false; onProfileChange(NetworkProfile.HOME) }
            )
            DropdownMenuItem(
                text = { Text(etiquetaFuera) },
                onClick = { abierto = false; onProfileChange(NetworkProfile.AWAY) }
            )
        }
    }
}

/**
 * La lupa que abre el buscador, a la derecha del selector de red.
 *
 * Es la misma pastilla que el selector y lleva sus mismos colores, y por el mismo motivo:
 * los dos se dibujan sobre el fondo del panel —que puede ser una imagen cualquiera— y sin
 * su propio color detrás desaparecerían en cuanto el fondo se les pareciera.
 */
@Composable
private fun BurbujaDeBusqueda(onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = {
            Icon(
                Icons.Default.Search,
                contentDescription = stringResource(R.string.search_hint),
                modifier = Modifier.size(18.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primary,
            labelColor = MaterialTheme.colorScheme.onPrimary
        ),
        border = null
    )
}

/**
 * El buscador, en una ventana aparte.
 *
 * Se abre con el teclado puesto y con la lista entera delante, no en blanco: así vale
 * igual para buscar por nombre que para llegar a un servicio concreto sin recorrer el
 * panel. Al elegir uno se cierra sola y lo abre, que es a lo que se venía.
 *
 * Los resultados van agrupados como en el panel. Un servicio suelto no dice de qué máquina
 * es, y quien tiene el mismo nombre repetido en dos servidores —cosa corriente en un
 * homelab— necesita el grupo para saber cuál está eligiendo.
 */
@Composable
private fun VentanaDeBusqueda(
    config: PanelConfig,
    away: Boolean,
    onServiceClick: (Service) -> Unit,
    onDismiss: () -> Unit
) {
    var busqueda by rememberSaveable { mutableStateOf("") }
    val foco = remember { FocusRequester() }
    val encontrados = remember(config, busqueda) { filtrar(config.groups, busqueda) }

    // Quien abre el buscador viene a escribir: el teclado sale solo.
    LaunchedEffect(Unit) { foco.requestFocus() }

    // Sin el ancho de fábrica, que es el de un diálogo de aviso: con iconos y nombres de
    // servicio al lado se queda estrecho y los nombres empiezan a cortarse.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Column(Modifier.padding(vertical = 18.dp)) {

                OutlinedTextField(
                    value = busqueda,
                    onValueChange = { busqueda = it },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        // Solo con algo escrito: un aspa fija invita a pulsarla sin que
                        // haya nada que borrar.
                        if (busqueda.isNotEmpty()) {
                            IconButton(onClick = { busqueda = "" }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.search_clear)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .focusRequester(foco)
                )

                if (encontrados.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_results, busqueda),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 24.dp)
                    )
                } else {
                    // Acotada: sin tope, con muchos servicios la ventana crece hasta
                    // comerse la pantalla y el campo de texto se va detrás del teclado.
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        encontrados.forEach { grupo ->
                            item(key = "bg-${grupo.id}") {
                                Text(
                                    text = grupo.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(
                                        start = 18.dp,
                                        top = 12.dp,
                                        bottom = 2.dp
                                    )
                                )
                            }

                            items(grupo.services, key = { "bs-${it.id}" }) { servicio ->
                                FilaDeResultado(
                                    service = servicio,
                                    url = config.urlOf(servicio, away),
                                    origen = config.iconOrigin(servicio),
                                    onClick = { onServiceClick(servicio) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Un servicio en la lista del buscador: icono, nombre y descripción. */
@Composable
private fun FilaDeResultado(
    service: Service,
    url: String,
    origen: String,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ServiceIcon(service = service, url = url, size = 34.dp, origen = origen)

            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = service.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (service.subtitle.isNotBlank()) {
                    Text(
                        text = service.subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CabeceraDeGrupo(nombre: String, color: Color? = null) {
    Text(
        text = nombre,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = color ?: MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

/** Medidas de la tarjeta según el tamaño elegido en Ajustes. */
private data class MedidasDeTarjeta(val icono: Dp, val alto: Dp, val nombre: TextUnit)

private fun medidasDe(tamano: String): MedidasDeTarjeta = when (tamano) {
    "COMPACT" -> MedidasDeTarjeta(icono = 30.dp, alto = 6.dp, nombre = 15.sp)
    "COMFY" -> MedidasDeTarjeta(icono = 54.dp, alto = 18.dp, nombre = 17.sp)
    else -> MedidasDeTarjeta(icono = 46.dp, alto = 13.dp, nombre = 16.sp)
}

/** Un servicio por línea: icono, nombre y descripción a la izquierda, estado a la derecha. */
@Composable
private fun TarjetaDeServicio(
    service: Service,
    url: String,
    /** Las direcciones del servicio, para el icono. Ver [PanelConfig.iconOrigin]. */
    origen: String,
    status: ServiceStatus,
    opacidad: Float,
    tamano: String,
    mostrarDireccion: Boolean,
    avisarSinCifrar: Boolean,
    colorDeGrupo: Color?,
    /** Color del nombre, elegido en Apariencia. Nulo: el del tema. */
    colorDelNombre: Color?,
    onClick: () -> Unit
) {
    val medidas = medidasDe(tamano)

    // Compacta es compacta: una línea por servicio. La descripción, la dirección y el
    // aviso de «sin cifrar» son lo que hace crecer la tarjeta a tres líneas, así que en
    // este tamaño no salen. Lo que se quiere aquí es ver muchos servicios de un vistazo;
    // el detalle está a un toque, en su ficha.
    val compacta = tamano == "COMPACT"

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        // Con la tarjeta translúcida se ve el fondo a través de ella. El texto y el icono
        // se quedan opacos: lo que se atenúa es el papel, no lo escrito encima.
        color = MaterialTheme.colorScheme.surface.copy(alpha = opacidad),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .border(
                // El color del grupo se nota en el borde: tiñe sin gritar, y deja el
                // icono y el texto en paz.
                if (colorDeGrupo != null) 2.dp else 1.dp,
                colorDeGrupo ?: MaterialTheme.colorScheme.outline,
                RoundedCornerShape(16.dp)
            )
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = medidas.alto),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ServiceIcon(service = service, url = url, size = medidas.icono, origen = origen)

            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text(
                    text = service.name,
                    fontSize = medidas.nombre,
                    fontWeight = FontWeight.SemiBold,
                    color = colorDelNombre ?: MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // La descripción y la dirección siguen al color del nombre, no al del
                // tema: quien pone el nombre en blanco porque su fondo es oscuro espera
                // que lo de debajo haga lo mismo. Y con más cuerpo del que tenían, que
                // al 60 % de opacidad y sobre una imagen no se leían.
                val colorBase = colorDelNombre ?: MaterialTheme.colorScheme.onSurface

                if (service.subtitle.isNotBlank() && !compacta) {
                    Text(
                        text = service.subtitle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorBase.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                if (mostrarDireccion && !compacta && url.isNotBlank()) {
                    Text(
                        text = url,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = colorBase.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            if (service.isExample) {
                Text(
                    text = stringResource(R.string.example_badge),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            } else {
                // Los dos avisos juntos a la derecha: si el de «sin cifrar» va debajo de
                // la descripción, el bloque de texto crece a tres líneas y el estado
                // queda descuadrado respecto al nombre.
                Column(horizontalAlignment = Alignment.End) {
                    Estado(status)

                    if (avisarSinCifrar && isCleartext(url) && !compacta) {
                        Text(
                            text = stringResource(R.string.status_cleartext),
                            fontSize = 11.sp,
                            color = PanelColors.Warning,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Punto de color y su significado escrito al lado. */
@Composable
private fun Estado(status: ServiceStatus) {
    // Sobre fondo oscuro hacen falta tonos más claros: el verde y el rojo de siempre son
    // los correctos sobre blanco, pero sobre una imagen oscura se apagan. Se mira la
    // luminosidad del fondo que hay de verdad, no el ajuste de tema, porque el usuario
    // puede haber puesto un fondo claro con el tema oscuro o al revés.
    val sobreOscuro = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val (color, texto) = when (status.state) {
        ServiceState.UP ->
            (if (sobreOscuro) PanelColors.StatusUpBright else PanelColors.StatusUp) to
                stringResource(R.string.status_online)

        ServiceState.DOWN ->
            (if (sobreOscuro) PanelColors.StatusDownBright else PanelColors.StatusDown) to
                stringResource(R.string.status_offline)

        ServiceState.CHECKING ->
            PanelColors.StatusChecking to stringResource(R.string.status_checking)

        ServiceState.UNKNOWN -> return
    }

    // Punto algo mayor y texto en seminegrita: con las tarjetas transparentes, el fondo
    // se ve a través y un texto fino de 12 sobre una imagen se pierde. El verde y el rojo
    // aguantan cualquier fondo, pero solo si tienen cuerpo.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(11.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            texto,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
private fun Bienvenida(onAddService: () -> Unit, onScanNetwork: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.welcome_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.welcome_body),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(22.dp))

        Button(onClick = onScanNetwork, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.TravelExplore, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.welcome_scan))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onAddService, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.welcome_add))
        }
    }
}

/** Bloque de ayuda mientras el panel solo tiene los servicios de ejemplo. */
@Composable
private fun AyudaDeEjemplos(
    onAddService: () -> Unit,
    onScanNetwork: () -> Unit,
    onRemoveExamples: () -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 20.dp)) {
        Text(
            stringResource(R.string.welcome_body),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onScanNetwork) { Text(stringResource(R.string.welcome_scan)) }
            OutlinedButton(onClick = onAddService) { Text(stringResource(R.string.welcome_add)) }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRemoveExamples) {
            Text(stringResource(R.string.examples_remove))
        }
    }
}

/** Deja solo los servicios que coinciden con la búsqueda, y los grupos con algo dentro. */
private fun filtrar(grupos: List<ServiceGroup>, busqueda: String): List<ServiceGroup> {
    val texto = busqueda.trim()
    if (texto.isEmpty()) return grupos

    return grupos.mapNotNull { grupo ->
        val encontrados = grupo.services.filter { servicio ->
            servicio.name.contains(texto, ignoreCase = true) ||
                servicio.subtitle.contains(texto, ignoreCase = true)
        }
        if (encontrados.isEmpty()) null else grupo.copy(services = encontrados)
    }
}

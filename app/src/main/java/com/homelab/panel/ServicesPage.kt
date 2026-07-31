package com.homelab.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/** Filas de la lista, ya aplanadas: cabeceras de grupo y servicios. */
private sealed interface Fila {
    data class Cabecera(val group: ServiceGroup, val groupIndex: Int) : Fila
    data class Item(val service: Service, val groupId: String, val globalIndex: Int) : Fila
}

/**
 * Pantalla de servicios y grupos, con reordenación arrastrando.
 *
 * El orden se lleva sobre **todos** los servicios seguidos, sin separar por grupos: así,
 * arrastrar uno más allá del último de su grupo lo pasa al siguiente sin ningún caso
 * especial. Hereda el grupo del servicio cuyo sitio ocupa.
 */
@Composable
fun ServicesPage(
    config: PanelConfig,
    onConfigChange: (PanelConfig) -> Unit,
    onEdit: (Service, String) -> Unit,
    onAddIn: (String) -> Unit
) {
    var grupoRenombrando by remember { mutableStateOf<ServiceGroup?>(null) }

    // El arrastre necesita la configuración del momento, no la capturada al empezar.
    val actual by rememberUpdatedState(config)
    val vibrar = LocalHapticFeedback.current

    var arrastrado by remember { mutableStateOf<String?>(null) }
    var desplazamiento by remember { mutableFloatStateOf(0f) }
    var alturaFila by remember { mutableFloatStateOf(0f) }

    var grupoArrastrado by remember { mutableStateOf<String?>(null) }
    var desplazamientoGrupo by remember { mutableFloatStateOf(0f) }
    var alturaCabecera by remember { mutableFloatStateOf(0f) }


    // Mientras se arrastra un grupo se muestran solo los nombres de grupo. Con todas las
    // filas iguales, mover un grupo es igual de simple que mover un servicio; midiendo
    // el alto real de cada grupo había que arrastrar casi dos pantallas para saltarse
    // uno grande, y al saltar el brinco era de ese mismo tamaño.
    val ordenandoGrupos = grupoArrastrado != null

    val filas = remember(config, ordenandoGrupos) {
        if (ordenandoGrupos) {
            config.groups.mapIndexed { indice, grupo -> Fila.Cabecera(grupo, indice) }
        } else {
            aplanar(config)
        }
    }
    val total = remember(config) { config.allServices.size }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {

        items(
            count = filas.size,
            key = { indice ->
                when (val fila = filas[indice]) {
                    is Fila.Cabecera -> "g-${fila.group.id}"
                    is Fila.Item -> "s-${fila.service.id}"
                }
            }
        ) { indice ->
            when (val fila = filas[indice]) {
                is Fila.Cabecera -> {
                    val enMovimiento = grupoArrastrado == fila.group.id

                    CabeceraDeGrupo(
                        grupo = fila.group,
                        enMovimiento = enMovimiento,
                        desplazamiento = if (enMovimiento) desplazamientoGrupo else 0f,
                        onMedida = { alto -> if (alturaCabecera == 0f) alturaCabecera = alto.toFloat() },
                        onAddIn = { onAddIn(fila.group.id) },
                        onRename = { grupoRenombrando = fila.group },
                        onDelete = { onConfigChange(ConfigOps.deleteGroup(config, fila.group.id)) },
                        modifierArrastre = Modifier.pointerInput(fila.group.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    grupoArrastrado = fila.group.id
                                    desplazamientoGrupo = 0f
                                    vibrar.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragEnd = { grupoArrastrado = null; desplazamientoGrupo = 0f },
                                onDragCancel = { grupoArrastrado = null; desplazamientoGrupo = 0f },
                                onDrag = { _, delta ->
                                    desplazamientoGrupo += delta.y
                                    val paso = alturaCabecera
                                    if (paso <= 0f) return@detectDragGesturesAfterLongPress

                                    // Con la lista reducida a nombres de grupo, todas las
                                    // filas miden lo mismo: se avanza de una en una.
                                    val pasos = (desplazamientoGrupo / paso).toInt()
                                    if (pasos == 0) return@detectDragGesturesAfterLongPress

                                    val indice = actual.groups.indexOfFirst { it.id == fila.group.id }
                                    if (indice < 0) return@detectDragGesturesAfterLongPress

                                    val destino = (indice + pasos)
                                        .coerceIn(0, actual.groups.lastIndex)
                                    if (destino == indice) return@detectDragGesturesAfterLongPress

                                    onConfigChange(ConfigOps.moveGroup(actual, indice, destino))
                                    desplazamientoGrupo -= (destino - indice) * paso
                                }
                            )
                        }
                    )
                }

                is Fila.Item -> {
                    val enMovimiento = arrastrado == fila.service.id

                    FilaDeServicio(
                        service = fila.service,
                        url = config.urlOf(fila.service, away = false),
                        origen = config.iconOrigin(fila.service),
                        enMovimiento = enMovimiento,
                        desplazamiento = if (enMovimiento) desplazamiento else 0f,
                        onMedida = { alto -> if (alturaFila == 0f) alturaFila = alto.toFloat() },
                        onClick = { onEdit(fila.service, fila.groupId) },
                        modifierArrastre = Modifier.pointerInput(fila.service.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    arrastrado = fila.service.id
                                    desplazamiento = 0f
                                    vibrar.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragEnd = { arrastrado = null; desplazamiento = 0f },
                                onDragCancel = { arrastrado = null; desplazamiento = 0f },
                                onDrag = { _, delta ->
                                    desplazamiento += delta.y
                                    val altura = alturaFila
                                    if (altura <= 0f) return@detectDragGesturesAfterLongPress

                                    // Se mueve de uno en uno cada vez que el dedo recorre
                                    // el alto de una fila.
                                    val pasos = (desplazamiento / altura).toInt()
                                    if (pasos == 0) return@detectDragGesturesAfterLongPress

                                    val desde = indiceGlobal(actual, fila.service.id)
                                    if (desde < 0) return@detectDragGesturesAfterLongPress

                                    val hasta = (desde + pasos).coerceIn(0, total - 1)
                                    if (hasta == desde) return@detectDragGesturesAfterLongPress

                                    onConfigChange(ConfigOps.moveService(actual, desde, hasta))
                                    desplazamiento -= (hasta - desde) * altura
                                }
                            )
                        }
                    )
                }
            }
        }

    }

    grupoRenombrando?.let { grupo ->
        EditorDeGrupo(
            grupo = grupo,
            onCancel = { grupoRenombrando = null },
            onAccept = { nombre, color ->
                onConfigChange(
                    ConfigOps.renameGroup(config, grupo.id, nombre)
                        .let { nueva ->
                            nueva.copy(
                                groups = nueva.groups.map {
                                    if (it.id == grupo.id) it.copy(color = color) else it
                                }
                            )
                        }
                )
                grupoRenombrando = null
            }
        )
    }
}

/**
 * Ficha del grupo: su nombre y su color.
 *
 * El color vive aquí y no en Apariencia porque es de cada grupo, como el nombre. En
 * Apariencia habría que poner una fila por grupo y esa pantalla ya es larga.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EditorDeGrupo(
    grupo: ServiceGroup,
    titulo: String? = null,
    onCancel: () -> Unit,
    onAccept: (String, String) -> Unit
) {
    var nombre by remember { mutableStateOf(grupo.name) }
    var color by remember { mutableStateOf(grupo.color) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(titulo ?: stringResource(R.string.group_edit)) },
        text = {
            Column {
                OutlinedTextField(
                    value = nombre,
                    onValueChange = { nombre = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    stringResource(R.string.group_color),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
                )

                // Hay dos sitios donde se decide el color de un grupo, y este manda sobre
                // el otro. Sin decirlo, quien cambie el color general en Apariencia y vea
                // que este grupo no cambia pensará que está roto.
                Text(
                    stringResource(R.string.group_color_overrides),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                FlowRow {
                    ColoresDeGrupo.forEach { codigo ->
                        val elegido = codigo.equals(color, ignoreCase = true)
                        val muestra = colorDeAjuste(codigo)

                        Box(
                            Modifier
                                .padding(4.dp)
                                .size(34.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(muestra ?: MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    if (elegido) 3.dp else 1.dp,
                                    MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(9.dp)
                                )
                                .clickable { color = codigo },
                            contentAlignment = Alignment.Center
                        ) {
                            // El primero es «sin color», y sin una marca dentro no habría
                            // forma de distinguirlo de una muestra más.
                            if (codigo.isBlank()) {
                                Icon(
                                    Icons.Default.FormatColorReset,
                                    stringResource(R.string.group_color_none),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = nombre.isNotBlank(),
                onClick = { onAccept(nombre.trim(), color) }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/**
 * Colores de grupo. El primero, vacío, es «sin color».
 *
 * Todos son tonos medios a propósito: el color se usa en el borde de la tarjeta y en el
 * nombre del grupo, así que tiene que verse tanto sobre el fondo claro como sobre el
 * oscuro. Los muy claros desaparecen en el tema claro y los muy oscuros, en el oscuro.
 */
private val ColoresDeGrupo = listOf(
    "",
    "#FFFFFF", "#9AA0A6", "#000000",
    "#2F6BD8", "#4AA3DF", "#00A0B0", "#00897B",
    "#2E9E5B", "#7CB342", "#C0A32E", "#E0A030",
    "#EF7D28", "#D64545", "#E0518A", "#D84F9E",
    "#9C5BD8", "#6C4FD8", "#5C6BC0", "#8D6E63",
    "#78909C"
)

@Composable
private fun CabeceraDeGrupo(
    grupo: ServiceGroup,
    enMovimiento: Boolean,
    desplazamiento: Float,
    onMedida: (Int) -> Unit,
    onAddIn: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifierArrastre: Modifier
) {
    Row(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { onMedida(it.height) }
            .zIndex(if (enMovimiento) 1f else 0f)
            .graphicsLayer { translationY = desplazamiento }
            .padding(start = 18.dp, end = 6.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                grupo.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (grupo.services.isEmpty()) {
                Text(
                    stringResource(R.string.group_empty),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        }

        // El grupo se arrastra por su asa, y se lleva sus servicios detrás.
        Icon(
            Icons.Default.DragIndicator,
            stringResource(R.string.reorder_hint),
            tint = if (enMovimiento) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
            },
            modifier = Modifier
                .padding(end = 6.dp)
                .size(24.dp)
                .then(modifierArrastre)
        )

        MenuDeAccionesLista(
            icono = Icons.Default.Edit,
            acciones = listOf(
                stringResource(R.string.group_add_service) to onAddIn,
                stringResource(R.string.group_rename) to onRename,
                stringResource(R.string.group_delete) to onDelete
            )
        )
    }
}

/**
 * Fila de un servicio.
 *
 * No lleva menú de acciones: tocarla abre su ficha, que es donde se cambia todo y donde
 * está el botón de borrar, y de grupo se cambia arrastrando. Un engranaje al lado solo
 * repetía lo que ya se puede hacer.
 */
@Composable
private fun FilaDeServicio(
    service: Service,
    url: String,
    /** Las direcciones del servicio, para el icono. Ver [PanelConfig.iconOrigin]. */
    origen: String,
    enMovimiento: Boolean,
    desplazamiento: Float,
    onMedida: (Int) -> Unit,
    onClick: () -> Unit,
    modifierArrastre: Modifier
) {
    Row(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { onMedida(it.height) }
            .zIndex(if (enMovimiento) 1f else 0f)
            .graphicsLayer { translationY = desplazamiento },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 5.dp)
                .then(
                    if (enMovimiento) {
                        Modifier.shadow(10.dp, RoundedCornerShape(14.dp))
                    } else {
                        Modifier
                    }
                )
                .border(
                    1.dp,
                    if (enMovimiento) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    RoundedCornerShape(14.dp)
                )
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ServiceIcon(service = service, url = url, size = 40.dp, origen = origen)

                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 14.dp)
                ) {
                    Text(
                        service.name,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        url.ifBlank { service.urlOwn },
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1
                    )
                }

                // Asa de arrastre: sin ella nadie adivina que la fila se puede mover.
                Icon(
                    Icons.Default.DragIndicator,
                    stringResource(R.string.reorder_hint),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier
                        .size(24.dp)
                        .then(modifierArrastre)
                )
            }
        }
    }
}

/**
 * Menú de acciones de un grupo.
 *
 * Con lápiz en vez de tres puntos, que dice mucho más. Los servicios no tienen menú: su
 * fila abre la ficha directamente.
 */
@Composable
private fun MenuDeAccionesLista(
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    acciones: List<Pair<String, () -> Unit>>
) {
    var abierto by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { abierto = true }) {
            Icon(
                icono,
                stringResource(R.string.actions),
                // Blanco sobre fondo oscuro y oscuro sobre fondo claro: el color del
                // texto vale para los dos temas, un blanco fijo se perdería en el claro.
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                modifier = Modifier.size(22.dp)
            )
        }
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            acciones.forEach { (texto, accion) ->
                DropdownMenuItem(
                    text = { Text(texto) },
                    onClick = { abierto = false; accion() }
                )
            }
        }
    }
}

/** Cabeceras y servicios en el orden en que se ven, con el índice global de cada servicio. */
private fun aplanar(config: PanelConfig): List<Fila> {
    val filas = mutableListOf<Fila>()
    var global = 0

    config.groups.forEachIndexed { indiceGrupo, grupo ->
        filas += Fila.Cabecera(grupo, indiceGrupo)
        grupo.services.forEach { servicio ->
            filas += Fila.Item(servicio, grupo.id, global)
            global++
        }
    }

    return filas
}

/** Posición de un servicio contando todos los servicios seguidos. */
private fun indiceGlobal(config: PanelConfig, serviceId: String): Int =
    config.groups.flatMap { it.services }.indexOfFirst { it.id == serviceId }

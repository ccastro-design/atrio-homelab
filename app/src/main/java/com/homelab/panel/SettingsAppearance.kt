package com.homelab.panel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** El logotipo tal como se ve ahora mismo en la barra del panel. */
@Composable
private fun LogoActual(config: PanelConfig) {
    val context = LocalContext.current

    val propio = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, config.logoFile) {
        value = withContext(Dispatchers.IO) {
            IconStore.userIcon(context, config.logoFile)?.let { fichero ->
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(fichero.absolutePath)
                        ?.asImageBitmap()
                }.getOrNull()
            }
        }
    }.value

    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        when {
            propio != null -> Image(
                bitmap = propio,
                contentDescription = null,
                modifier = Modifier.size(52.dp)
            )

            config.logoIcon.isNotBlank() -> Icon(
                Categories.icon(config.logoIcon),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )

            else -> Icon(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(52.dp)
            )
        }
    }
}

/** Un deslizador con su rótulo, que ya lleva el número dentro. */
@Composable
private fun Ajuste(
    texto: String,
    valor: Float,
    rango: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(
            texto,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Slider(value = valor, onValueChange = onChange, valueRange = rango)
    }
}

@Composable
private fun CampoDeAjuste(
    valor: String,
    onChange: (String) -> Unit,
    etiqueta: String,
    marcador: String
) {
    OutlinedTextField(
        value = valor,
        onValueChange = onChange,
        label = { Text(etiqueta) },
        placeholder = { Text(marcador) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PaginaDeApariencia(config: PanelConfig, onConfigChange: (PanelConfig) -> Unit) {
    val context = LocalContext.current
    var eligiendoIcono by remember { mutableStateOf(false) }
    var menuDeLogo by remember { mutableStateOf(false) }
    var encuadrando by remember { mutableStateOf(false) }
    /** Primero de dónde sale el fondo, y después cuál. */
    var eligiendoOrigen by remember { mutableStateOf(false) }
    var eligiendoFondoPropio by remember { mutableStateOf(false) }

    if (encuadrando && config.backgroundImage.isNotBlank()) {
        EncuadreDeFondo(config, onConfigChange) { encuadrando = false }
    }

    val elegirFondo = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            IconStore.saveBackground(context, uri)?.let { nombre ->
                if (config.backgroundImage.isNotBlank()) {
                    IconStore.deleteUserIcon(context, config.backgroundImage)
                }
                // Encuadre de partida limpio: el de la imagen anterior no tiene por qué
                // valerle a esta, que puede ser de otro tamaño y otra forma.
                onConfigChange(
                    config.copy(
                        backgroundImage = nombre,
                        backgroundScale = 1f,
                        backgroundOffsetX = 0f,
                        backgroundOffsetY = 0f
                    )
                )
                // Se pasa directo a encuadrar: elegir una imagen y encuadrarla son el
                // mismo gesto, y nadie quiere dejarla puesta a medias.
                encuadrando = true
            }
        }
    }

    val elegirLogo = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            IconStore.saveUserIcon(context, uri, "logo")?.let { nombre ->
                // Se borra el anterior para no ir acumulando imágenes sin usar.
                if (config.logoFile.isNotBlank()) IconStore.deleteUserIcon(context, config.logoFile)
                onConfigChange(config.copy(logoFile = nombre))
            }
        }
    }

    if (eligiendoIcono) {
        SelectorDeCategoria(
            actual = config.logoIcon,
            onCancel = { eligiendoIcono = false },
            onPick = { icono ->
                // La imagen propia manda sobre el icono, así que al elegir icono se
                // retira la imagen: si no, se elegiría uno y no cambiaría nada.
                if (config.logoFile.isNotBlank()) IconStore.deleteUserIcon(context, config.logoFile)
                onConfigChange(config.copy(logoIcon = icono, logoFile = ""))
                eligiendoIcono = false
            }
        )
    }

    if (eligiendoOrigen) {
        AlertDialog(
            onDismissRequest = { eligiendoOrigen = false },
            title = { Text(stringResource(R.string.background_source_title)) },
            text = { Text(stringResource(R.string.background_source_help), fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    eligiendoOrigen = false
                    eligiendoFondoPropio = true
                }) { Text(stringResource(R.string.background_source_system)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    eligiendoOrigen = false
                    elegirFondo.launch("image/*")
                }) { Text(stringResource(R.string.background_source_gallery)) }
            }
        )
    }

    if (eligiendoFondoPropio) {
        SelectorDeFondoPropio(
            actual = config.backgroundImage,
            onCancel = { eligiendoFondoPropio = false },
            onPick = { elegido ->
                // Si venía una imagen de la galería, se borra: ya no se va a usar y
                // ocuparía sitio para siempre.
                if (config.backgroundImage.isNotBlank() &&
                    !SystemBackgrounds.isSystem(config.backgroundImage)
                ) {
                    IconStore.deleteUserIcon(context, config.backgroundImage)
                }
                onConfigChange(config.copy(backgroundImage = SystemBackgrounds.value(elegido)))
                eligiendoFondoPropio = false
            }
        )
    }

    // El contenido de cada sección va dentro de su recuadro y el título fuera, encima.
    // El margen es de 14 porque es el que llevan las filas de las demás pantallas de
    // ajustes; los títulos se meten 4 más para quedar alineados con el texto de dentro.
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {

        TituloDeSeccion(stringResource(R.string.appearance_logo), Modifier.padding(start = 4.dp))

        Burbuja {
            // Se enseña el logotipo que hay puesto: elegir a ciegas y volver al panel a
            // comprobar qué ha quedado es dar vueltas para nada. Un solo botón, y de dónde
            // sacarlo se pregunta después, en el menú.
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoActual(config)
                Spacer(Modifier.width(14.dp))

                Box {
                    TextButton(onClick = { menuDeLogo = true }) {
                        Text(stringResource(R.string.appearance_logo_icon))
                    }

                    DropdownMenu(
                        expanded = menuDeLogo,
                        onDismissRequest = { menuDeLogo = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.appearance_logo_default)) },
                            onClick = {
                                menuDeLogo = false
                                if (config.logoFile.isNotBlank()) {
                                    IconStore.deleteUserIcon(context, config.logoFile)
                                }
                                onConfigChange(config.copy(logoFile = "", logoIcon = ""))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.appearance_logo_system)) },
                            onClick = { menuDeLogo = false; eligiendoIcono = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.appearance_logo_gallery)) },
                            onClick = { menuDeLogo = false; elegirLogo.launch("image/*") }
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            CampoDeAjuste(
                valor = config.title,
                onChange = { onConfigChange(config.copy(title = it)) },
                etiqueta = stringResource(R.string.appearance_title),
                // El marcador tiene que ser lo que sale si lo dejas vacío, y el panel
                // usa el nombre largo.
                marcador = stringResource(R.string.app_full_name)
            )
            CampoDeAjuste(
                valor = config.subtitle,
                onChange = { onConfigChange(config.copy(subtitle = it)) },
                etiqueta = stringResource(R.string.appearance_subtitle),
                marcador = stringResource(R.string.panel_subtitle_default)
            )
        }

        TituloDeSeccion(
            stringResource(R.string.appearance_profiles),
            Modifier.padding(start = 4.dp)
        )
        Burbuja {
            CampoDeAjuste(
                valor = config.labelHome,
                onChange = { onConfigChange(config.copy(labelHome = it)) },
                etiqueta = stringResource(R.string.appearance_label_home),
                marcador = stringResource(R.string.profile_home)
            )
            CampoDeAjuste(
                valor = config.labelAway,
                onChange = { onConfigChange(config.copy(labelAway = it)) },
                etiqueta = stringResource(R.string.appearance_label_away),
                marcador = stringResource(R.string.profile_away)
            )
        }

        TituloDeSeccion(stringResource(R.string.appearance_theme), Modifier.padding(start = 4.dp))

        Burbuja {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "LIGHT" to stringResource(R.string.appearance_theme_light),
                    "DARK" to stringResource(R.string.appearance_theme_dark),
                    "SYSTEM" to stringResource(R.string.appearance_theme_system)
                ).forEach { (clave, etiqueta) ->
                    FilterChip(
                        selected = config.theme == clave,
                        onClick = { onConfigChange(config.copy(theme = clave)) },
                        label = { Text(etiqueta) }
                    )
                }
            }
        }

        TituloDeSeccion(stringResource(R.string.appearance_colors), Modifier.padding(start = 4.dp))

        // De cada color hay dos, el del tema claro y el del oscuro, y aquí se elige cuál
        // se está tocando. Con un conmutador propio no hace falta cambiar el tema de la
        // aplicación entera para arreglar los colores del otro.
        val temaDelSistema = isSystemInDarkTheme()
        var editandoOscuro by remember {
            mutableStateOf(
                when (config.theme) {
                    "DARK" -> true
                    "LIGHT" -> false
                    else -> temaDelSistema
                }
            )
        }

        // Los colores de fábrica salen del tema **sin** los cambios del usuario: si se
        // preguntara al tema ya montado, «volver al original» devolvería al color que él
        // acaba de poner, o sea a ninguna parte.
        val base = esquemaBase(editandoOscuro)
        // Lo que trae la aplicación, para poder decir «el del tema» en vez de un código
        // en los que el usuario no ha tocado.
        val fab = DefaultConfig.APARIENCIA

        Burbuja {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !editandoOscuro,
                    onClick = { editandoOscuro = false },
                    label = { Text(stringResource(R.string.appearance_colors_light)) }
                )
                FilterChip(
                    selected = editandoOscuro,
                    onClick = { editandoOscuro = true },
                    label = { Text(stringResource(R.string.appearance_colors_dark)) }
                )
            }
            Text(
                stringResource(R.string.appearance_colors_help),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 6.dp)
            )

            SelectorDeColor(
                etiqueta = stringResource(R.string.appearance_color_banner),
                valor = if (editandoOscuro) config.bannerColorDark else config.bannerColor,
                deFabrica = if (editandoOscuro) fab.bannerColorDark else fab.bannerColor,
                porOmision = base.primary,
                onChange = {
                    onConfigChange(
                        if (editandoOscuro) config.copy(bannerColorDark = it)
                        else config.copy(bannerColor = it)
                    )
                }
            )
            SelectorDeColor(
                etiqueta = stringResource(R.string.appearance_color_banner_text),
                valor = if (editandoOscuro) config.bannerTextColorDark else config.bannerTextColor,
                deFabrica = if (editandoOscuro) fab.bannerTextColorDark else fab.bannerTextColor,
                porOmision = base.onPrimary,
                onChange = {
                    onConfigChange(
                        if (editandoOscuro) config.copy(bannerTextColorDark = it)
                        else config.copy(bannerTextColor = it)
                    )
                }
            )
            SelectorDeColor(
                etiqueta = stringResource(R.string.appearance_color_background),
                valor = if (editandoOscuro) config.backgroundColorDark else config.backgroundColor,
                deFabrica = if (editandoOscuro) fab.backgroundColorDark else fab.backgroundColor,
                porOmision = base.background,
                onChange = {
                    onConfigChange(
                        if (editandoOscuro) config.copy(backgroundColorDark = it)
                        else config.copy(backgroundColor = it)
                    )
                }
            )
            SelectorDeColor(
                etiqueta = stringResource(R.string.appearance_color_text),
                valor = if (editandoOscuro) config.textColorDark else config.textColor,
                deFabrica = if (editandoOscuro) fab.textColorDark else fab.textColor,
                porOmision = base.onBackground,
                onChange = {
                    onConfigChange(
                        if (editandoOscuro) config.copy(textColorDark = it)
                        else config.copy(textColor = it)
                    )
                }
            )
            SelectorDeColor(
                etiqueta = stringResource(R.string.appearance_color_service),
                valor = if (editandoOscuro) {
                    config.serviceNameColorDark
                } else {
                    config.serviceNameColor
                },
                deFabrica = if (editandoOscuro) {
                    fab.serviceNameColorDark
                } else {
                    fab.serviceNameColor
                },
                porOmision = base.onSurface,
                onChange = {
                    onConfigChange(
                        if (editandoOscuro) config.copy(serviceNameColorDark = it)
                        else config.copy(serviceNameColor = it)
                    )
                }
            )
            SelectorDeColor(
                etiqueta = stringResource(R.string.appearance_color_group),
                valor = if (editandoOscuro) config.groupNameColorDark else config.groupNameColor,
                deFabrica = if (editandoOscuro) fab.groupNameColorDark else fab.groupNameColor,
                porOmision = base.onBackground,
                onChange = {
                    onConfigChange(
                        if (editandoOscuro) config.copy(groupNameColorDark = it)
                        else config.copy(groupNameColor = it)
                    )
                }
            )
        }

        TituloDeSeccion(stringResource(R.string.appearance_cards), Modifier.padding(start = 4.dp))

        Burbuja {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "COMPACT" to stringResource(R.string.appearance_cards_compact),
                    "NORMAL" to stringResource(R.string.appearance_cards_normal),
                    "COMFY" to stringResource(R.string.appearance_cards_comfy)
                ).forEach { (clave, etiqueta) ->
                    FilterChip(
                        selected = config.cardSize == clave,
                        onClick = {
                            // En compactas la dirección no se dibuja, así que el
                            // interruptor se apaga al elegirlas: dejarlo encendido sin
                            // efecto haría pensar que algo no funciona.
                            onConfigChange(
                                config.copy(
                                    cardSize = clave,
                                    showAddress =
                                        if (clave == "COMPACT") false else config.showAddress
                                )
                            )
                        },
                        label = { Text(etiqueta) }
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val enCompactas = config.cardSize == "COMPACT"

                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.appearance_show_address),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground
                            .copy(alpha = if (enCompactas) 0.4f else 1f)
                    )
                    Text(
                        stringResource(
                            if (enCompactas) {
                                R.string.appearance_show_address_compact
                            } else {
                                R.string.appearance_show_address_help
                            }
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = config.showAddress,
                    enabled = !enCompactas,
                    onCheckedChange = { onConfigChange(config.copy(showAddress = it)) }
                )
            }

            Ajuste(
                texto = stringResource(R.string.appearance_card_opacity, config.cardOpacity),
                valor = config.cardOpacity.toFloat(),
                rango = 20f..100f,
                onChange = { onConfigChange(config.copy(cardOpacity = it.toInt())) }
            )
        }

        TituloDeSeccion(
            stringResource(R.string.appearance_background),
            Modifier.padding(start = 4.dp)
        )

        Burbuja {
            // Los tres, del mismo tipo y separados: como texto suelto, «quitar» quedaba
            // pegado a «elegir» y no parecía un botón, con lo fácil que es darle sin
            // querer.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { eligiendoOrigen = true }) {
                    Text(stringResource(R.string.appearance_background_choose))
                }
                // Encuadrar solo tiene sentido con una imagen de la galería: los fondos
                // propios se dibujan a la medida de la pantalla, no hay nada que recortar.
                if (config.backgroundImage.isNotBlank() &&
                    !SystemBackgrounds.isSystem(config.backgroundImage)
                ) {
                    OutlinedButton(onClick = { encuadrando = true }) {
                        Text(stringResource(R.string.appearance_background_frame_title))
                    }
                }
                if (config.backgroundImage.isNotBlank()) {
                    OutlinedButton(onClick = {
                        if (!SystemBackgrounds.isSystem(config.backgroundImage)) {
                            IconStore.deleteUserIcon(context, config.backgroundImage)
                        }
                        onConfigChange(config.copy(backgroundImage = ""))
                    }) {
                        Text(stringResource(R.string.appearance_background_remove))
                    }
                }
            }

            // Todo esto solo tiene sentido habiendo imagen.
            if (config.backgroundImage.isNotBlank()) {
                Ajuste(
                    texto = stringResource(
                        R.string.appearance_background_dim,
                        config.backgroundDim
                    ),
                    valor = config.backgroundDim.toFloat(),
                    rango = 0f..90f,
                    onChange = { onConfigChange(config.copy(backgroundDim = it.toInt())) }
                )
                Ajuste(
                    texto = stringResource(
                        R.string.appearance_background_blur,
                        config.backgroundBlur
                    ),
                    valor = config.backgroundBlur.toFloat(),
                    rango = 0f..25f,
                    onChange = { onConfigChange(config.copy(backgroundBlur = it.toInt())) }
                )
            }
        }
    }
}

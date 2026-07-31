package com.homelab.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Piezas de las que tira la pantalla de Apariencia para elegir colores y fondo. Están
// aparte de [PaginaDeApariencia] porque son diálogos y controles completos, no trozos de
// esa pantalla: juntos eran ochocientas líneas en las que no se encontraba nada.

/**
 * Colores que se ofrecen. Una paleta corta y elegida a mano en vez de una rueda de
 * color: con la rueda es facilísimo acabar con un texto gris sobre fondo gris, y el
 * panel deja de leerse. Están los grises de los extremos y una vuelta completa de tonos.
 */
private val PALETA = listOf(
    "#FFFFFF", "#E8EAED", "#9AA0A6", "#3C4043", "#14171C", "#000000",
    "#D64545", "#E06C25", "#E0A030", "#2E9E5B", "#00897B", "#2F6BD8",
    "#5B93F0", "#6C4FD8", "#A64FD8", "#D84F9E", "#7A5C3E", "#4A5568"
)

/**
 * Elige un color, con «el del tema» siempre a mano.
 *
 * La muestra de la izquierda enseña el color que hay puesto ahora, para no tener que
 * volver al panel a ver qué ha quedado.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun SelectorDeColor(
    etiqueta: String,
    valor: String,
    porOmision: Color,
    /** Lo que trae la aplicación aquí. Coincidir con esto es «no haber elegido nada». */
    deFabrica: String = "",
    onChange: (String) -> Unit
) {
    var abierto by remember { mutableStateOf(false) }
    var escribiendo by remember { mutableStateOf(false) }
    val actual = colorDeAjuste(valor) ?: porOmision

    // Un color igual al de fábrica no es una elección del usuario, así que se enseña por
    // su nombre y no por su código: enseñar «#FFFFFF» en un ajuste que nadie ha tocado
    // hace pensar que hay algo puesto a mano.
    val esDeFabrica = valor.isBlank() || valor.equals(deFabrica, ignoreCase = true)

    if (escribiendo) {
        DialogoDeColorEscrito(
            inicial = valor.ifBlank { ajusteDeColor(porOmision) },
            onCancel = { escribiendo = false },
            onAccept = { codigo ->
                onChange(codigo)
                escribiendo = false
                abierto = false
            }
        )
    }

    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(actual)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .clickable { abierto = !abierto }
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(etiqueta, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    if (esDeFabrica) stringResource(R.string.appearance_color_default) else valor,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            TextButton(onClick = { abierto = !abierto }) {
                Text(stringResource(R.string.appearance_color_change))
            }
        }

        if (abierto) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Volver al color de fábrica. Con su nombre escrito, porque como muestra
                // de color se confundía con una más de la paleta.
                TextButton(onClick = { onChange(deFabrica); abierto = false }) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.appearance_color_default))
                }
                TextButton(onClick = { escribiendo = true }) {
                    Text(stringResource(R.string.appearance_color_type))
                }
            }

            FlowRow(Modifier.padding(bottom = 4.dp)) {
                PALETA.forEach { codigo ->
                    val color = colorDeAjuste(codigo) ?: return@forEach
                    Box(
                        Modifier
                            .padding(4.dp)
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(color)
                            .border(
                                if (codigo.equals(valor, true)) 3.dp else 1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(9.dp)
                            )
                            .clickable { onChange(codigo); abierto = false }
                    )
                }
            }
        }
    }
}

/**
 * Escribir un color a mano, para quien tenga uno concreto en la cabeza.
 *
 * Se admite con almohadilla o sin ella, y en mayúsculas o minúsculas. Mientras lo que
 * haya escrito no sea un color, el botón de aceptar no deja seguir y se ve una muestra
 * de lo que va quedando.
 */
@Composable
private fun DialogoDeColorEscrito(
    inicial: String,
    onCancel: () -> Unit,
    onAccept: (String) -> Unit
) {
    var texto by remember { mutableStateOf(inicial) }
    val color = colorDeAjuste(texto)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.appearance_color_type)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(9.dp)
                            )
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = texto,
                        onValueChange = { texto = it.take(9) },
                        singleLine = true,
                        isError = color == null,
                        placeholder = { Text("#2F6BD8") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    stringResource(R.string.appearance_color_type_help),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = color != null,
                onClick = { color?.let { onAccept(ajusteDeColor(it)) } }
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
 * Los fondos que trae la aplicación, con su vista previa.
 *
 * Se enseñan dibujados de verdad y no con un nombre: «Arcos», «Atrio» y «Celdas» no le
 * dicen nada a nadie hasta que se ven.
 */
@Composable
internal fun SelectorDeFondoPropio(
    actual: String,
    onCancel: () -> Unit,
    onPick: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.background_system_title)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SystemBackgrounds.all.forEach { fondo ->
                    val elegido = SystemBackgrounds.isSystem(actual) &&
                        SystemBackgrounds.idOf(actual) == fondo.id

                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { onPick(fondo.id) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(132.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    width = if (elegido) 3.dp else 1.dp,
                                    color = if (elegido) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                )
                        ) {
                            SystemBackgroundCanvas(fondo.id, Modifier.fillMaxSize())
                        }
                        Text(
                            stringResource(fondo.labelRes),
                            fontSize = 12.sp,
                            color = if (elegido) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(top = 6.dp)
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

/**
 * Dibuja la imagen con el encuadre elegido: 1 es «llena la pantalla».
 *
 * No se usa `ContentScale.Crop` porque **recorta antes de aplicar el zoom**: lo que sobra
 * se tira nada más entrar y, al empequeñecer luego, lo que encoge es el recorte y la
 * parte descartada no vuelve nunca. Con `Fit` cabe la imagen entera y es el zoom el que
 * decide qué se recorta; el factor de partida se calcula para que a 1 se vea exactamente
 * lo mismo que llenando la pantalla.
 */
@Composable
private fun ImagenEncuadrada(
    imagen: androidx.compose.ui.graphics.ImageBitmap,
    escala: Float,
    moverX: Float,
    moverY: Float
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ancho = constraints.maxWidth.toFloat()
        val alto = constraints.maxHeight.toFloat()

        val cabe = minOf(ancho / imagen.width, alto / imagen.height)
        val llena = maxOf(ancho / imagen.width, alto / imagen.height)
        val dePartida = if (cabe > 0f) llena / cabe else 1f

        Image(
            bitmap = imagen,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = dePartida * escala
                    scaleY = dePartida * escala
                    translationX = moverX * size.width
                    translationY = moverY * size.height
                }
        )
    }
}

/**
 * Encuadre de la imagen de fondo.
 *
 * Se enseña un recorte del alto de una pantalla y se mueve con el dedo o se acerca con
 * dos dedos, como cualquier visor de fotos. Hacía falta: una foto apaisada puesta de
 * fondo en un móvil se recorta por el centro, y lo que interesa casi nunca está justo
 * ahí. Lo que se guarda es el aumento y el desplazamiento, no otra copia de la imagen.
 */
@Composable
internal fun EncuadreDeFondo(
    config: PanelConfig,
    onConfigChange: (PanelConfig) -> Unit,
    onListo: () -> Unit
) {
    val context = LocalContext.current

    // El encuadre se lleva aquí mientras se toca y solo se guarda al aceptar. Guardar en
    // cada movimiento del dedo significa serializar y escribir la configuración en disco
    // sesenta veces por segundo, y de ahí venían los tirones.
    var escala by remember { mutableFloatStateOf(config.backgroundScale) }
    var moverX by remember { mutableFloatStateOf(config.backgroundOffsetX) }
    var moverY by remember { mutableFloatStateOf(config.backgroundOffsetY) }

    // La vista previa tiene la forma de la pantalla del móvil: si fuera apaisada, lo que
    // se encuadra aquí no sería lo que luego se ve en el panel.
    val pantalla = LocalConfiguration.current
    val proporcion = pantalla.screenWidthDp.toFloat() / pantalla.screenHeightDp.toFloat()

    val imagen = produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        null,
        config.backgroundImage
    ) {
        value = withContext(Dispatchers.IO) {
            IconStore.userIcon(context, config.backgroundImage)?.let { fichero ->
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(fichero.absolutePath)
                        ?.asImageBitmap()
                }.getOrNull()
            }
        }
    }.value ?: return

    // En su propia ventana y no dentro de los ajustes: la pantalla de ajustes se
    // desplaza al arrastrar, y el dedo que quería mover la imagen movía la pantalla.
    AlertDialog(
        onDismissRequest = onListo,
        title = { Text(stringResource(R.string.appearance_background_frame_title)) },
        text = {
        Column {
            Text(
                stringResource(R.string.appearance_background_frame),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .wrapContentSize()
                    .height(320.dp)
                    .aspectRatio(proporcion)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .pointerInput(config.backgroundImage) {
                        detectTransformGestures { _, desplazamiento, zoom, _ ->
                            // Se puede empequeñecer por debajo de 1: es la única forma de
                            // ver entera una imagen que no tiene la forma de la pantalla.
                            escala = (escala * zoom).coerceIn(0.3f, 4f)
                            moverX = (moverX + desplazamiento.x / size.width)
                                .coerceIn(-1.5f, 1.5f)
                            moverY = (moverY + desplazamiento.y / size.height)
                                .coerceIn(-1.5f, 1.5f)
                        }
                    }
            ) {
                ImagenEncuadrada(imagen, escala, moverX, moverY)
            }
        }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfigChange(
                        config.copy(
                            backgroundScale = escala,
                            backgroundOffsetX = moverX,
                            backgroundOffsetY = moverY
                        )
                    )
                    onListo()
                }
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { escala = 1f; moverX = 0f; moverY = 0f }) {
                Text(stringResource(R.string.appearance_background_frame_reset))
            }
        }
    )
}

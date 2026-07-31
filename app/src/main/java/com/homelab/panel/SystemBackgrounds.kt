package com.homelab.panel

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** Un fondo de los que trae la aplicación. */
data class SystemBackground(val id: String, val labelRes: Int)

/**
 * Fondos propios del panel.
 *
 * Se dibujan, no se guardan como imagen. Un PNG a pantalla completa serían dos o tres
 * megas por fondo dentro del instalador, se vería borroso en las pantallas más densas y
 * habría que decidir su licencia; dibujados no ocupan nada, salen nítidos a cualquier
 * tamaño y son obra propia, que en un proyecto con licencia libre no es un detalle menor.
 *
 * En [PanelConfig.backgroundImage] se guardan como `sys:<id>`, para distinguirlos del
 * nombre de fichero de una imagen del usuario.
 */
object SystemBackgrounds {

    private const val PREFIJO = "sys:"

    val all = listOf(
        SystemBackground("arcos", R.string.background_sys_arcs),
        SystemBackground("atrio", R.string.background_sys_atrium),
        SystemBackground("celdas", R.string.background_sys_cells)
    )

    fun isSystem(valor: String): Boolean = valor.startsWith(PREFIJO)

    fun value(id: String): String = "$PREFIJO$id"

    fun idOf(valor: String): String = valor.removePrefix(PREFIJO)
}

/**
 * Pinta uno de los fondos propios.
 *
 * Todo va en fracciones del tamaño disponible, así que la misma composición vale para la
 * pantalla completa y para una miniatura de los ajustes.
 */
@Composable
fun SystemBackgroundCanvas(id: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        when (id) {
            "atrio" -> dibujarAtrio()
            "celdas" -> dibujarCeldas()
            else -> dibujarArcos()
        }
    }
}

/** El icono de la aplicación, agrandado y repetido, saliendo por abajo. */
private fun DrawScope.dibujarArcos() {
    val w = size.width
    val h = size.height

    drawRect(
        Brush.linearGradient(
            listOf(Color(0xFF1B3F80), Color(0xFF2F6BD8), Color(0xFF16305E)),
            start = Offset(0f, 0f),
            end = Offset(w * 0.4f, h)
        )
    )

    // Un halo suave arriba, para que la mitad de arriba no quede plana.
    drawCircle(
        Color.White.copy(alpha = 0.045f),
        radius = w * 0.52f,
        center = Offset(w * 0.5f, h * 0.3f)
    )

    // Arcos concéntricos apoyados en el borde inferior, del más grande al más pequeño.
    listOf(0.46f, 0.36f, 0.26f, 0.16f).forEach { proporcion ->
        val radio = w * proporcion
        val base = h * 0.94f - radio * 0.55f
        drawPath(
            arco(centroX = w / 2f, radio = radio, baseY = base, hasta = h),
            Color.White.copy(alpha = 0.13f),
            style = Stroke(width = w * 0.008f)
        )
    }
}

/** Un patio con su arco al fondo y el suelo en fuga. */
private fun DrawScope.dibujarAtrio() {
    val w = size.width
    val h = size.height

    drawRect(
        Brush.linearGradient(
            listOf(Color(0xFF0F2A55), Color(0xFF27579F), Color(0xFF0D2247)),
            start = Offset(0f, 0f),
            end = Offset(0f, h)
        )
    )

    // El suelo: un triángulo que se abre desde el punto de fuga hacia el espectador.
    val fugaY = h * 0.6f
    drawPath(
        Path().apply {
            moveTo(w / 2f, fugaY)
            lineTo(-w * 0.16f, h)
            lineTo(w * 1.16f, h)
            close()
        },
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
            startY = fugaY,
            endY = h
        )
    )

    // Las líneas de fuga del embaldosado.
    listOf(-0.08f, 0.18f, 0.5f, 0.82f, 1.08f).forEach { destino ->
        drawLine(
            Color.White.copy(alpha = 0.10f),
            start = Offset(w / 2f, fugaY),
            end = Offset(w * destino, h),
            strokeWidth = w * 0.006f
        )
    }

    // El arco del fondo, el mismo del icono.
    val radio = w * 0.25f
    drawPath(
        arco(centroX = w / 2f, radio = radio, baseY = h * 0.41f, hasta = fugaY),
        Color.White.copy(alpha = 0.2f),
        style = Stroke(width = w * 0.016f)
    )
    drawCircle(
        Color.White.copy(alpha = 0.07f),
        radius = radio * 0.8f,
        center = Offset(w / 2f, h * 0.41f)
    )
}

/** Una rejilla suave, que rima con las tarjetas del panel. */
private fun DrawScope.dibujarCeldas() {
    val w = size.width
    val h = size.height

    drawRect(
        Brush.linearGradient(
            listOf(Color(0xFF24508F), Color(0xFF2F6BD8), Color(0xFF1A3768)),
            start = Offset(w * 0.1f, 0f),
            end = Offset(w * 0.9f, h)
        )
    )

    val paso = w * 0.184f
    val lado = paso * 0.74f
    val margen = (paso - lado) / 2f
    val grosor = w * 0.0064f

    var y = -paso
    while (y < h + paso) {
        var x = -paso
        while (x < w + paso) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.10f),
                topLeft = Offset(x + margen, y + margen),
                size = Size(lado, lado),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(lado * 0.26f),
                style = Stroke(width = grosor)
            )
            x += paso
        }
        y += paso
    }

    // Dos manchas grandes para que la rejilla no se lea como un papel pintado uniforme.
    drawCircle(Color.White.copy(alpha = 0.05f), w * 0.48f, Offset(w * 0.16f, h * 0.18f))
    drawCircle(Color.Black.copy(alpha = 0.10f), w * 0.56f, Offset(w * 0.88f, h * 0.86f))
}

/**
 * Arco de medio punto apoyado en dos patas rectas, que es la forma del icono.
 *
 * @param baseY altura del eje del semicírculo; su punto más alto queda [radio] por encima.
 * @param hasta hasta dónde bajan las patas.
 */
private fun arco(centroX: Float, radio: Float, baseY: Float, hasta: Float): Path =
    Path().apply {
        moveTo(centroX - radio, hasta)
        lineTo(centroX - radio, baseY)
        arcTo(
            rect = Rect(centroX - radio, baseY - radio, centroX + radio, baseY + radio),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 180f,
            forceMoveTo = false
        )
        lineTo(centroX + radio, hasta)
    }

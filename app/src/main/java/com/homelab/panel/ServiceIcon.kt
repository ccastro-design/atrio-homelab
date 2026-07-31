package com.homelab.panel

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Icono de un servicio, resuelto por orden de preferencia:
 *
 *   1. la imagen que haya puesto el usuario,
 *   2. el icono que sirva el propio servicio,
 *   3. el icono de su categoría,
 *   4. su inicial, cuando no tiene categoría asignada.
 *
 * Se dibuja siempre sobre un fondo de color derivado del nombre. Sin ese fondo, los
 * iconos que sirven los servicios (unos con fondo blanco, otros casi negros) resultan
 * ilegibles en uno de los dos temas.
 */
@Composable
fun ServiceIcon(
    service: Service,
    url: String,
    size: Dp = 46.dp,
    modifier: Modifier = Modifier,
    /**
     * Las direcciones del servicio, para saber si el icono guardado sigue valiendo. Ver
     * [PanelConfig.iconOrigin].
     *
     * **Se deja en `null` donde no se conocen de verdad**, como en la vista previa de la
     * ficha: con un valor inventado, el icono guardado se daría por caducado y se borraría.
     */
    origen: String? = null
) {
    val oscuro = isSystemInDarkTheme()
    val (fondo, tinte) = chipColors(service.name.ifBlank { service.id }, oscuro)
    val imagen = rememberIconBitmap(service, url, origen)
    val propio = OwnIcons.drawableOf(service.category)

    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            // Los dibujos propios van sin cama de color. El fondo está para que los iconos
            // que sirve cada servicio —unos con fondo blanco, otros casi negros— se lean en
            // los dos temas; estos están dibujados a propósito, con su contorno y sus
            // colores, y con la cama detrás se veía un recuadro de color alrededor del
            // dibujo que no pintaba nada.
            .background(if (propio != null) Color.Transparent else fondo),
        contentAlignment = Alignment.Center
    ) {
        when {
            // La imagen llena el hueco entero. Antes se dibujaba al 64 %, para que el
            // fondo de color se viera alrededor, y el logotipo del servicio quedaba
            // diminuto dentro de un cuadro casi vacío. El fondo sigue debajo, que es lo
            // que hace legibles los iconos con transparencia.
            imagen != null -> Image(
                bitmap = imagen,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            // Un dibujo de los que trae la aplicación: llena el hueco entero, igual que una
            // imagen del usuario. Dejándole margen se veía más pequeño que los demás.
            propio != null -> Image(
                painter = androidx.compose.ui.res.painterResource(propio),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            service.category == "generic" -> Text(
                text = service.name.trim().take(1).uppercase(),
                color = tinte,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.SemiBold
            )

            else -> Icon(
                imageVector = Categories.icon(service.category),
                contentDescription = null,
                tint = tinte,
                modifier = Modifier.size(size * 0.56f)
            )
        }
    }
}

@Composable
private fun rememberIconBitmap(service: Service, url: String, origen: String?): ImageBitmap? {
    val context = LocalContext.current

    return produceState<ImageBitmap?>(
        initialValue = null,
        service.iconFile,
        service.useFavicon,
        service.isExample,
        url,
        origen
    ) {
        value = withContext(Dispatchers.IO) {
            val delUsuario = IconStore.userIcon(context, service.iconFile)
            if (delUsuario != null) return@withContext decodificar(delUsuario)

            // Los ejemplos no apuntan a ninguna máquina real: no hay a quién preguntar.
            if (!service.useFavicon || service.isExample || url.isBlank()) {
                return@withContext null
            }

            // Se muestra al momento el que ya estuviera guardado; si no hay, se pide.
            val guardado = IconStore.cachedFavicon(context, service.id, origen)
            if (guardado != null && guardado.length() > 0) return@withContext decodificar(guardado)

            // Con la contraseña del servicio, si la hay: los que piden autenticación
            // devuelven 401 hasta al favicon.
            val auth = IconStore.basicAuth(context, service.id)
            IconStore.ensureFavicon(context, service.id, url, origen, auth)?.let { decodificar(it) }
        }
    }.value
}

private fun decodificar(fichero: java.io.File): ImageBitmap? = runCatching {
    BitmapFactory.decodeFile(fichero.absolutePath)?.asImageBitmap()
}.getOrNull()

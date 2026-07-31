package com.homelab.panel

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Paleta propia del panel. */
object PanelColors {
    val Primary = Color(0xFF2F6BD8)
    val PrimaryDark = Color(0xFF5B93F0)
    val Accent = Color(0xFF00897B)

    val LightBackground = Color(0xFFF4F5F7)
    val LightCard = Color(0xFFFFFFFF)
    val LightText = Color(0xFF1F2328)
    val LightSubtitle = Color(0xFF6B7280)
    val LightBorder = Color(0xFFE3E6EA)

    // Negro puro: en pantallas AMOLED apaga los píxeles y gasta menos batería.
    val DarkBackground = Color(0xFF000000)
    val DarkCard = Color(0xFF14171C)
    val DarkText = Color(0xFFE8EAED)
    val DarkSubtitle = Color(0xFF9AA0A6)
    val DarkBorder = Color(0xFF262B33)

    /** Estado de un servicio. */
    val StatusUp = Color(0xFF1E9E52)
    val StatusDown = Color(0xFFD63A3A)
    val StatusChecking = Color(0xFF9AA0A6)

    // Los mismos, subidos de brillo para cuando el fondo es oscuro. Sin esto, con una
    // imagen de fondo y las tarjetas semitransparentes, el verde quedaba apagado y
    // costaba distinguir de un vistazo qué respondía y qué no.
    val StatusUpBright = Color(0xFF45D97D)
    val StatusDownBright = Color(0xFFFF6B6B)
    /** Aviso de conexión sin cifrar. */
    val Warning = Color(0xFFE0A030)
}

private val LightScheme = lightColorScheme(
    primary = PanelColors.Primary,
    // Sin fijarlo, Material pone un morado que no pega con el azul del panel.
    onPrimary = Color.White,
    secondary = PanelColors.Accent,
    background = PanelColors.LightBackground,
    surface = PanelColors.LightCard,
    onBackground = PanelColors.LightText,
    onSurface = PanelColors.LightText,
    outline = PanelColors.LightBorder,
    error = PanelColors.StatusDown,

    // Los diálogos y los menús no usan `surface`, sino estos otros huecos. Si se dejan
    // sin poner, Material cae a su paleta morada de fábrica y los diálogos salen con un
    // fondo lila que no pega con nada.
    surfaceVariant = Color(0xFFF0F2F5),
    onSurfaceVariant = PanelColors.LightSubtitle,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFF5F6F8),
    surfaceContainerHigh = Color(0xFFEFF1F4),
    surfaceContainerHighest = Color(0xFFE9EBEF)
)

private val DarkScheme = darkColorScheme(
    primary = PanelColors.PrimaryDark,
    // Azul muy oscuro sobre el azul claro de los botones: buen contraste y del mismo
    // tono. El morado que pone Material por omisión desentonaba.
    onPrimary = Color(0xFF062750),
    secondary = PanelColors.Accent,
    background = PanelColors.DarkBackground,
    surface = PanelColors.DarkCard,
    onBackground = PanelColors.DarkText,
    onSurface = PanelColors.DarkText,
    outline = PanelColors.DarkBorder,
    error = PanelColors.StatusDown,

    // Igual que en el tema claro: sin estos huecos, los diálogos salían con el fondo
    // claro de fábrica de Material aunque la aplicación estuviera en oscuro.
    surfaceVariant = Color(0xFF1B1F26),
    onSurfaceVariant = PanelColors.DarkSubtitle,
    surfaceContainerLowest = Color(0xFF0B0D10),
    surfaceContainerLow = Color(0xFF101318),
    surfaceContainer = Color(0xFF14171C),
    surfaceContainerHigh = Color(0xFF1B1F26),
    surfaceContainerHighest = Color(0xFF222831)
)

/**
 * Colores del tema **sin** los cambios del usuario.
 *
 * Los ajustes lo necesitan para ofrecer «volver al color de fábrica»: si preguntaran al
 * tema ya montado, el color de fábrica sería el que el usuario acaba de poner, y el botón
 * de volver atrás no llevaría a ninguna parte.
 */
fun esquemaBase(dark: Boolean): ColorScheme = if (dark) DarkScheme else LightScheme

/**
 * Convierte un «#RRGGBB» guardado en la configuración en un color, o null si no hay nada
 * puesto o lo que hay no se entiende. Vacío significa siempre «lo que diga el tema».
 */
fun colorDeAjuste(valor: String): Color? {
    val texto = valor.trim().removePrefix("#")
    if (texto.length != 6 && texto.length != 8) return null

    return runCatching {
        val numero = texto.toLong(16)
        if (texto.length == 6) Color(numero or 0xFF000000L) else Color(numero)
    }.getOrNull()
}

/** Un color a texto «#RRGGBB», que es como se guarda. */
fun ajusteDeColor(color: Color): String =
    "#%06X".format(color.value.shr(32).toLong() and 0xFFFFFF)

/**
 * Tema de la aplicación, con los colores que haya elegido el usuario encima.
 *
 * Los cambios se hacen sobre el esquema de Material y no pintando cada pantalla a mano:
 * así todo lo que ya usa `MaterialTheme.colorScheme` —tarjetas, diálogos, menús— cambia
 * solo y no hay forma de que una pantalla se quede con los colores viejos.
 */
@Composable
fun PanelTheme(
    config: PanelConfig? = null,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val base = if (dark) DarkScheme else LightScheme

    val esquema = if (config == null) {
        base
    } else {
        val fondo = colorDeAjuste(config.backgroundColorOf(dark))
        val texto = colorDeAjuste(config.textColorOf(dark))
        val barra = colorDeAjuste(config.bannerColorOf(dark))
        val sobreBarra = colorDeAjuste(config.bannerTextColorOf(dark))

        base.copy(
            primary = barra ?: base.primary,
            onPrimary = sobreBarra ?: base.onPrimary,
            background = fondo ?: base.background,
            onBackground = texto ?: base.onBackground,
            onSurface = texto ?: base.onSurface
        )
    }

    // Los dos colores que no tienen hueco en el esquema de Material viajan aparte, para
    // no ir pasándolos de composable en composable hasta la tarjeta.
    val propios = if (config == null) {
        ColoresDelPanel()
    } else {
        ColoresDelPanel(
            nombreDeServicio = colorDeAjuste(config.serviceNameColorOf(dark)),
            nombreDeGrupo = colorDeAjuste(config.groupNameColorOf(dark))
        )
    }

    CompositionLocalProvider(LocalColoresDelPanel provides propios) {
        MaterialTheme(colorScheme = esquema, content = content)
    }
}

/** Colores del panel que Material no contempla. Nulo significa el del tema. */
data class ColoresDelPanel(
    val nombreDeServicio: Color? = null,
    val nombreDeGrupo: Color? = null
)

val LocalColoresDelPanel = staticCompositionLocalOf { ColoresDelPanel() }

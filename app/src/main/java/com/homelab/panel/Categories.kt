package com.homelab.panel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.absoluteValue

/**
 * Iconos de categoría del panel.
 *
 * Se usan los iconos de Material, que son obra de Google con licencia Apache 2.0 y por
 * tanto libres para uso comercial. Son dibujos genéricos (una película, una carpeta, un
 * escudo), no logotipos de marcas: eso evita distribuir marcas registradas de terceros,
 * que es el problema que tenía la versión personal con los logotipos de Plex, Jellyfin,
 * Portainer y compañía.
 *
 * Además dan coherencia visual: todos comparten trazo y peso, cosa que no ocurre
 * mezclando logotipos de sitios distintos.
 */
data class Category(val id: String, val icon: ImageVector, val labelRes: Int)

object Categories {

    val all: List<Category> = listOf(
        Category("generic", Icons.Filled.Dashboard, R.string.cat_generic),
        Category("media", Icons.Filled.Movie, R.string.cat_media),
        Category("music", Icons.Filled.MusicNote, R.string.cat_music),
        Category("photos", Icons.Filled.PhotoLibrary, R.string.cat_photos),
        Category("containers", Icons.Filled.Widgets, R.string.cat_containers),
        Category("virtualization", Icons.Filled.Computer, R.string.cat_virtualization),
        Category("backup", Icons.Filled.Backup, R.string.cat_backup),
        Category("storage", Icons.Filled.Storage, R.string.cat_storage),
        Category("files", Icons.Filled.Folder, R.string.cat_files),
        Category("documents", Icons.Filled.Description, R.string.cat_documents),
        Category("notes", Icons.Filled.EditNote, R.string.cat_notes),
        Category("books", Icons.AutoMirrored.Filled.MenuBook, R.string.cat_books),
        Category("dns", Icons.Filled.Dns, R.string.cat_dns),
        Category("router", Icons.Filled.Router, R.string.cat_router),
        Category("network", Icons.Filled.SettingsEthernet, R.string.cat_network),
        Category("vpn", Icons.Filled.VpnKey, R.string.cat_vpn),
        Category("downloads", Icons.Filled.Download, R.string.cat_downloads),
        Category("monitoring", Icons.Filled.Insights, R.string.cat_monitoring),
        Category("security", Icons.Filled.Security, R.string.cat_security),
        Category("cameras", Icons.Filled.Videocam, R.string.cat_cameras),
        Category("home", Icons.Filled.House, R.string.cat_home),
        Category("power", Icons.Filled.Bolt, R.string.cat_power),
        Category("mail", Icons.Filled.Mail, R.string.cat_mail),
        Category("calendar", Icons.Filled.CalendarMonth, R.string.cat_calendar),
        Category("chat", Icons.AutoMirrored.Filled.Chat, R.string.cat_chat),
        Category("code", Icons.Filled.Code, R.string.cat_code),
        Category("terminal", Icons.Filled.Terminal, R.string.cat_terminal),
        Category("games", Icons.Filled.SportsEsports, R.string.cat_games),
        Category("web", Icons.Filled.Language, R.string.cat_web),
        Category("printer", Icons.Filled.Print, R.string.cat_printer),
        Category("ai", Icons.Filled.SmartToy, R.string.cat_ai),
        Category("feeds", Icons.Filled.RssFeed, R.string.cat_feeds)
    )

    private val porId = all.associateBy { it.id }

    fun icon(id: String): ImageVector = (porId[id] ?: porId.getValue("generic")).icon

    fun get(id: String): Category = porId[id] ?: porId.getValue("generic")
}

/**
 * Color de fondo del icono de un servicio, derivado de su nombre.
 *
 * Todos los iconos se dibujan sobre este fondo, vengan del juego de categorías, del
 * favicon del servicio o de una imagen del usuario. Resuelve un problema real: los
 * iconos que sirven los servicios son de todo tipo, unos con fondo blanco y otros casi
 * negros, y en modo oscuro la mitad se volvían ilegibles. Con un fondo propio siempre
 * hay contraste, y de paso el panel se ve ordenado.
 */
fun chipColors(seed: String, dark: Boolean): Pair<Color, Color> {
    val tono = (seed.lowercase().hashCode().absoluteValue % 360).toFloat()

    return if (dark) {
        hsl(tono, 0.35f, 0.22f) to hsl(tono, 0.70f, 0.72f)
    } else {
        hsl(tono, 0.55f, 0.92f) to hsl(tono, 0.65f, 0.36f)
    }
}

/** Color a partir de tono, saturación y luminosidad. */
private fun hsl(h: Float, s: Float, l: Float): Color {
    val c = (1f - kotlin.math.abs(2 * l - 1f)) * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f

    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    return Color(r + m, g + m, b + m)
}

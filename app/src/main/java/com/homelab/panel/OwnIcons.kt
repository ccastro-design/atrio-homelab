package com.homelab.panel

/**
 * Iconos dibujados para la aplicación, para servicios que no sirven ninguno propio.
 *
 * **Todos son obra del autor.** Nada de logotipos de terceros: son marcas registradas con
 * sus reglas de uso, distribuirlas dentro del instalador es motivo de rechazo en Play y en
 * F-Droid, y fue el problema que ya tuvo la versión personal del panel. Quien quiera el
 * logotipo exacto de un servicio lo tiene sin esto: la aplicación se lo pide al propio
 * servicio, igual que un navegador con las pestañas.
 *
 * Van en `drawable-nodpi` a propósito. Son WebP con transparencia, ya al tamaño que hacen
 * falta, y sin `nodpi` Android los daría por hechos para pantalla de densidad media y los
 * escalaría a lo bruto en cualquier móvil moderno.
 *
 * En [Service.category] se guardan con el prefijo `own:`, para no confundirlos con los
 * iconos de categoría de [Categories].
 */
data class OwnIcon(val id: String, val drawable: Int, val labelRes: Int)

object OwnIcons {

    private const val PREFIJO = "own:"

    val all: List<OwnIcon> = listOf(
        OwnIcon("servidor", R.drawable.atrio_servidor, R.string.atrio_icon_server),
        OwnIcon("servidor2", R.drawable.atrio_servidor_dos, R.string.atrio_icon_server_two),
        OwnIcon("router", R.drawable.atrio_router, R.string.atrio_icon_router),
        OwnIcon("barco", R.drawable.atrio_barco, R.string.atrio_icon_ship),
        OwnIcon("burro", R.drawable.atrio_burro, R.string.atrio_icon_donkey),
        OwnIcon("hispashare", R.drawable.atrio_hispashare, R.string.atrio_icon_donkey_star),
        OwnIcon("flecha", R.drawable.atrio_flecha, R.string.atrio_icon_arrow),
        OwnIcon("lupa", R.drawable.atrio_lupa, R.string.atrio_icon_search),
        OwnIcon("llama", R.drawable.atrio_llama, R.string.atrio_icon_flame)
    )

    val hayAlguno: Boolean get() = all.isNotEmpty()

    fun isOwn(valor: String): Boolean = valor.startsWith(PREFIJO)

    fun value(id: String): String = "$PREFIJO$id"

    fun idOf(valor: String): String = valor.removePrefix(PREFIJO)

    /** El dibujo que toca, o null si ese identificador ya no existe. */
    fun drawableOf(valor: String): Int? =
        if (!isOwn(valor)) null else all.firstOrNull { it.id == idOf(valor) }?.drawable
}

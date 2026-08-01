package com.homelab.panel

import android.content.Context

/**
 * Cómo viene la aplicación recién instalada.
 *
 * Empieza **vacía de servicios reales**: cuatro grupos con ejemplos dentro que no se
 * conectan a ninguna parte. Su dirección es literalmente una instrucción
 * («http://IP-DE-TU-SERVIDOR:9000») y al pulsarlos se abre su ficha explicada, así que
 * enseñan cómo se configura un servicio en vez de dar la sensación de que la aplicación
 * viene rota. Hay un botón para quitarlos todos de golpe.
 *
 * Los grupos están puestos por **máquina** y no por tipo de servicio, que es la idea que
 * más cuesta transmitir del panel: la dirección se define una vez por equipo y cada
 * servicio indica solo su puerto. Quien vea «servidor local» y «servidor externo» entiende
 * la estructura antes de leer nada.
 *
 * Las dos webs del último grupo **sí funcionan**: no son ejemplos.
 */
object DefaultConfig {

    /**
     * Aspecto de fábrica, tal como quedó tras probarlo en pantalla.
     *
     * Está aparte para que los ajustes puedan compararse con él: un color que coincide con
     * el de fábrica se enseña como «el del tema» y no como un código, porque el usuario no
     * ha elegido nada ahí.
     *
     * **Los colores y el fondo van juntos y no se pueden separar**: los nombres en blanco
     * se leen porque debajo está el fondo «Atrio», que es oscuro. Si algún día se cambia el
     * fondo de partida, hay que revisar estos colores en el mismo movimiento o el panel de
     * estreno saldrá ilegible.
     */
    val APARIENCIA = PanelConfig(
        backgroundImage = SystemBackgrounds.value("atrio"),
        cardOpacity = 60,
        showAddress = false,
        // El aviso de «Sin cifrar» viene apagado. En una red doméstica casi todo va por
        // HTTP plano, así que encendido pinta la marca en casi todas las tarjetas y deja de
        // avisar de nada. Quien quiera verlo lo enciende en Ajustes › Seguridad, y sigue
        // estando por servicio en cada ficha.
        warnCleartext = false,
        textColorDark = "#FFFFFF",
        serviceNameColor = "#FFFFFF",
        serviceNameColorDark = "#000000",
        groupNameColor = "#FFFFFF",
        groupNameColorDark = "#000000"
    )

    fun create(context: Context): PanelConfig = APARIENCIA.copy(
        groups = listOf(
            ServiceGroup(
                id = "example-docker",
                name = context.getString(R.string.example_group_docker),
                services = listOf(
                    ejemplo(
                        context,
                        id = "example-containers",
                        nombre = R.string.example_containers_name,
                        url = R.string.example_containers_url,
                        categoria = "containers",
                        escritorio = true
                    ),
                    ejemplo(
                        context,
                        id = "example-photos",
                        nombre = R.string.example_photos_name,
                        url = R.string.example_photos_url,
                        categoria = "photos"
                    ),
                    ejemplo(
                        context,
                        id = "example-media",
                        nombre = R.string.example_media_name,
                        url = R.string.example_media_url,
                        categoria = "media"
                    )
                )
            ),
            ServiceGroup(
                id = "example-local",
                name = context.getString(R.string.example_group_local),
                services = listOf(
                    ejemplo(
                        context,
                        id = "example-nas",
                        nombre = R.string.example_nas_name,
                        url = R.string.example_nas_url,
                        // Con los dibujos propios el estreno se ve como algo hecho a mano y
                        // no como una plantilla de iconos de sistema.
                        categoria = OwnIcons.value("servidor"),
                        escritorio = true
                    ),
                    ejemplo(
                        context,
                        id = "example-router",
                        nombre = R.string.example_router_name,
                        url = R.string.example_router_url,
                        categoria = OwnIcons.value("router")
                    )
                )
            ),
            ServiceGroup(
                id = "example-remote",
                name = context.getString(R.string.example_group_remote),
                services = listOf(
                    ejemplo(
                        context,
                        id = "example-vps",
                        nombre = R.string.example_vps_name,
                        url = R.string.example_vps_url,
                        categoria = OwnIcons.value("servidor2"),
                        escritorio = true
                    ),
                    ejemplo(
                        context,
                        id = "example-remote-backup",
                        nombre = R.string.example_remote_backup_name,
                        url = R.string.example_remote_backup_url,
                        categoria = "dns"
                    )
                )
            ),
            ServiceGroup(
                id = "atrio-webs",
                name = context.getString(R.string.example_group_web),
                services = webs(context)
            )
        )
    )

    /**
     * Las dos webs que vienen puestas. Estas **no** son ejemplos: son enlaces que funcionan,
     * así que se comprueban y se abren como cualquier otro servicio.
     *
     * **Se abren dentro de la aplicación, no en el navegador del móvil**, y por eso ninguna
     * lleva `openExternal`. No es un descuido: son lo único del panel de estreno que enseña
     * que aquí se puede navegar por webs igual que por los servicios propios, y en la
     * misma pestaña que mantiene la sesión. Quien las abre fuera nunca llega a descubrirlo.
     *
     * La del código fuente solo aparece cuando hay repositorio: un enlace de fábrica que
     * lleva a un 404 es peor que no tenerlo. Ver [Proyecto].
     */
    private fun webs(context: Context): List<Service> = buildList {
        if (Proyecto.CODIGO.isNotBlank()) {
            add(
                Service(
                    id = "atrio-source",
                    name = context.getString(R.string.web_source_name),
                    subtitle = context.getString(R.string.web_source_subtitle),
                    urlOwn = Proyecto.CODIGO,
                    category = "code"
                )
            )
        }

        add(
            Service(
                id = "atrio-author",
                name = context.getString(R.string.web_author_name),
                subtitle = context.getString(R.string.web_author_subtitle),
                urlOwn = context.getString(R.string.web_author_url),
                category = OwnIcons.value("llama"),
                // Sin esto se vería el icono que sirve la web y no el dibujo elegido: el
                // del servicio manda sobre los demás.
                useFavicon = false
            )
        )
    }

    /** Un servicio de ejemplo: no se comprueba, no se conecta y al tocarlo se explica. */
    private fun ejemplo(
        context: Context,
        id: String,
        nombre: Int,
        url: Int,
        categoria: String,
        escritorio: Boolean = false
    ) = Service(
        id = id,
        name = context.getString(nombre),
        subtitle = context.getString(R.string.example_hint),
        urlOwn = context.getString(url),
        category = categoria,
        desktopMode = escritorio,
        checkStatus = false,
        // Su dirección es una instrucción, no una máquina: no hay a quién pedirle un icono
        // y, sobre todo, pedirlo taparía el icono elegido, porque el del servicio manda
        // sobre el de la lista.
        useFavicon = false,
        // Y sin el aviso de «Sin cifrar»: todas estas direcciones son `http://` de mentira,
        // así que el aviso saldría en las siete tarjetas del estreno avisando de nada. En
        // los servicios que añada el usuario sigue activo, que es donde importa.
        warnCleartext = false,
        isExample = true
    )

    /** Quita los grupos de ejemplo, y los grupos que se queden vacíos al hacerlo. */
    fun withoutExamples(config: PanelConfig): PanelConfig {
        val grupos = config.groups
            .map { grupo -> grupo.copy(services = grupo.services.filter { !it.isExample }) }
            .filter { it.services.isNotEmpty() }

        return config.copy(groups = grupos)
    }
}

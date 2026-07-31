package com.homelab.panel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pantallas de la aplicación. */
private enum class Screen { PANEL, TABS, SETTINGS, DOWNLOADS, SCAN }

class MainActivity : FragmentActivity() {

    /** Enlace recibido de otra aplicación, pendiente de entregar. */
    private var pendingLink by mutableStateOf<String?>(null)
    private var unlocked by mutableStateOf(false)

    /** Pantalla y servicio pedidos desde el ordenador. Solo en depuración. */
    private var debugScreen: String? = null
    private var debugService by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingLink = extraerEnlace(intent)
        aplicarExtrasDeDepuracion(intent)

        val config = ConfigStore.load(this)

        // El salto del desbloqueo existe solo para poder verificar la aplicación desde el
        // ordenador, y solo en compilaciones de depuración: en la de publicación
        // BuildConfig.DEBUG es falso y la condición nunca se cumple.
        val saltarDesbloqueo = BuildConfig.DEBUG && intent?.getStringExtra("sinhuella") != null

        if (config.requireUnlock && !saltarDesbloqueo) {
            BiometricGate.ask(this) { autorizado ->
                if (autorizado) unlocked = true else finish()
            }
        } else {
            unlocked = true
        }

        setContent {
            // El tema se aplica dentro de App, que es quien tiene la configuración viva:
            // los colores que elija el usuario tienen que verse al instante, sin salir de
            // los ajustes ni reiniciar nada.
            if (unlocked) {
                App(
                    pendingLink = pendingLink,
                    onLinkConsumed = { pendingLink = null },
                    debugScreen = debugScreen,
                    debugService = debugService,
                    onDebugServiceConsumed = { debugService = null }
                )
            } else {
                PanelTheme { Surface(Modifier) {} }
            }
        }
    }

    /** Momento en que la aplicación dejó de verse, para saber cuánto ha estado fuera. */
    private var salidaEn = 0L

    override fun onStop() {
        super.onStop()
        salidaEn = android.os.SystemClock.elapsedRealtime()

        // Al cerrarla del todo, y si el usuario lo ha pedido, no se dejan sesiones vivas
        // en las pestañas.
        if (isFinishing && ConfigStore.load(this).clearSessionsOnExit) {
            WebSessions.clear()
        }
    }

    /**
     * Al volver a la aplicación se vuelve a pedir el desbloqueo si ha pasado el tiempo
     * configurado. Antes solo se pedía al arrancar en frío: quien cogiera el móvil
     * desbloqueado entraba directamente, con las pestañas abiertas y sus sesiones.
     */
    override fun onStart() {
        super.onStart()

        val config = ConfigStore.load(this)
        val minutos = config.relockMinutes

        if (!unlocked || !config.requireUnlock || minutos < 0 || salidaEn == 0L) return

        val fuera = android.os.SystemClock.elapsedRealtime() - salidaEn
        if (fuera < minutos * 60_000L) return

        unlocked = false
        BiometricGate.ask(this) { autorizado ->
            if (autorizado) unlocked = true else finish()
        }
    }

    /** La aplicación es de instancia única: los enlaces posteriores llegan por aquí. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extraerEnlace(intent)?.let { pendingLink = it }
        aplicarExtrasDeDepuracion(intent)
    }

    /**
     * Ayudas para probar la aplicación desde el ordenador. Este móvil no permite simular
     * toques por adb (HyperOS revierte en silencio el ajuste que lo autoriza), así que se
     * abren las pantallas por intent:
     *
     * Ojo al nombre del componente: la parte de la izquierda es el `applicationId` y la de
     * la derecha el paquete del código, que **no coinciden** desde que se fijó el
     * identificador definitivo.
     *
     *   adb shell am start -n io.github.ccastrodesign.atrio/com.homelab.panel.MainActivity -e pantalla ajustes
     *   adb shell am start -n io.github.ccastrodesign.atrio/com.homelab.panel.MainActivity -e pantalla descargas
     *   adb shell am start -n io.github.ccastrodesign.atrio/com.homelab.panel.MainActivity -e servicio <id>
     *   adb shell am start -n io.github.ccastrodesign.atrio/com.homelab.panel.MainActivity -e sembrar 1
     *
     * Solo funciona en compilaciones de depuración: en la de publicación el `if` es
     * siempre falso y el código se elimina al minificar.
     */
    private fun aplicarExtrasDeDepuracion(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return

        when (intent.getStringExtra("sembrar")) {
            null -> Unit

            // Solo los destinos de descarga, sin tocar el panel: sirve para recuperarlos
            // conservando su identificador, y con él la contraseña ya guardada.
            "destinos" -> {
                val actual = ConfigStore.load(this)
                val nasDelPanel = actual.servers.firstOrNull { it.hostHome == "192.168.1.254" }

                val nuevos = DebugSeed.create().downloadTargets
                    .filter { candidato -> actual.downloadTargets.none { it.id == candidato.id } }
                    .map { destino ->
                        if (nasDelPanel != null) destino.copy(serverId = nasDelPanel.id)
                        else destino
                    }

                ConfigStore.save(
                    this,
                    actual.copy(downloadTargets = actual.downloadTargets + nuevos)
                )
            }

            else -> ConfigStore.save(this, DebugSeed.create())
        }

        // Importa un panel desde un fichero del dispositivo, para poder probar la lectura
        // sin recorrer el selector de ficheros a mano:
        //   adb push config.yml /data/local/tmp/config.yml
        //   adb shell am start ... -e importar /data/local/tmp/config.yml
        intent.getStringExtra("importar")?.let { ruta ->
            val leido = runCatching { java.io.File(ruta).readText() }
                .getOrNull()
                ?.let { PanelImport.parse(it) }

            if (leido == null) {
                android.util.Log.w("Panel", "No se pudo importar $ruta")
            } else {
                android.util.Log.i(
                    "Panel",
                    "Importados ${leido.serviceCount} servicios, " +
                        "${leido.groups.size} grupos, ${leido.servers.size} servidores, " +
                        "${leido.logoPaths.size} iconos declarados"
                )

                val anterior = ConfigStore.load(this)
                ConfigStore.keepPrevious(this, anterior)

                // Se parte de la configuración actual, igual que hace la pantalla de
                // ajustes: construirla de cero se llevaba por delante los destinos de
                // descarga y los demás ajustes.
                var nueva = anterior.copy(
                    title = leido.title,
                    subtitle = leido.subtitle,
                    servers = leido.servers,
                    groups = leido.groups
                )

                // Con -e origen http://... se traen también los iconos del panel viejo.
                val origen = intent.getStringExtra("origen")
                if (origen != null) {
                    kotlinx.coroutines.runBlocking {
                        nueva = PanelImport.withOriginIcons(this@MainActivity, nueva, leido, origen)
                    }
                }

                ConfigStore.save(this, nueva)
            }
        }

        // Segundo fichero con las direcciones de fuera:
        //   adb shell am start ... -e importarfuera /data/local/tmp/vpn.yml
        intent.getStringExtra("importarfuera")?.let { ruta ->
            val leido = runCatching { java.io.File(ruta).readText() }
                .getOrNull()
                ?.let { PanelImport.parse(it) }

            if (leido != null) {
                val (nueva, cuenta) = PanelImport.applyAwayAddresses(ConfigStore.load(this), leido)
                android.util.Log.i("Panel", "Direcciones de fuera aplicadas a $cuenta servidores")
                ConfigStore.save(this, nueva.copy(profile = NetworkProfile.AUTO.name))
            }
        }

        intent.getStringExtra("pantalla")?.let { debugScreen = it }
        intent.getStringExtra("servicio")?.let { debugService = it }
    }

    /** Un enlace puede llegar como dirección pulsada o compartido desde otra aplicación. */
    private fun extraerEnlace(intent: Intent?): String? {
        if (intent == null) return null

        val delEsquema = intent.data?.toString()
        if (delEsquema != null && Links.detect(delEsquema) != null) return delEsquema

        if (intent.action == Intent.ACTION_SEND) {
            return Links.extract(intent.getStringExtra(Intent.EXTRA_TEXT))
        }

        return null
    }
}

@Composable
private fun App(
    pendingLink: String?,
    onLinkConsumed: () -> Unit,
    debugScreen: String? = null,
    debugService: String? = null,
    onDebugServiceConsumed: () -> Unit = {}
) {
    val context = LocalContext.current

    // La configuración vive aquí, por encima del tema: los colores que elija el usuario
    // se aplican a toda la aplicación en cuanto los toca.
    var config by remember { mutableStateOf(ConfigStore.load(context)) }

    PanelTheme(
        config = config,
        dark = when (config.theme) {
            "DARK" -> true
            "SYSTEM" -> androidx.compose.foundation.isSystemInDarkTheme()
            else -> false
        }
    ) {
        Surface(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Contenido(
                config = config,
                onConfigChange = { config = it },
                pendingLink = pendingLink,
                onLinkConsumed = onLinkConsumed,
                debugScreen = debugScreen,
                debugService = debugService,
                onDebugServiceConsumed = onDebugServiceConsumed
            )
        }
    }
}

@Composable
private fun Contenido(
    config: PanelConfig,
    onConfigChange: (PanelConfig) -> Unit,
    pendingLink: String?,
    onLinkConsumed: () -> Unit,
    debugScreen: String? = null,
    debugService: String? = null,
    onDebugServiceConsumed: () -> Unit = {}
) {
    val context = LocalContext.current

    // La marca de ventana segura se pone en cuanto se toca el interruptor, sin reiniciar:
    // oculta la miniatura en las aplicaciones recientes y prohíbe las capturas.
    LaunchedEffect(config.secureScreen) {
        (context as? android.app.Activity)?.window?.let { ventana ->
            if (config.secureScreen) {
                ventana.setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE
                )
            } else {
                ventana.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    var away by remember { mutableStateOf(false) }
    var pantalla by remember {
        mutableStateOf(
            when (debugScreen) {
                "ajustes" -> Screen.SETTINGS
                "descargas" -> Screen.DOWNLOADS
                "buscar" -> Screen.SCAN
                else -> Screen.PANEL
            }
        )
    }
    val status = rememberStatusMap()

    val pestanas = remember { mutableStateListOf<TabState>() }
    var pestanaActiva by remember { mutableIntStateOf(0) }

    var enviandoEnlace by remember { mutableStateOf(false) }
    var enlacesEntrantes by remember { mutableStateOf<List<String>>(emptyList()) }
    /**
     * De qué son los enlaces que acaban de llegar, cuando quien los capturó ya lo sabía.
     *
     * Hace falta porque la dirección de un enlace de descarga muchas veces no dice nada:
     * los buscadores de NZB sirven `…/api?t=get&id=123&apikey=…`, y deducir el tipo otra
     * vez a partir de eso da «no parece un enlace que se pueda enviar».
     */
    var tipoEntrante by remember { mutableStateOf<LinkKind?>(null) }
    /** Fichero que armó la propia web en el móvil, cuando no hay enlace que pasar. */
    var ficheroEntrante by remember { mutableStateOf<DownloadFile?>(null) }
    /** Cierto mientras pueden llegar más enlaces de la misma ráfaga. */
    var recogiendoEnlaces by remember { mutableStateOf(false) }
    var servicioEnEdicion by remember { mutableStateOf<Pair<Service, String>?>(null) }
    var refrescando by remember { mutableStateOf(false) }
    /** La presentación sale sola la primera vez, y a petición desde Ajustes. */
    var tutorial by remember { mutableStateOf(!config.tutorialSeen) }
    /** Ofrecer añadir la red de casa al terminar la presentación. */
    var preguntarRed by remember { mutableStateOf(false) }
    /**
     * Si la configuración no se pudo leer al arrancar, hay que decirlo. Perder el panel en
     * silencio es lo peor que puede hacer esta aplicación, y enterarse a las dos semanas,
     * peor todavía.
     */
    var avisoDeCarga by remember { mutableStateOf(ConfigStore.ultimoEstado) }
    /** Entrar en Ajustes directamente por Seguridad, no por su menú. */
    var irASeguridad by remember { mutableStateOf(false) }
    /** Red no reconocida en la que, aun así, algo responde en una dirección de casa. */
    var redDesconocida by remember { mutableStateOf<String?>(null) }
    /** Servicio que se ha tocado y no tiene ninguna dirección con la que abrirse. */
    var servicioSinDireccion by remember { mutableStateOf<Service?>(null) }

    val alcance = rememberCoroutineScope()

    fun guardar(nueva: PanelConfig) {
        onConfigChange(nueva)
        ConfigStore.save(context, nueva)
    }

    /**
     * Comprobación a mano, para no esperar a la automática.
     *
     * Respeta el interruptor general de Ajustes › Seguridad, igual que la automática: si
     * el usuario ha dicho que la aplicación no sondee su red, ningún gesto ni ningún
     * botón puede saltárselo. El botón de la barra ya no se enseña, pero el gesto de
     * tirar hacia abajo sí llegaba aquí.
     */
    /**
     * Aplica lo que ha decidido el resolutor: además de qué direcciones tocan, puede
     * traer una red que aprender o una por la que preguntar.
     */
    fun aplicar(veredicto: NetworkVerdict) {
        away = veredicto.away
        redDesconocida = veredicto.ask
    }

    fun refrescarEstado() {
        if (refrescando || !config.checkStatus) return
        refrescando = true
        alcance.launch {
            NetworkResolver.invalidate()
            aplicar(NetworkResolver.resolve(context, config))
            status.refresh(config, away)
            refrescando = false
        }
    }

    // Qué direcciones tocan: manda la WiFi en la que estemos, y si no se puede leer, que
    // responda o no la dirección de casa.
    LaunchedEffect(config.profile, config.servers, config.groups, config.homeSsids) {
        aplicar(NetworkResolver.resolve(context, config))
    }

    // El estado se refresca solo mientras el panel está en pantalla. Al salir, la
    // corrutina se cancela y la aplicación deja de usar la red.
    LaunchedEffect(config, away, pantalla) {
        if (!config.checkStatus || pantalla != Screen.PANEL) return@LaunchedEffect

        while (true) {
            status.refresh(config, away)
            delay(STATUS_REFRESH_SECONDS * 1000L)
        }
    }

    // Un enlace llegado de otra aplicación se atiende en cuanto la pantalla está lista.
    LaunchedEffect(pendingLink) {
        val enlace = pendingLink ?: return@LaunchedEffect
        onLinkConsumed()
        enlacesEntrantes = listOf(enlace)
        enviandoEnlace = true
    }

    // Una web que lanza varios enlaces los suelta en ráfaga, así que se espera un momento
    // a que dejen de llegar y se envían juntos: una sola pregunta y un solo aviso.
    //
    // Medio segundo y no más: el aviso de «enviando» sale desde el primer toque, pero
    // esta espera es tiempo en el que no pasa nada, y de nada sirve un aviso puntual si
    // luego se queda parado dos segundos.
    LaunchedEffect(enlacesEntrantes, recogiendoEnlaces) {
        if (!recogiendoEnlaces) return@LaunchedEffect

        delay(600)
        recogiendoEnlaces = false
    }

    fun abrirServicio(servicio: Service) {
        val destino = config.urlOf(servicio, away)

        // Sin dirección no hay nada que abrir, pero callarse tampoco vale: hasta ahora se
        // volvía sin más y tocar la tarjeta no hacía absolutamente nada.
        if (destino.isBlank()) {
            servicioSinDireccion = servicio
            return
        }

        val existente = pestanas.indexOfFirst { it.service.id == servicio.id }
        if (existente >= 0) {
            pestanaActiva = existente
            // Puede llevar cargada la dirección del otro perfil: hay que recargar, o se
            // queda mostrando algo que ya no responde.
            if (pestanas[existente].url != destino) pestanas[existente].load(destino)
        } else {
            pestanas.add(TabState(servicio, destino))
            pestanaActiva = pestanas.lastIndex
        }
        pantalla = Screen.TABS
    }

    // Apertura directa de un servicio desde el ordenador, para poder probarlo.
    LaunchedEffect(debugService) {
        val id = debugService ?: return@LaunchedEffect
        onDebugServiceConsumed()
        config.allServices.firstOrNull { it.id == id }?.let { abrirServicio(it) }
    }

    // Al cambiar de perfil, las pestañas abiertas conservan la dirección antigua.
    LaunchedEffect(away) {
        pestanas.forEach { pestana ->
            val destino = config.urlOf(pestana.service, away)
            if (destino.isNotBlank() && pestana.url != destino) pestana.load(destino)
        }
    }

    /**
     * Cambia una pestaña de sitio, arrastrándola por la tira.
     *
     * La pestaña que se estaba viendo sigue siendo la misma aunque cambie de posición: se
     * recoloca el índice de la activa, o al mover una de al lado se saltaría a otra
     * página sin haberla pedido.
     */
    fun moverPestana(desde: Int, hasta: Int) {
        if (desde !in pestanas.indices || hasta !in pestanas.indices || desde == hasta) return

        pestanas.add(hasta, pestanas.removeAt(desde))

        pestanaActiva = when {
            pestanaActiva == desde -> hasta
            desde < pestanaActiva && hasta >= pestanaActiva -> pestanaActiva - 1
            desde > pestanaActiva && hasta <= pestanaActiva -> pestanaActiva + 1
            else -> pestanaActiva
        }
    }

    fun cerrarPestana(indice: Int) {
        pestanas.getOrNull(indice)?.destroy()
        pestanas.removeAt(indice)
        pestanaActiva = pestanaActiva.coerceIn(0, maxOf(0, pestanas.lastIndex))
        if (pestanas.isEmpty()) pantalla = Screen.PANEL
    }

    // La presentación, encima de todo lo demás. Al cerrarla se vuelve a donde se estaba,
    // que al abrir la aplicación es el panel y al pedirla desde Ajustes, los ajustes.
    if (tutorial) {
        Tutorial(
            onFinish = {
                tutorial = false

                // Solo la primera vez: es el momento en que acaba de leer para qué sirve.
                // Volver a preguntarlo cada vez que alguien repase la presentación desde
                // Ajustes sería pesado.
                //
                // Se ofrece aunque la aplicación ya haya aprendido una red sola, porque
                // eso es una conjetura —«la primera WiFi donde algo respondió»— y esto es
                // que lo diga el usuario, que es lo único que vale de verdad.
                if (!config.tutorialSeen) {
                    guardar(config.copy(tutorialSeen = true))
                    preguntarRed = true
                }
            }
        )
        return
    }

    if (avisoDeCarga != ConfigStore.Estado.NORMAL) {
        val recuperada = avisoDeCarga == ConfigStore.Estado.RECUPERADA

        AlertDialog(
            onDismissRequest = {
                ConfigStore.avisoVisto()
                avisoDeCarga = ConfigStore.Estado.NORMAL
            },
            title = {
                Text(
                    stringResource(
                        if (recuperada) R.string.config_recovered_title
                        else R.string.config_lost_title
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (recuperada) R.string.config_recovered_body
                        else R.string.config_lost_body
                    ),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ConfigStore.avisoVisto()
                    avisoDeCarga = ConfigStore.Estado.NORMAL
                }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    if (preguntarRed) {
        AlertDialog(
            onDismissRequest = { preguntarRed = false },
            title = { Text(stringResource(R.string.ask_network_title)) },
            text = { Text(stringResource(R.string.ask_network_body), fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    preguntarRed = false
                    irASeguridad = true
                    pantalla = Screen.SETTINGS
                }) { Text(stringResource(R.string.ask_network_go)) }
            },
            dismissButton = {
                TextButton(onClick = { preguntarRed = false }) {
                    Text(stringResource(R.string.ask_network_later))
                }
            }
        )
    }

    // Se ha tocado un servicio que no tiene con qué abrirse. Se dice por qué y se ofrece
    // ir a su ficha, que es donde se arregla: allí se le elige otro servidor o se le pone
    // una dirección propia.
    servicioSinDireccion?.let { servicio ->
        val servidor = config.server(servicio.serverId)

        AlertDialog(
            onDismissRequest = { servicioSinDireccion = null },
            title = { Text(stringResource(R.string.no_address_title, servicio.name)) },
            text = {
                Text(
                    when (config.motivoSinDireccion(servicio, away)) {
                        NoSePuedeAbrir.SERVIDOR_BORRADO ->
                            stringResource(R.string.no_address_server_gone)

                        NoSePuedeAbrir.SERVIDOR_SIN_DIRECCION ->
                            stringResource(
                                R.string.no_address_server_empty,
                                servidor?.name.orEmpty()
                            )

                        else -> stringResource(R.string.no_address_none)
                    },
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val grupo = config.groups
                        .firstOrNull { g -> g.services.any { it.id == servicio.id } }
                    servicioSinDireccion = null
                    servicioEnEdicion = servicio to grupo?.id.orEmpty()
                }) { Text(stringResource(R.string.edit)) }
            },
            dismissButton = {
                TextButton(onClick = { servicioSinDireccion = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // La ficha de un servicio se abre también desde el panel, al tocar un ejemplo.
    servicioEnEdicion?.let { (servicio, grupoId) ->
        ServiceEditor(
            service = servicio,
            config = config,
            groupId = grupoId,
            onSave = { nuevo, grupoDestino ->
                guardar(ConfigOps.saveService(config, nuevo, grupoDestino))
                servicioEnEdicion = null
            },
            // Un servicio que aún no existe no se puede borrar: sin id, la ficha es un
            // alta y no debe enseñar la papelera.
            onDelete = if (servicio.id.isBlank()) {
                null
            } else {
                {
                    // Ni las credenciales ni la imagen propia están en la configuración,
                    // así que borrar el servicio no se las lleva: hay que decirlo aquí o
                    // se quedan en el móvil para siempre.
                    AutoLogin.forget(context, servicio.id)
                    IconStore.deleteServiceIcon(context, config, servicio)
                    guardar(ConfigOps.deleteService(config, servicio.id))
                    servicioEnEdicion = null
                }
            },
            onCancel = { servicioEnEdicion = null }
        )
        return
    }

    when (pantalla) {
        Screen.SETTINGS -> SettingsScreen(
            config = config,
            onConfigChange = { guardar(it) },
            onOpenDownloads = { pantalla = Screen.DOWNLOADS },
            onOpenScan = { pantalla = Screen.SCAN },
            onShowTutorial = { tutorial = true },
            empezarEnSeguridad = irASeguridad,
            onClose = {
                irASeguridad = false
                pantalla = Screen.PANEL
            }
        )

        Screen.DOWNLOADS -> DownloadsScreen(
            config = config,
            onConfigChange = { guardar(it) },
            onClose = { pantalla = Screen.SETTINGS }
        )

        Screen.SCAN -> ScanScreen(
            config = config,
            onConfigChange = { guardar(it) },
            onClose = { pantalla = Screen.PANEL }
        )

        Screen.TABS -> if (pestanas.isEmpty()) {
            pantalla = Screen.PANEL
        } else {
            TabbedBrowser(
                tabs = pestanas,
                activeIndex = pestanaActiva,
                config = config,
                away = away,
                onTrustCert = { info -> guardar(CertTrust.trust(config, info)) },
                onSelect = { pestanaActiva = it },
                onCloseTab = { cerrarPestana(it) },
                onMoveTab = { desde, hasta -> moverPestana(desde, hasta) },
                onSwitchToHome = {
                    NetworkResolver.invalidate()
                    guardar(config.copy(profile = NetworkProfile.HOME.name))
                    away = false
                },
                onBackToPanel = { pantalla = Screen.PANEL },
                onDownloadLink = { enlace, tipo ->
                    // El aviso se abre con el primer enlace, no cuando acaba la espera:
                    // pulsar y que no pase nada durante un segundo parece que no ha
                    // funcionado y lleva a pulsar otra vez.
                    enlacesEntrantes = (enlacesEntrantes + enlace).distinct()
                    // El primero que llegue con tipo conocido manda: en una ráfaga son
                    // todos de la misma página y del mismo tipo.
                    if (tipoEntrante == null) tipoEntrante = tipo
                    recogiendoEnlaces = true
                    enviandoEnlace = true
                },
                onDownloadFile = { fichero ->
                    // Aquí no hay ráfaga que esperar: el fichero ya está leído entero.
                    ficheroEntrante = fichero
                    enlacesEntrantes = emptyList()
                    tipoEntrante = fichero.kind
                    recogiendoEnlaces = false
                    enviandoEnlace = true
                },
                onSendLink = {
                    enlacesEntrantes = emptyList()
                    tipoEntrante = null
                    ficheroEntrante = null
                    recogiendoEnlaces = false
                    enviandoEnlace = true
                },
                onRememberCredentials = { servicio ->
                    guardar(
                        ConfigOps.updateService(config, servicio.id) { it.copy(autoLogin = true) }
                    )
                }
            )
        }

        Screen.PANEL -> Dashboard(
            config = config,
            away = away,
            status = status,
            refreshing = refrescando,
            onRefresh = { refrescarEstado() },
            onServiceClick = { servicio ->
                when {
                    // Un ejemplo no apunta a ninguna máquina: al tocarlo se abre su
                    // ficha, para que enseñe cómo se configura un servicio.
                    servicio.isExample -> {
                        val grupo = config.groups
                            .firstOrNull { g -> g.services.any { it.id == servicio.id } }
                        servicioEnEdicion = servicio to grupo?.id.orEmpty()
                    }

                    servicio.openExternal ->
                        abrirFuera(context, servicio, config.urlOf(servicio, away))

                    else -> abrirServicio(servicio)
                }
            },
            onProfileChange = { perfil ->
                NetworkResolver.invalidate()
                guardar(config.copy(profile = perfil.name))
            },
            onSettings = { pantalla = Screen.SETTINGS },
            onSendLink = {
                enlacesEntrantes = emptyList()
                recogiendoEnlaces = false
                enviandoEnlace = true
            },
            onAddService = {
                servicioEnEdicion = Service() to config.groups.firstOrNull()?.id.orEmpty()
            },
            onScanNetwork = { pantalla = Screen.SCAN },
            onRemoveExamples = { guardar(DefaultConfig.withoutExamples(config)) }
        )
    }

    if (enviandoEnlace) {
        SendLinkFlow(
            config = config,
            away = away,
            initialLinks = enlacesEntrantes,
            tipoEntrante = tipoEntrante,
            ficheroEntrante = ficheroEntrante,
            esperandoMas = recogiendoEnlaces,
            onConfigChange = { guardar(it) },
            onFinished = { mensaje ->
                enviandoEnlace = false
                enlacesEntrantes = emptyList()
                tipoEntrante = null
                ficheroEntrante = null
                Toast.makeText(context, mensaje, Toast.LENGTH_LONG).show()
            },
            onCancel = {
                enviandoEnlace = false
                enlacesEntrantes = emptyList()
                tipoEntrante = null
                ficheroEntrante = null
            }
        )
    }

    // Alguien responde en una dirección de casa, pero la red no es de las reconocidas.
    // Mientras no se conteste, la aplicación usa las direcciones de fuera: no se manda
    // nada a una máquina que podría no ser suya.
    redDesconocida?.let { wifi ->
        AlertDialog(
            onDismissRequest = {
                NetworkResolver.dismiss(wifi)
                redDesconocida = null
            },
            title = { Text(stringResource(R.string.network_unknown_title)) },
            text = { Text(stringResource(R.string.network_unknown_body, wifi), fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    guardar(config.copy(homeSsids = config.homeSsids + wifi))
                    NetworkResolver.invalidate()
                    redDesconocida = null
                }) { Text(stringResource(R.string.network_unknown_mine)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    NetworkResolver.dismiss(wifi)
                    redDesconocida = null
                }) { Text(stringResource(R.string.network_unknown_not_mine)) }
            }
        )
    }
}

/**
 * Abre un servicio fuera del panel.
 *
 * Si el usuario eligió una aplicación en la ficha, se abre esa por su nombre de paquete.
 * Sin aplicación elegida se lanza la dirección y decide el sistema, que en la práctica
 * significa el navegador: ver [ExternalApps] para por qué Android no puede deducir la
 * aplicación de un servicio autoalojado a partir de su dirección.
 */
private fun abrirFuera(context: Context, servicio: Service, url: String) {
    val elegida = servicio.externalPackage

    if (elegida.isNotBlank()) {
        if (ExternalApps.open(context, elegida, url)) return

        // Solo llega aquí si la desinstaló. Se avisa y se sigue por el navegador, que es
        // mejor que no hacer nada al pulsar la tarjeta.
        Toast.makeText(
            context,
            context.getString(R.string.error_app_gone, ExternalApps.label(context, elegida) ?: elegida),
            Toast.LENGTH_LONG
        ).show()
    }

    if (url.isBlank()) return

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(
            context,
            context.getString(R.string.error_no_app, servicio.name),
            Toast.LENGTH_LONG
        ).show()
    }
}

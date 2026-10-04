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
import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Screen { PANEL, TABS, SETTINGS, DOWNLOADS, SCAN }

class MainActivity : FragmentActivity() {

    /** Enlace recibido de otra aplicación, pendiente de entregar. */
    private var pendingLink by mutableStateOf<String?>(null)
    private var unlocked by mutableStateOf(false)

    /** Pantalla y servicio pedidos desde el ordenador. Solo en depuración. */
    private var debugScreen: String? = null
    private var debugService by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Antes que nada: lo que falle al restaurar la pantalla también debe quedar apuntado.
        Registro.iniciar(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingLink = extraerEnlace(intent)
        aplicarExtrasDeDepuracion(intent)

        val config = ConfigStore.load(this)

        val saltarDesbloqueo = Depuracion.saltarDesbloqueo(intent)

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
        Registro.traza("Ciclo") { "Deja de verse" }

        if (isFinishing && ConfigStore.load(this).clearSessionsOnExit) {
            WebSessions.clear(this)
        }
    }

    /**
     * Al volver a la aplicación se vuelve a pedir el desbloqueo si ha pasado el tiempo
     * configurado.
     */
    override fun onStart() {
        super.onStart()
        Registro.traza("Ciclo") {
            if (salidaEn == 0L) {
                "Se ve"
            } else {
                val segundos = (android.os.SystemClock.elapsedRealtime() - salidaEn) / 1000
                "Vuelve a verse tras ${segundos / 60} min ${segundos % 60} s"
            }
        }

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

    /** Ver [Depuracion]. Solo hace algo en compilaciones de depuración. */
    private fun aplicarExtrasDeDepuracion(intent: Intent?) {
        val pedido = Depuracion.aplicarExtras(this, intent) ?: return
        pedido.pantalla?.let { debugScreen = it }
        pedido.servicio?.let { debugService = it }
    }

    /** Un enlace puede llegar como dirección pulsada o compartido desde otra aplicación. */
    private fun extraerEnlace(intent: Intent?): String? {
        if (intent == null) return null

        val delEsquema = intent.data?.toString()
        if (delEsquema != null && Links.detect(delEsquema) != null) {
            Registro.traza("Envío") { "Pulsado fuera de Atrio: ${Registro.enlace(delEsquema)}" }
            return delEsquema
        }

        if (intent.action == Intent.ACTION_SEND) {
            val texto = intent.getStringExtra(Intent.EXTRA_TEXT)
            val compartido = Links.extract(texto)
            Registro.traza("Envío") {
                val todos = Links.extractAll(texto)
                "Compartido desde otra aplicación: ${todos.size} enlaces en ${texto?.length ?: 0} caracteres" +
                    (todos.firstOrNull()?.let { ", el primero ${Registro.enlace(it)}" } ?: "") +
                    "; se recoge ${if (compartido == null) "nada" else "${Links.extractAll(compartido).size}"}"
            }
            return compartido
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

    val red = remember { CasaOFuera() }
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

    val pestanas = remember { Pestanas() }

    val envio = remember { EnvioDeEnlaces() }
    var servicioEnEdicion by remember { mutableStateOf<Pair<Service, String>?>(null) }
    var refrescando by remember { mutableStateOf(false) }
    /** La presentación sale sola la primera vez, y a petición desde Ajustes. */
    var tutorial by remember { mutableStateOf(!config.tutorialSeen) }
    /** Ofrecer añadir la red de casa al terminar la presentación. */
    var preguntarRed by remember { mutableStateOf(false) }
    /** Si la configuración no se pudo leer al arrancar, hay que decirlo. */
    var avisoDeCarga by remember { mutableStateOf(ConfigStore.ultimoEstado) }
    /** Entrar en Ajustes directamente por Seguridad, no por su menú. */
    var irASeguridad by remember { mutableStateOf(false) }
    /** Servicio que se ha tocado y no tiene ninguna dirección con la que abrirse. */
    var servicioSinDireccion by remember { mutableStateOf<Service?>(null) }

    val alcance = rememberCoroutineScope()

    // Para las corrutinas que sobreviven a una recomposición: una lanzada con `config`
    // capturado seguiría viendo la configuración de cuando arrancó, y aplicaría, por
    // ejemplo, un veredicto AUTO por encima de un perfil que el usuario acaba de fijar.
    val configVigente by rememberUpdatedState(config)

    fun guardar(nueva: PanelConfig) {
        onConfigChange(nueva)
        ConfigStore.save(context, nueva)
    }

    /**
     * Comprobación a mano, para no esperar a la automática.
     *
     * Respeta el interruptor general de Ajustes › Seguridad, igual que la automática: si
     * el usuario ha dicho que la aplicación no sondee su red, ningún gesto ni ningún
     * botón puede saltárselo.
     */
    fun refrescarEstado() {
        if (refrescando || !config.checkStatus) return
        refrescando = true
        alcance.launch {
            NetworkResolver.invalidate()
            val veredicto = NetworkResolver.resolve(context, config)
            // Un veredicto provisional no cambia el perfil: recargaría las pestañas por
            // una decisión tomada con la red a medio levantar. El estado sí se refresca.
            if (!veredicto.provisional) red.aplicar(veredicto)
            status.refresh(config, red.fuera)
            refrescando = false
        }
    }

    DecidirCasaOFuera(config, red)

    // El estado se refresca solo mientras el panel está en pantalla. Al salir, la
    // corrutina se cancela y la aplicación deja de usar la red.
    LaunchedEffect(config, red.fuera, pantalla) {
        if (!config.checkStatus || pantalla != Screen.PANEL) return@LaunchedEffect

        while (true) {
            status.refresh(config, red.fuera)
            delay(STATUS_REFRESH_SECONDS * 1000L)
        }
    }

    // Un enlace llegado de otra aplicación se atiende en cuanto la pantalla está lista.
    LaunchedEffect(pendingLink) {
        val enlace = pendingLink ?: return@LaunchedEffect
        onLinkConsumed()
        envio.deOtraAplicacion(enlace)
    }

    // Una web que lanza varios enlaces los suelta en ráfaga, así que se espera un momento
    // a que dejen de llegar y se envían juntos: una sola pregunta y un solo aviso. 600 ms y
    // no más: esta espera es tiempo en el que no pasa nada.
    LaunchedEffect(envio.enlaces, envio.recogiendo) {
        if (!envio.recogiendo) return@LaunchedEffect

        delay(600)
        envio.rafagaTerminada()
    }

    fun abrirServicio(servicio: Service) {
        val destino = config.urlOf(servicio, red.fuera)

        // Sin dirección no hay nada que abrir, pero callarse tampoco vale.
        if (destino.isBlank()) {
            servicioSinDireccion = servicio
            return
        }

        pestanas.abrir(servicio, destino)
        pantalla = Screen.TABS
    }

    // Apertura directa de un servicio desde el ordenador, para poder probarlo.
    LaunchedEffect(debugService) {
        val id = debugService ?: return@LaunchedEffect
        onDebugServiceConsumed()
        config.allServices.firstOrNull { it.id == id }?.let { abrirServicio(it) }
    }

    // Al cambiar de perfil, las pestañas abiertas conservan la dirección antigua.
    LaunchedEffect(red.fuera) {
        pestanas.cambiarDirecciones { config.urlOf(it, red.fuera) }
    }

    fun cerrarPestana(indice: Int) {
        pestanas.cerrar(indice)
        if (pestanas.vacia) pantalla = Screen.PANEL
    }

    /** Abre la ficha de un servicio, dentro del grupo en que está. */
    fun editarServicio(servicio: Service) {
        val grupo = config.groups.firstOrNull { g -> g.services.any { it.id == servicio.id } }
        servicioEnEdicion = servicio to grupo?.id.orEmpty()
    }

    // La presentación, encima de todo lo demás. Al cerrarla se vuelve a donde se estaba,
    // que al abrir la aplicación es el panel y al pedirla desde Ajustes, los ajustes.
    if (tutorial) {
        Tutorial(
            onFinish = {
                tutorial = false

                // Solo la primera vez: es el momento en que acaba de leer para qué sirve.
                // Se ofrece aunque la aplicación ya haya aprendido una red sola, porque
                // eso es una conjetura y esto es que lo diga el usuario.
                if (!config.tutorialSeen) {
                    guardar(config.copy(tutorialSeen = true))
                    preguntarRed = true
                }
            }
        )
        return
    }

    if (avisoDeCarga != ConfigStore.Estado.NORMAL) {
        AvisoDeConfiguracion(
            recuperada = avisoDeCarga == ConfigStore.Estado.RECUPERADA,
            onVisto = {
                ConfigStore.avisoVisto()
                avisoDeCarga = ConfigStore.Estado.NORMAL
            }
        )
    }

    if (preguntarRed) {
        PreguntaRedDeCasa(
            onIr = {
                preguntarRed = false
                irASeguridad = true
                pantalla = Screen.SETTINGS
            },
            onLuego = { preguntarRed = false }
        )
    }

    servicioSinDireccion?.let { servicio ->
        AvisoSinDireccion(
            servicio = servicio,
            config = config,
            fuera = red.fuera,
            onEditar = {
                servicioSinDireccion = null
                editarServicio(servicio)
            },
            onCancelar = { servicioSinDireccion = null }
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

    // Las pantallas entran creciendo desde el centro y la anterior se va hacia el fondo.
    // El movimiento está definido una sola vez, en `Ui.kt`, para que este cambio y el de
    // las páginas de Ajustes se sientan igual.
    val (entrada, salida) = duracionesDeTransicion()

    AnimatedContent(
        targetState = pantalla,
        label = "pantalla",
        transitionSpec = { transicionDePantalla(entrada, salida) }
    ) { destino ->
    when (destino) {
        Screen.SETTINGS -> SettingsScreen(
            config = config,
            onConfigChange = { guardar(it) },
            onOpenDownloads = { pantalla = Screen.DOWNLOADS },
            onOpenScan = { pantalla = Screen.SCAN },
            onShowTutorial = { tutorial = true },
            onCerrarApp = { cerrarLaAplicacion(context) },
            onSessionsCleared = {
                // Borrar las cookies no echa de un sitio en el que ya estás dentro: la
                // página sigue cargada y funcionando.
                pestanas.cerrarTodas()
                if (pantalla == Screen.TABS) pantalla = Screen.PANEL
            },
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

        Screen.TABS -> if (pestanas.vacia) {
            pantalla = Screen.PANEL
        } else {
            TabbedBrowser(
                tabs = pestanas.lista,
                activeIndex = pestanas.activa,
                config = config,
                away = red.fuera,
                onTrustCert = { info -> guardar(CertTrust.trust(config, info)) },
                onSelect = { pestanas.activa = it },
                onCloseTab = { cerrarPestana(it) },
                onMoveTab = { desde, hasta -> pestanas.mover(desde, hasta) },
                onSwitchToHome = {
                    NetworkResolver.invalidate()
                    guardar(config.copy(profile = NetworkProfile.HOME.name))
                    red.fuera = false
                },
                onRetryTab = { pestana -> red.reintentar(pestana, context, alcance) { configVigente } },
                onBackToPanel = { pantalla = Screen.PANEL },
                onDownloadLink = { enlace, tipo -> envio.dePestana(enlace, tipo) },
                onDownloadFile = { fichero -> envio.deFichero(fichero) },
                onSendLink = { envio.abrirDesdePestana() },
                onRememberCredentials = { servicio ->
                    guardar(
                        ConfigOps.updateService(config, servicio.id) { it.copy(autoLogin = true) }
                    )
                    // Acaban de aparecer las credenciales que quizá faltaban para poder
                    // pedirle el icono a este servicio. Si estaba marcado como «no da
                    // icono», esa marca ya no vale: se olvida para que se reintente.
                    IconStore.olvidarMarcaSinIcono(context, servicio.id)
                }
            )
        }

        Screen.PANEL -> Dashboard(
            config = config,
            away = red.fuera,
            status = status,
            refreshing = refrescando,
            onRefresh = { refrescarEstado() },
            onServiceClick = { servicio ->
                when {
                    // Un ejemplo no apunta a ninguna máquina: al tocarlo se abre su
                    // ficha, para que enseñe cómo se configura un servicio.
                    servicio.isExample -> editarServicio(servicio)

                    servicio.openExternal ->
                        abrirFuera(context, servicio, config.urlOf(servicio, red.fuera))

                    else -> abrirServicio(servicio)
                }
            },
            onProfileChange = { perfil ->
                NetworkResolver.invalidate()
                guardar(config.copy(profile = perfil.name))
            },
            onSettings = { pantalla = Screen.SETTINGS },
            onSendLink = { envio.abrirDesdePanel() },
            onAddService = {
                servicioEnEdicion = Service() to config.groups.firstOrNull()?.id.orEmpty()
            },
            onScanNetwork = { pantalla = Screen.SCAN },
            onRemoveExamples = { guardar(DefaultConfig.withoutExamples(config)) }
        )
    }
    }

    if (envio.abierto) {
        SendLinkFlow(
            config = config,
            away = red.fuera,
            initialLinks = envio.enlaces,
            tipoEntrante = envio.tipo,
            ficheroEntrante = envio.fichero,
            esperandoMas = envio.recogiendo,
            onConfigChange = { guardar(it) },
            onFinished = { mensaje ->
                Registro.traza("Envío") { "Aviso al usuario: $mensaje" }
                envio.cerrar()
                Toast.makeText(context, mensaje, Toast.LENGTH_LONG).show()
            },
            onCancel = {
                Registro.traza("Envío") { "Cancelado por el usuario" }
                envio.cerrar()
            }
        )
    }

    red.redDesconocida?.let { wifi ->
        PreguntaRedDesconocida(
            wifi = wifi,
            onEsMia = {
                guardar(config.copy(homeSsids = config.homeSsids + wifi))
                NetworkResolver.invalidate()
                red.redDesconocida = null
            },
            onNoEsMia = {
                NetworkResolver.dismiss(wifi)
                red.redDesconocida = null
            }
        )
    }
}

/**
 * Cierra la aplicación **matando el proceso**, no solo la pantalla.
 *
 * Lo que hay que vaciar es la memoria del motor del navegador, donde quedan las credenciales
 * de los servicios que las piden por su propia ventana, y a esa memoria no llega ninguna
 * función de Android. Muere con el proceso y de ninguna otra forma.
 *
 * El retardo deja ver el aviso de que se ha borrado, y da margen a que termine de escribirse
 * la configuración.
 */
private fun cerrarLaAplicacion(context: Context) {
    val actividad = context as? android.app.Activity
    val cola = android.os.Handler(android.os.Looper.getMainLooper())
    cola.postDelayed({
        actividad?.finishAffinity()

        // El cierre y la muerte del proceso van en **dos mensajes separados**, no seguidos.
        // Entre uno y otro, Android ejecuta el ciclo de cierre de la pantalla, y ahí es donde
        // el sistema espera a que terminen las escrituras que quedaran encoladas y donde se
        // aplica «borrar las sesiones al salir».
        cola.postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 600)
    }, 1_200)
}

/**
 * Abre un servicio fuera del panel.
 *
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

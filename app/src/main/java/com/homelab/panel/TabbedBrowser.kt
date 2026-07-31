package com.homelab.panel

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import kotlin.math.abs
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Se anuncia como un Chrome de escritorio para que los paneles no den la versión móvil. */
private const val USER_AGENT_ESCRITORIO =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36"

private const val ESPERA_MAXIMA_MS = 12_000L

/**
 * Navegador con pestañas. Cada pestaña conserva su propio WebView, de modo que cambiar
 * entre ellas no recarga nada ni pierde la sesión.
 */
@Composable
fun TabbedBrowser(
    tabs: List<TabState>,
    activeIndex: Int,
    config: PanelConfig,
    away: Boolean,
    onTrustCert: (CertTrust.Info) -> Unit,
    onSelect: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onMoveTab: (Int, Int) -> Unit,
    onSwitchToHome: () -> Unit,
    onBackToPanel: () -> Unit,
    onDownloadLink: (String, LinkKind?) -> Unit,
    onDownloadFile: (DownloadFile) -> Unit,
    onSendLink: () -> Unit,
    onRememberCredentials: (Service) -> Unit
) {
    val activa = tabs.getOrNull(activeIndex) ?: return
    val context = LocalContext.current
    var certPendiente by remember { mutableStateOf<Pair<CertTrust.Info, SslErrorHandler>?>(null) }
    var autenticacion by remember { mutableStateOf<AvisoDeAutenticacion?>(null) }

    BackHandler {
        val vista = activa.view
        if (vista != null && vista.canGoBack()) vista.goBack() else onBackToPanel()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        BarraSuperior(
            titulo = activa.service.name,
            // Este botón va al panel. Retroceder dentro de la web es cosa del gesto o
            // del botón atrás del sistema.
            onInicio = onBackToPanel,
            onEnviarEnlace = onSendLink,
            onRecargar = { activa.load() },
            onCerrar = { onCloseTab(activeIndex) }
        )

        // Con una sola pestaña la tira no aporta nada y roba altura.
        if (tabs.size > 1) {
            TiraDePestanas(tabs, activeIndex, onSelect, onCloseTab, onMoveTab)
        }

        if (activa.loading) {
            LinearProgressIndicator(
                progress = { activa.progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Box(Modifier.weight(1f)) {
            ContenedorDeWebViews(
                tabs = tabs,
                activeIndex = activeIndex,
                config = config,
                away = away,
                onCertRequest = { info, handler -> certPendiente = info to handler },
                onDownloadLink = onDownloadLink,
                onDownloadFile = onDownloadFile,
                onHttpAuthRequest = { tab, host, realm, handler ->
                    autenticacion = AvisoDeAutenticacion(tab, host, realm, handler)
                }
            )

            activa.error?.let { mensaje ->
                PantallaDeError(
                    service = activa.service,
                    mensaje = mensaje,
                    mostrarAvisoDeFuera = away &&
                        !config.hasAwayAddress(activa.service) &&
                        isPrivateHost(activa.url),
                    away = away,
                    onReintentar = { activa.load() },
                    onModoCasa = onSwitchToHome
                )
            }
        }
    }

    // El tiempo límite se rearma en cada carga, no solo en la primera: si no, una
    // recarga colgada dejaba la pantalla en negro indefinidamente.
    LaunchedEffect(activa.key, activa.loadId) {
        delay(ESPERA_MAXIMA_MS)
        if (activa.loading && activa.error == null) {
            // Hay que llamar a stopLoading, no basta con dar el error por mostrado: un
            // servicio que no responde bloquea el repintado de toda la interfaz.
            activa.view?.stopLoading()
            activa.loading = false
            activa.error = TIMEOUT_MARCA
        }
    }

    certPendiente?.let { (info, handler) ->
        DialogoDeCertificado(
            info = info,
            onConfiar = {
                onTrustCert(info)
                handler.proceed()
                certPendiente = null
            },
            onRechazar = {
                handler.cancel()
                certPendiente = null
            }
        )
    }

    autenticacion?.let { aviso ->
        DialogoDeAutenticacion(
            aviso = aviso,
            onAceptar = { usuario, clave, recordar ->
                if (recordar) {
                    AutoLogin.save(context, aviso.tab.service.id, usuario, clave)
                    // Y se marca en la ficha del servicio, o la próxima vez que el usuario
                    // la abra y guarde, el interruptor apagado borraría lo recordado aquí.
                    onRememberCredentials(aviso.tab.service)
                }
                aviso.tab.httpAuthTried = true
                aviso.handler.proceed(usuario, clave)
                autenticacion = null
            },
            onCancelar = {
                aviso.handler.cancel()
                autenticacion = null
            }
        )
    }
}

/** Petición de usuario y contraseña que ha hecho un servidor a la pestaña. */
private data class AvisoDeAutenticacion(
    val tab: TabState,
    val host: String,
    val realm: String,
    val handler: HttpAuthHandler
)

/**
 * Pide usuario y contraseña cuando lo pide el servidor.
 *
 * Se dice qué máquina las pide, porque la petición puede venir de algo incrustado en la
 * página y no del servicio que el usuario cree estar mirando.
 */
@Composable
private fun DialogoDeAutenticacion(
    aviso: AvisoDeAutenticacion,
    onAceptar: (String, String, Boolean) -> Unit,
    onCancelar: () -> Unit
) {
    val context = LocalContext.current
    var usuario by remember { mutableStateOf(AutoLogin.savedUser(context, aviso.tab.service.id)) }
    var clave by remember { mutableStateOf("") }
    var recordar by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(stringResource(R.string.auth_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.auth_asked_by, aviso.host),
                    fontSize = 13.sp
                )
                if (aviso.realm.isNotBlank()) {
                    Text(
                        aviso.realm,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = usuario,
                    onValueChange = { usuario = it },
                    label = { Text(stringResource(R.string.field_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = clave,
                    onValueChange = { clave = it },
                    label = { Text(stringResource(R.string.field_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = recordar, onCheckedChange = { recordar = it })
                    Text(stringResource(R.string.auth_remember), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = usuario.isNotBlank() || clave.isNotBlank(),
                onClick = { onAceptar(usuario.trim(), clave, recordar) }
            ) {
                Text(stringResource(R.string.auth_enter))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancelar) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/**
 * Marca interna para distinguir el tiempo agotado de un error del propio WebView.
 *
 * Empieza por un carácter nulo a propósito, para que ningún mensaje de error de verdad
 * pueda coincidir con ella. Tiene que ir escrita como secuencia de escape y **nunca como
 * el carácter suelto**: un byte nulo dentro del fichero hace que las herramientas de
 * búsqueda lo tomen por binario y **se salten el fichero entero sin avisar**. Estuvo así y
 * dejaba este fichero fuera de cualquier búsqueda.
 */
private const val TIMEOUT_MARCA = "\u0000timeout"

@Composable
private fun BarraSuperior(
    titulo: String,
    onInicio: () -> Unit,
    onEnviarEnlace: () -> Unit,
    onRecargar: () -> Unit,
    onCerrar: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onInicio) {
            Icon(Icons.Default.Home, stringResource(R.string.home), tint = Color.White)
        }
        Text(
            titulo,
            color = Color.White,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // Aquí es donde hace falta: muchas webs de enlaces no los sirven como enlaces
        // pulsables, sino como texto en un cuadro para copiar. Se copia, se pulsa esto y
        // el envío ya sale relleno con lo copiado.
        IconButton(onClick = onEnviarEnlace) {
            Icon(
                Icons.Default.FileDownload,
                stringResource(R.string.send_link),
                tint = Color.White
            )
        }
        IconButton(onClick = onRecargar) {
            Icon(Icons.Default.Refresh, stringResource(R.string.reload), tint = Color.White)
        }
        IconButton(onClick = onCerrar) {
            Icon(Icons.Default.Close, stringResource(R.string.close_tab), tint = Color.White)
        }
    }
}

@Composable
private fun TiraDePestanas(
    tabs: List<TabState>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onMove: (Int, Int) -> Unit
) {
    val vibrar = LocalHapticFeedback.current

    // El orden se toca sobre la lista del momento, no sobre la capturada al empezar el
    // arrastre: si no, mover dos veces seguidas se lía.
    val actuales by rememberUpdatedState(tabs)

    var arrastrada by remember { mutableStateOf<String?>(null) }
    var desplazamiento by remember { mutableFloatStateOf(0f) }
    // Cada pestaña mide lo que mida su nombre, así que el salto no es fijo: se mide el
    // ancho real de cada una y se salta el de la vecina hacia la que se va.
    val anchos = remember { mutableStateMapOf<String, Int>() }

    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tabs.forEachIndexed { indice, tab ->
            val activa = indice == activeIndex
            val enMovimiento = arrastrada == tab.key

            // Cada pestaña se identifica por su clave, no por el sitio que ocupa. Sin
            // esto, al cambiarlas de orden Compose reutiliza el hueco para otra pestaña,
            // el detector de gestos se reinicia y el arrastre se suelta solo: había que
            // volver a pulsar para cada posición.
            key(tab.key) {
                Surface(
                    onClick = { onSelect(indice) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (activa) Color.White else Color.White.copy(alpha = 0.2f),
                    contentColor = if (activa) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier
                        .onSizeChanged { anchos[tab.key] = it.width }
                        .zIndex(if (enMovimiento) 1f else 0f)
                        .graphicsLayer { translationX = if (enMovimiento) desplazamiento else 0f }
                        .then(
                            if (enMovimiento) {
                                Modifier.shadow(8.dp, RoundedCornerShape(14.dp))
                            } else {
                                Modifier
                            }
                        )
                        .pointerInput(tab.key) {
                            val separacion = 6.dp.toPx()
    
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    arrastrada = tab.key
                                    desplazamiento = 0f
                                    vibrar.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragEnd = { arrastrada = null; desplazamiento = 0f },
                                onDragCancel = { arrastrada = null; desplazamiento = 0f },
                                onDrag = { _, delta ->
                                    desplazamiento += delta.x
    
                                    val desde = actuales.indexOfFirst { it.key == tab.key }
                                    if (desde < 0) return@detectDragGesturesAfterLongPress
    
                                    val hacia = if (desplazamiento > 0) desde + 1 else desde - 1
                                    val vecina = actuales.getOrNull(hacia)
                                        ?: return@detectDragGesturesAfterLongPress
    
                                    val paso = (anchos[vecina.key] ?: 0) + separacion
                                    if (paso <= 0f || abs(desplazamiento) < paso) {
                                        return@detectDragGesturesAfterLongPress
                                    }
    
                                    onMove(desde, hacia)
                                    desplazamiento -= if (desplazamiento > 0) paso else -paso
                                }
                            )
                        }
                ) {
                    Row(
                        Modifier.padding(
                            start = 14.dp,
                            end = if (activa) 4.dp else 14.dp,
                            top = 6.dp,
                            bottom = 6.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            tab.service.name,
                            fontSize = 13.sp,
                            fontWeight = if (activa) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1
                        )
    
                        // La X solo en la activa: estando en todas, al ir a cambiar de
                        // pestaña se cerraban sin querer. Tocar una inactiva siempre
                        // significa ir a ella.
                        if (activa) {
                            IconButton(onClick = { onClose(indice) }, modifier = Modifier.size(30.dp)) {
                                Icon(
                                    Icons.Default.Close,
                                    stringResource(R.string.close_tab),
                                    Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Un único contenedor nativo alberga el WebView de la pestaña activa. Los demás siguen
 * vivos fuera de él, con su página cargada.
 */
@Composable
private fun ContenedorDeWebViews(
    tabs: List<TabState>,
    activeIndex: Int,
    config: PanelConfig,
    away: Boolean,
    onCertRequest: (CertTrust.Info, SslErrorHandler) -> Unit,
    onDownloadLink: (String, LinkKind?) -> Unit,
    onDownloadFile: (DownloadFile) -> Unit,
    onHttpAuthRequest: (TabState, String, String, HttpAuthHandler) -> Unit
) {
    val context = LocalContext.current
    val contenedor = remember { FrameLayout(context) }

    // Solo el WebView activo está dentro del contenedor. Apilarlos todos y jugar con la
    // visibilidad no funciona: uno oculto no vuelve a pintarse al mostrarlo, y el orden
    // de dibujado hace que unos tapen a otros. Sacarlo y meterlo no pierde nada, porque
    // el objeto sigue vivo con su página.
    LaunchedEffect(tabs.size, activeIndex, tabs.map { it.key }) {
        tabs.forEach { tab ->
            if (tab.view == null) {
                tab.view = crearWebView(
                    context, tab, config, away, onCertRequest, onDownloadLink, onDownloadFile,
                    onHttpAuthRequest
                ).also {
                    it.loadUrl(tab.url)
                }
            }
        }

        contenedor.removeAllViews()

        tabs.getOrNull(activeIndex)?.view?.let { activa ->
            (activa.parent as? android.view.ViewGroup)?.removeView(activa)
            contenedor.addView(
                activa,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            activa.visibility = android.view.View.VISIBLE
        }
    }

    AndroidView(factory = { contenedor }, modifier = Modifier.fillMaxSize())
}

/**
 * Esquemas que no son páginas sino encargos para un programa de descargas. Se quedan en
 * los dos que el sistema también deja pulsar desde otras aplicaciones (ver el manifiesto).
 */
private val ESQUEMAS_DE_DESCARGA = setOf("ed2k", "magnet")

@SuppressLint("SetJavaScriptEnabled")
private fun crearWebView(
    context: android.content.Context,
    tab: TabState,
    config: PanelConfig,
    away: Boolean,
    onCertRequest: (CertTrust.Info, SslErrorHandler) -> Unit,
    onDownloadLink: (String, LinkKind?) -> Unit,
    onDownloadFile: (DownloadFile) -> Unit,
    onHttpAuthRequest: (TabState, String, String, HttpAuthHandler) -> Unit
): WebView = WebView(context).apply {

    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = true
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = false
        javaScriptCanOpenWindowsAutomatically = true
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // No se activa ningún guardado de contraseñas del navegador: de la pestaña solo
        // se conservan las cookies de sesión, nunca credenciales en claro.

        // Paneles pensados para pantalla grande: sin esto se apiñan o se cortan.
        if (tab.service.desktopMode) {
            userAgentString = USER_AGENT_ESCRITORIO
        }
    }

    // Algunos Android aplican su propio oscurecido a los WebView y dejan páginas
    // completamente en negro. Se desactiva para que cada web use sus colores.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
    }

    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    setBackgroundColor(android.graphics.Color.WHITE)

    // El propio WebView, para poder leerle un `blob:` desde el escuchador de descargas.
    val vistaDelBlob = this

    /**
     * Ficheros que la página ofrece descargar.
     *
     * Un WebView no descarga nada por su cuenta: si nadie atiende esto, pulsar un
     * `.torrent` **no hace absolutamente nada**, ni siquiera un aviso, porque el motor no
     * sabe pintar ese contenido y se calla. Un `.torrent` o un `.nzb` no hay que
     * descargarlos: se le pasa su dirección al programa de descargas, que se lo baja él.
     * Lo demás se deja al navegador del móvil, que para eso tiene gestor de descargas.
     */
    setDownloadListener { url, _, contentDisposition, mimeType, _ ->
        val cabecera = contentDisposition.orEmpty()

        // De qué es el fichero. La dirección es la peor pista de las tres: los buscadores
        // de NZB sirven todo por `…/api?t=get&id=123&apikey=…`, que no acaba en `.nzb` ni
        // se le parece, y lo mismo hacen los indexadores de torrent. **El tipo se averigua
        // aquí y viaja con el enlace**: quien lo reciba después ya no tiene de dónde
        // sacarlo, y antes lo rechazaba por no reconocerlo.
        val tipo = when {
            Links.detect(url) != null -> Links.detect(url)

            mimeType.equals("application/x-bittorrent", ignoreCase = true) -> LinkKind.TORRENT
            cabecera.contains(".torrent", ignoreCase = true) -> LinkKind.TORRENT

            mimeType.equals("application/x-nzb", ignoreCase = true) -> LinkKind.NZB
            mimeType.equals("application/nzb", ignoreCase = true) -> LinkKind.NZB
            cabecera.contains(".nzb", ignoreCase = true) -> LinkKind.NZB

            else -> null
        }

        if (tipo == LinkKind.TORRENT || tipo == LinkKind.NZB) {
            if (BuildConfig.DEBUG) Log.w("Panel", "Descarga capturada ($tipo): $url")

            // Un `blob:` no es una dirección: es un fichero que la propia página ha armado
            // aquí dentro, y fuera de esta pestaña no existe. Pasárselo al programa de
            // descargas no sirve de nada —lo rechaza sin poder decir por qué—, así que se
            // lee el contenido y se manda entero. Binsearch funciona así.
            if (url.startsWith("blob:", ignoreCase = true)) {
                leerFicheroDeLaPagina(vistaDelBlob, url, nombreDeFichero(cabecera, tipo), tipo) { fichero ->
                    if (fichero == null) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.send_file_read_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        onDownloadFile(fichero)
                    }
                }
            } else {
                onDownloadLink(url, tipo)
            }
        } else {
            val fuera = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fuera) }
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, nuevo: Int) {
            tab.progress = nuevo
            tab.loading = nuevo < 100
        }

        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                Log.w("Panel", "[${tab.service.name}] ${m.message()}")
            }
            return true
        }
    }

    webViewClient = object : WebViewClient() {

        /**
         * Un enlace de descarga no es una página.
         *
         * Sin esto, pulsar un `ed2k://` dentro de una pestaña dejaba la pestaña en un
         * error de esquema desconocido: el WebView intentaba cargarlo como si fuera una
         * dirección web. Ahora se entrega al servicio de descargas y la web se queda donde
         * estaba, que es lo que espera quien pulsa «Ejecutar enlaces» en una página.
         */
        override fun shouldOverrideUrlLoading(
            v: WebView?,
            peticion: WebResourceRequest?
        ): Boolean {
            val destino = peticion?.url ?: return false
            val tipo = Links.detect(destino.toString())

            // Un `.torrent` se reconoce por su dirección y se ataja aquí mismo. Dejar que
            // el WebView lo cargara para enterarse por el tipo de contenido costaba uno o
            // dos segundos de espera en los que no pasaba nada a la vista.
            if (tipo == LinkKind.TORRENT || tipo == LinkKind.NZB) {
                if (BuildConfig.DEBUG) Log.w("Panel", "Fichero de descarga: $destino")
                onDownloadLink(destino.toString(), tipo)
                return true
            }

            return if (destino.scheme?.lowercase() in ESQUEMAS_DE_DESCARGA) {
                // Solo los enlaces capturados, y solo en depuración: registrar cada
                // navegación sería llevar un diario de lo que visita el usuario.
                if (BuildConfig.DEBUG) Log.w("Panel", "Enlace capturado: $destino")
                onDownloadLink(destino.toString(), tipo)
                true
            } else {
                false
            }
        }

        override fun onPageStarted(v: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
            // Cuanto antes, mejor: hay que estar puesto **antes** de que la página cree
            // ningún fichero en memoria. Ver `GUARDIAN_DE_FICHEROS`.
            v?.evaluateJavascript(GUARDIAN_DE_FICHEROS, null)
        }

        override fun onPageFinished(v: WebView?, u: String?) {
            tab.loading = false

            // Otra vez al terminar: en una web hecha con React el guion de la página se
            // ejecuta después, y una recarga interna puede haberse llevado lo anterior.
            v?.evaluateJavascript(GUARDIAN_DE_FICHEROS, null)

            if (!tab.autoLoginTried && v != null) {
                // Se marca antes de intentarlo: un solo intento pase lo que pase.
                tab.autoLoginTried = true
                AutoLogin.attempt(
                    context = context,
                    view = v,
                    service = tab.service,
                    currentUrl = u,
                    allowedUrls = listOf(
                        config.urlOf(tab.service, away = false),
                        config.urlOf(tab.service, away = true)
                    ).filter { it.isNotBlank() }
                )
            }
        }

        override fun onReceivedError(
            v: WebView?,
            peticion: WebResourceRequest?,
            err: WebResourceError?
        ) {
            if (peticion?.isForMainFrame == true) {
                tab.error = err?.description?.toString().orEmpty()
                tab.loading = false
            }
        }

        /**
         * El servidor pide usuario y contraseña al propio navegador (autenticación
         * «básica»), no con un formulario dentro de la página.
         *
         * Un WebView no enseña ningún diálogo por su cuenta: si nadie atiende esto, la
         * pestaña se queda en un «401 Unauthorized» y parece que el servicio está roto,
         * mientras que en Chrome funciona porque él sí pregunta. Le pasa a Transmission,
         * a muchos routers y a cualquier cosa puesta detrás de un nginx con contraseña.
         *
         * Se prueban primero las credenciales guardadas del servicio, y solo si no valen
         * se le pregunta al usuario.
         */
        override fun onReceivedHttpAuthRequest(
            v: WebView?,
            handler: HttpAuthHandler?,
            host: String?,
            realm: String?
        ) {
            if (handler == null) return

            val usuario = AutoLogin.savedUser(context, tab.service.id)
            val clave = SecureStore.read(context, SecureStore.servicePasswordKey(tab.service.id))

            if (!tab.httpAuthTried && usuario.isNotBlank() && !clave.isNullOrEmpty()) {
                tab.httpAuthTried = true
                handler.proceed(usuario, clave)
                return
            }

            onHttpAuthRequest(tab, host.orEmpty(), realm.orEmpty(), handler)
        }

        override fun onReceivedSslError(v: WebView?, handler: SslErrorHandler?, e: SslError?) {
            if (handler == null || e == null) {
                handler?.cancel()
                return
            }

            val info = CertTrust.describe(e)

            when {
                info.host.isBlank() -> handler.cancel()
                CertTrust.isTrusted(config, info) -> handler.proceed()
                else -> onCertRequest(info, handler)
            }
        }
    }
}

@Composable
private fun DialogoDeCertificado(
    info: CertTrust.Info,
    onConfiar: () -> Unit,
    onRechazar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onRechazar,
        title = { Text(stringResource(R.string.cert_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.cert_body, info.host), fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))

                if (info.issuedTo.isNotBlank()) {
                    DatoDelCertificado(stringResource(R.string.cert_issued_to), info.issuedTo)
                }
                if (info.issuedBy.isNotBlank()) {
                    DatoDelCertificado(stringResource(R.string.cert_issued_by), info.issuedBy)
                }
                if (info.fingerprint.isNotBlank()) {
                    DatoDelCertificado(
                        stringResource(R.string.cert_fingerprint),
                        info.fingerprint,
                        monoespaciada = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfiar) { Text(stringResource(R.string.cert_trust)) }
        },
        dismissButton = {
            TextButton(onClick = onRechazar) { Text(stringResource(R.string.cert_reject)) }
        }
    )
}

@Composable
private fun DatoDelCertificado(etiqueta: String, valor: String, monoespaciada: Boolean = false) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(
            etiqueta,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            valor,
            fontSize = if (monoespaciada) 10.sp else 13.sp,
            fontFamily = if (monoespaciada) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
private fun PantallaDeError(
    service: Service,
    mensaje: String,
    mostrarAvisoDeFuera: Boolean,
    away: Boolean,
    onReintentar: () -> Unit,
    onModoCasa: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.error_title, service.name),
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (mensaje == TIMEOUT_MARCA) stringResource(R.string.error_timeout) else mensaje,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )

            if (mostrarAvisoDeFuera) {
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.error_no_away_address),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onReintentar) { Text(stringResource(R.string.retry)) }
                if (away) {
                    OutlinedButton(onClick = onModoCasa) {
                        Text(stringResource(R.string.error_try_home))
                    }
                }
            }
        }
    }
}


/**
 * Se queda con una copia de cada fichero que la página arma en su propia memoria.
 *
 * Hace falta porque una web moderna hace `URL.createObjectURL(blob)`, dispara la descarga
 * y **revoca la dirección en el acto**: para cuando la aplicación se entera de que hay una
 * descarga, ese `blob:` ya no existe. Medido con Binsearch, que fallaba a los 15 ms.
 *
 * Se envuelve `createObjectURL` para leer el contenido **en el momento en que se crea** y
 * se guarda en la propia página. Siempre se delega en la función original, así que la web
 * sigue comportándose igual. Solo se guarda lo que baja de 8 MB: un `.nzb` o un `.torrent`
 * no se acercan, y así una página con un vídeo en memoria no se lleva por delante la del
 * móvil.
 *
 * A propósito no se usa `addJavascriptInterface`, que sería más cómodo: eso deja un puente
 * permanente por el que cualquier web abierta en una pestaña podría llamar a la aplicación
 * cuando quisiera. Aquí no se abre nada: el contenido se queda en la página y se recoge
 * cuando hace falta.
 */
private val GUARDIAN_DE_FICHEROS = """
    (function () {
      try {
        if (window.__atrioPuesto) return;
        window.__atrioPuesto = true;
        window.__atrioFicheros = {};

        var original = URL.createObjectURL;
        URL.createObjectURL = function (obj) {
          var url = original.call(URL, obj);
          try {
            if (obj && typeof obj.size === 'number' && obj.size > 0 && obj.size < 8388608) {
              var lector = new FileReader();
              lector.onload = function () { window.__atrioFicheros[url] = lector.result; };
              lector.readAsDataURL(obj);
            }
          } catch (e) {}
          return url;
        };
      } catch (e) {}
    })();
""".trimIndent()

/**
 * Recoge el fichero que [GUARDIAN_DE_FICHEROS] guardó al crearse.
 *
 * Se reintenta unas cuantas veces porque el lector de la página es asíncrono y puede no
 * haber terminado cuando salta el aviso de descarga.
 */
private fun leerFicheroDeLaPagina(
    vista: WebView,
    blobUrl: String,
    nombre: String,
    tipo: LinkKind,
    intento: Int = 0,
    alTerminar: (DownloadFile?) -> Unit
) {
    val js = "(function(){try{return (window.__atrioFicheros||{})" +
        "[${JSONObject.quote(blobUrl)}]||'';}catch(e){return '';}})()"

    vista.evaluateJavascript(js) { crudo ->
        // `evaluateJavascript` devuelve el valor como literal JSON, con sus comillas y sus
        // escapes: hay que deshacerlo antes de poder usarlo.
        val dataUrl = runCatching { JSONArray("[$crudo]").getString(0) }.getOrNull()

        when {
            !dataUrl.isNullOrBlank() -> {
                val contenido = contenidoDeDataUrl(dataUrl)

                if (contenido.isNullOrBlank()) {
                    Log.w("Panel", "El fichero de la pagina no se pudo descodificar")
                    alTerminar(null)
                } else {
                    if (BuildConfig.DEBUG) {
                        Log.w("Panel", "Fichero leido de la pagina: $nombre (${contenido.length} caracteres)")
                    }
                    alTerminar(DownloadFile(name = nombre, content = contenido, kind = tipo))
                }
            }

            intento < 5 -> vista.postDelayed(
                { leerFicheroDeLaPagina(vista, blobUrl, nombre, tipo, intento + 1, alTerminar) },
                250
            )

            else -> {
                Log.w("Panel", "No se pudo leer el fichero que preparo la pagina")
                alTerminar(null)
            }
        }
    }
}

/** El contenido de un `data:…;base64,…`, ya en texto. */
private fun contenidoDeDataUrl(dataUrl: String): String? = runCatching {
    val base64 = dataUrl.substringAfter("base64,", "")
    if (base64.isBlank()) return@runCatching null
    String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT), Charsets.UTF_8)
}.getOrNull()

/** Nombre que anuncia la cabecera de descarga, o uno inventado con la extensión que toca. */
private fun nombreDeFichero(contentDisposition: String, tipo: LinkKind): String {
    val extension = if (tipo == LinkKind.TORRENT) ".torrent" else ".nzb"

    val declarado = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
        .find(contentDisposition)
        ?.groupValues?.get(1)
        ?.trim()
        ?.trim('"')
        ?.substringAfterLast('/')

    val limpio = declarado?.replace(Regex("""[\\/:*?"<>|]"""), "_").orEmpty()

    return when {
        limpio.isBlank() -> "atrio-${System.currentTimeMillis()}$extension"
        limpio.endsWith(extension, ignoreCase = true) -> limpio
        else -> "$limpio$extension"
    }
}

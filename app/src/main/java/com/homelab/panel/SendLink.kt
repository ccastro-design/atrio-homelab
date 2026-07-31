package com.homelab.panel

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Envío de enlaces a un servicio de descarga.
 *
 * La regla es no preguntar salvo que haga falta:
 *  - un solo destino capaz de aceptar ese tipo, va directo;
 *  - varios y con uno fijado en los ajustes, va al fijado;
 *  - varios sin fijar, se pregunta una vez y se puede recordar la elección;
 *  - ninguno capaz, se avisa con claridad.
 *
 * Se admiten varios enlaces de una vez, que es como los sirven las webs de enlaces: un
 * cuadro con uno por línea y un botón para lanzarlos todos.
 */
@Composable
fun SendLinkFlow(
    config: PanelConfig,
    away: Boolean,
    initialLinks: List<String>,
    /**
     * De qué son los enlaces recibidos, cuando quien los capturó ya lo sabía.
     *
     * La dirección de un enlace de descarga a menudo no delata nada: los buscadores de NZB
     * sirven `…/api?t=get&id=123&apikey=…` y los indexadores de torrent hacen lo mismo. El
     * tipo lo averigua el navegador por la cabecera del fichero y llega hasta aquí; sin
     * esto, volver a mirarlo por la extensión daba «eso no parece un enlace».
     */
    tipoEntrante: LinkKind? = null,
    /**
     * Fichero que armó la propia web dentro del móvil, cuando no hay enlace que pasar.
     *
     * Se sube entero en vez de mandar una dirección. Ver [DownloadFile].
     */
    ficheroEntrante: DownloadFile? = null,
    /** Cierto mientras pueden llegar más enlaces de la misma ráfaga: no se decide aún. */
    esperandoMas: Boolean = false,
    onConfigChange: (PanelConfig) -> Unit,
    onFinished: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val alcance = rememberCoroutineScope()

    var texto by remember {
        mutableStateOf(
            initialLinks.joinToString("\n").ifBlank { delPortapapeles(context) }
        )
    }
    var eligiendo by remember { mutableStateOf(false) }
    var enviando by remember { mutableStateOf<DownloadTarget?>(null) }

    /**
     * Los enlaces que llegan solos —de una pestaña o de otra aplicación— no enseñan el
     * cuadro de pegar. Antes aparecía y desaparecía en menos de un segundo, un parpadeo
     * que solo servía para dar la sensación de que algo se había ido mal.
     */
    var decidiendo by remember {
        mutableStateOf(initialLinks.isNotEmpty() || ficheroEntrante != null)
    }

    // Lo que llegó de fuera, para saber si sigue intacto. Si el usuario reescribe el
    // cuadro, lo que sabía el navegador ya no vale para lo que hay escrito ahora.
    val textoRecibido = remember { initialLinks.joinToString("\n") }
    val tipoValido = tipoEntrante?.takeIf { textoRecibido.isNotBlank() && texto == textoRecibido }

    val enlaces = if (tipoValido != null) {
        // Ya se sabe de qué son, así que no se filtra por la extensión de la dirección:
        // se cogen las líneas tal cual, que es lo que capturó el navegador.
        texto.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    } else {
        Links.extractAll(texto)
    }

    // Todos los del cuadro son del mismo tipo en la práctica; el primero manda a la hora
    // de decidir el destino.
    val tipo = ficheroEntrante?.kind ?: tipoValido ?: enlaces.firstOrNull()?.let { Links.detect(it) }
    val candidatos = tipo?.let { config.targetsFor(it) }.orEmpty()

    fun enviar(destino: DownloadTarget) {
        enviando = destino
        alcance.launch {
            val direccion = config.urlOf(destino, away)

            // Si lo que hay es un fichero, se sube entero: no hay enlaces que repartir ni
            // nada que contar, es uno y va de una vez.
            if (ficheroEntrante != null) {
                onFinished(
                    when (val hecho = LinkSender.sendFile(context, destino, direccion, ficheroEntrante)) {
                        is SendResult.Ok -> context.getString(R.string.send_ok, destino.name)
                        is SendResult.Failed -> hecho.message
                    }
                )
                return@launch
            }

            var enviados = 0
            var primerFallo: String? = null

            // De uno en uno: aMule sirve las peticiones en serie y con varias a la vez
            // pierde alguna sin decir nada.
            enlaces.forEach { enlace ->
                when (val resultado = LinkSender.send(context, destino, direccion, enlace)) {
                    is SendResult.Ok -> enviados++
                    is SendResult.Failed ->
                        if (primerFallo == null) primerFallo = resultado.message
                }
            }

            onFinished(
                when {
                    enviados == 0 ->
                        primerFallo ?: context.getString(R.string.send_invalid)

                    enviados < enlaces.size -> context.getString(
                        R.string.send_ok_partial, enviados, enlaces.size, destino.name
                    )

                    enlaces.size == 1 ->
                        context.getString(R.string.send_ok, destino.name)

                    else -> context.resources.getQuantityString(
                        R.plurals.send_ok_many, enviados, enviados, destino.name
                    )
                }
            )
        }
    }

    /** Decide a dónde va, preguntando solo si es imprescindible. */
    fun continuar() {
        val kind = tipo ?: return

        val preferido = config.preferredTarget(kind)
        when {
            preferido != null -> enviar(preferido)
            candidatos.size == 1 -> enviar(candidatos.first())
            candidatos.isEmpty() -> onFinished(
                context.getString(R.string.send_no_target, linkKindName(context, kind))
            )
            else -> eligiendo = true
        }
    }

    // Un enlace que llega desde otra aplicación o de una pestaña no debe hacer preguntas
    // si no hace falta.
    // Los enlaces de una ráfaga van llegando de uno en uno; sin esto solo contaba el
    // primero, porque el cuadro de texto se rellenaba una única vez al abrirse.
    LaunchedEffect(initialLinks) {
        if (initialLinks.isNotEmpty()) texto = initialLinks.joinToString("\n")
    }

    LaunchedEffect(initialLinks, ficheroEntrante, esperandoMas) {
        if (esperandoMas) return@LaunchedEffect

        val hayAlgo = initialLinks.isNotEmpty() || ficheroEntrante != null
        if (hayAlgo && tipo != null) continuar()
        // Si no se ha entendido lo que llegó, que se vea el cuadro para arreglarlo a mano.
        decidiendo = false
    }

    // Un solo aviso desde el primer toque hasta la respuesta: mientras se recogen los
    // enlaces de la ráfaga, mientras se decide el destino y mientras se entrega. Antes
    // había un hueco al principio y el aviso salía tarde y duraba un suspiro.
    if (esperandoMas || enviando != null || (decidiendo && !eligiendo)) {
        DialogoEnviando(if (ficheroEntrante != null) 1 else enlaces.size)
        return
    }

    if (eligiendo) {
        SelectorDeDestino(
            candidatos = candidatos,
            kind = tipo,
            config = config,
            away = away,
            onPick = { destino, recordar ->
                eligiendo = false
                if (recordar && tipo != null) {
                    onConfigChange(
                        config.copy(
                            linkRouting = config.linkRouting + (tipo.name to destino.id)
                        )
                    )
                }
                enviar(destino)
            },
            onCancel = onCancel
        )
        return
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.send_link)) },
        text = {
            Column {
                Text(stringResource(R.string.send_paste_hint), fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = it },
                    minLines = 3,
                    maxLines = 5,
                    isError = texto.isNotBlank() && tipo == null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (texto.isNotBlank() && tipo == null) {
                    Text(
                        stringResource(R.string.send_invalid),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
                tipo?.let {
                    Text(
                        // Con varios enlaces se dice cuántos: pegar un cuadro entero y no
                        // saber qué ha entendido la aplicación da mala espina.
                        if (enlaces.size > 1) {
                            pluralStringResource(R.plurals.send_count, enlaces.size, enlaces.size)
                        } else {
                            linkKindLabel(it)
                        },
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = tipo != null, onClick = { continuar() }) {
                Text(stringResource(R.string.send))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/**
 * Aviso mientras se entrega el enlace.
 *
 * Entregar tarda lo que tarde el servidor en contestar, y sin nada a la vista parecía que
 * el toque no había hecho efecto. No lleva botones a propósito: no hay nada que decidir y
 * se cierra solo al terminar.
 */
@Composable
private fun DialogoEnviando(cuantos: Int) {
    AlertDialog(
        onDismissRequest = { },
        // El texto no cambia en todo el proceso, aunque por dentro sean dos pasos
        // (decidir el destino y entregar). Cambiarlo a mitad se veía como si un aviso se
        // cerrara y se abriera otro. El destino ya lo dice el mensaje del final.
        title = { Text(stringResource(R.string.send_sending_generic)) },
        text = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(
                    if (cuantos > 1) {
                        pluralStringResource(R.plurals.send_count, cuantos, cuantos)
                    } else {
                        stringResource(R.string.send_sending_wait)
                    },
                    fontSize = 13.sp
                )
            }
        },
        confirmButton = { }
    )
}

@Composable
private fun SelectorDeDestino(
    candidatos: List<DownloadTarget>,
    kind: LinkKind?,
    config: PanelConfig,
    away: Boolean,
    onPick: (DownloadTarget, Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var recordar by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.send_choose_target)) },
        text = {
            Column {
                candidatos.forEach { destino ->
                    // Con recuadro y flecha: una lista de nombres sueltos no se reconoce
                    // como algo que se pueda pulsar. Y con el icono del servicio, que es
                    // como el usuario los distingue en el panel.
                    ClickableRow(
                        title = destino.name,
                        subtitle = destino.targetKind.displayName,
                        leading = { IconoDeDestino(config, destino, away) },
                        onClick = { onPick(destino, recordar) }
                    )
                }

                if (kind != null) {
                    Row(
                        Modifier.padding(top = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Checkbox(checked = recordar, onCheckedChange = { recordar = it })
                        Text(
                            stringResource(R.string.send_remember, linkKindLabel(kind)),
                            fontSize = 13.sp
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
 * Icono del destino: el mismo que enseña su servicio en el panel.
 *
 * Casi siempre el programa de descargas está también como servicio —es una web que el
 * usuario abre— así que se busca por dirección y se reutiliza su icono, incluido el que
 * haya puesto él a mano. Si no aparece por ninguna parte, queda el genérico de descargas.
 */
@Composable
private fun IconoDeDestino(config: PanelConfig, destino: DownloadTarget, away: Boolean) {
    val direccion = config.urlOf(destino, away)
    val servicio = remember(direccion, config.groups) {
        val clave = maquinaYPuerto(direccion)
        config.allServices.firstOrNull {
            clave != null && maquinaYPuerto(config.urlOf(it, away)) == clave
        }
    }

    if (servicio != null) {
        ServiceIcon(service = servicio, url = direccion, size = 34.dp)
    } else {
        Icon(
            Categories.icon("downloads"),
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
    }
}

/** «http://192.168.1.254:8085/» -> «192.168.1.254:8085», para emparejar direcciones. */
private fun maquinaYPuerto(url: String): String? = runCatching {
    val uri = java.net.URI(url.trim())
    val maquina = uri.host ?: return null
    "$maquina:${uri.port}"
}.getOrNull()

/**
 * Se ofrece lo que haya en el portapapeles: es de donde sale un enlace casi siempre.
 *
 * Se cogen **todos** los que haya copiados, no solo el primero: las webs de enlaces los
 * presentan en un cuadro de texto para seleccionarlos y copiarlos de una vez.
 */
private fun delPortapapeles(context: Context): String = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val texto = cm.primaryClip?.getItemAt(0)?.text?.toString()
    Links.extractAll(texto).joinToString("\n")
}.getOrDefault("")

private fun linkKindName(context: Context, kind: LinkKind): String = context.getString(
    when (kind) {
        LinkKind.ED2K -> R.string.link_ed2k
        LinkKind.MAGNET -> R.string.link_magnet
        LinkKind.TORRENT -> R.string.link_torrent
        LinkKind.NZB -> R.string.link_nzb
    }
)

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
 * Envío de enlaces a un servicio de descarga. La regla es no preguntar salvo que haga falta.
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
     * sirven `…/api?t=get&id=123&apikey=…` y los indexadores de torrent hacen lo mismo.
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
    /** Empieza la entrega de estos enlaces: lo que llegue desde ahora tiene que esperar. */
    onSending: (List<String>) -> Unit,
    /** [fallo]: no llegó todo, y el mensaje va en una ventana en vez de en un aviso breve. */
    onFinished: (mensaje: String, fallo: Boolean) -> Unit,
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

    /** El programa colgó y se espera a que vuelva: se dice por qué tarda. Ver [Reintentos]. */
    var reiniciando by remember { mutableStateOf(false) }

    /**
     * Los enlaces que llegan solos —de una pestaña o de otra aplicación— no enseñan el
     * cuadro de pegar.
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
        onSending(if (ficheroEntrante != null) emptyList() else enlaces)
        alcance.launch(AvisoDeEspera { reiniciando = true }) {
            val direccion = config.urlOf(destino, away)
            // Numerados para ver en el registro si dos envíos se solapan.
            val numero = if (BuildConfig.REGISTRO) numeroDeEnvio.incrementAndGet() else 0
            Registro.traza("Envío") {
                val que = if (ficheroEntrante != null) "un fichero" else "${enlaces.size} enlaces"
                "Envío $numero: $que a «${destino.name}» en $direccion (${if (away) "fuera" else "casa"})"
            }

            // Si lo que hay es un fichero, se sube entero: no hay enlaces que repartir ni
            // nada que contar, es uno y va de una vez.
            if (ficheroEntrante != null) {
                when (val hecho = LinkSender.sendFile(context, destino, direccion, ficheroEntrante)) {
                    is SendResult.Ok -> onFinished(context.getString(R.string.send_ok, destino.name), false)
                    is SendResult.Failed -> onFinished(hecho.message, true)
                }
                return@launch
            }

            var enviados = 0
            val motivos = mutableListOf<String>()

            // De uno en uno: aMule sirve las peticiones en serie y con varias a la vez
            // pierde alguna sin decir nada.
            enlaces.forEachIndexed { i, enlace ->
                val cual = "Envío $numero, ${i + 1} de ${enlaces.size}"
                Registro.traza("Envío") { "$cual: ${Registro.enlace(enlace)}" }
                val resultado = LinkSender.send(context, destino, direccion, enlace)
                reiniciando = false
                when (resultado) {
                    is SendResult.Ok -> {
                        enviados++
                        Registro.traza("Envío") { "$cual: entregado" }
                    }
                    is SendResult.Failed -> {
                        motivos += resultado.message
                        Registro.traza("Envío") { "$cual: FALLA: ${resultado.message}" }
                    }
                }
            }

            when (val fin = desenlace(enlaces.size, enviados, motivos)) {
                is Desenlace.Todo -> onFinished(
                    if (fin.cuantos == 1) {
                        context.getString(R.string.send_ok, destino.name)
                    } else {
                        context.resources.getQuantityString(R.plurals.send_ok_many, fin.cuantos, fin.cuantos, destino.name)
                    },
                    false
                )

                is Desenlace.Parte -> onFinished(
                    context.resources.getQuantityString(
                        R.plurals.send_ok_partial, fin.total - fin.enviados,
                        fin.enviados, fin.total, destino.name, fin.motivos.joinToString(" ")
                    ),
                    true
                )

                is Desenlace.Nada -> onFinished(fin.motivo ?: context.getString(R.string.send_invalid), true)
            }
        }
    }

    /** Decide a dónde va, preguntando solo si es imprescindible. */
    fun continuar() {
        // Uno a la vez: lo que llegue durante un envío espera su turno. Ver [EnvioDeEnlaces.enviando].
        if (enviando != null) return
        val kind = tipo ?: return

        val preferido = config.preferredTarget(kind)
        when {
            preferido != null -> enviar(preferido)
            candidatos.size == 1 -> enviar(candidatos.first())
            candidatos.isEmpty() -> onFinished(
                context.getString(R.string.send_no_target, linkKindName(context, kind)),
                true
            )
            else -> eligiendo = true
        }
    }

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
    // enlaces de la ráfaga, mientras se decide el destino y mientras se entrega.
    if (esperandoMas || enviando != null || (decidiendo && !eligiendo)) {
        DialogoEnviando(
            cuantos = if (ficheroEntrante != null) 1 else enlaces.size,
            reiniciandose = enviando?.name?.takeIf { reiniciando }
        )
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
 * Aviso mientras se entrega el enlace. No lleva botones a propósito: no hay nada que decidir
 * y se cierra solo al terminar.
 *
 * @param reiniciandose el programa que colgó y al que se espera, para decir por qué tarda:
 *   la espera puede llegar a medio minuto.
 */
@Composable
private fun DialogoEnviando(cuantos: Int, reiniciandose: String?) {
    AlertDialog(
        onDismissRequest = { },
        // El título no cambia en todo el proceso, aunque por dentro sean dos pasos
        // (decidir el destino y entregar). Cambiarlo a mitad se veía como si un aviso se
        // cerrara y se abriera otro. El destino ya lo dice el mensaje del final.
        title = { Text(stringResource(R.string.send_sending_generic)) },
        text = {
            Column {
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
                if (reiniciandose != null) {
                    Text(
                        stringResource(R.string.send_restarting, reiniciandose),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
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
 * Icono del destino: el mismo que enseña su servicio en el panel. Casi siempre el programa de
 * descargas está también como servicio, así que se busca por dirección y se reutiliza su icono.
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

private val numeroDeEnvio = java.util.concurrent.atomic.AtomicInteger()

/**
 * Cómo acabó un envío de enlaces, todavía sin palabras. Si no llegó todo, el motivo va en una
 * ventana: un aviso breve de Android se corta a dos líneas, y el motivo es lo que no se puede
 * perder.
 */
internal sealed interface Desenlace {
    data class Todo(val cuantos: Int) : Desenlace

    /** [motivos] sin repetir: varios enlaces suelen fallar por lo mismo. */
    data class Parte(val enviados: Int, val total: Int, val motivos: List<String>) : Desenlace

    /** [motivo] es el del primero que falló, o null si no había ningún enlace que mandar. */
    data class Nada(val motivo: String?) : Desenlace
}

internal fun desenlace(total: Int, enviados: Int, motivos: List<String>): Desenlace = when {
    enviados == 0 -> Desenlace.Nada(motivos.firstOrNull())
    enviados < total -> Desenlace.Parte(enviados, total, motivos.distinct())
    else -> Desenlace.Todo(total)
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
    val enlaces = Links.extractAll(texto)
    Registro.traza("Envío") { "Del portapapeles: ${enlaces.size} enlaces de ${texto?.length ?: 0} caracteres" }
    enlaces.joinToString("\n")
}.getOrElse {
    Registro.aviso("Envío", "No se pudo leer el portapapeles", it)
    ""
}

private fun linkKindName(context: Context, kind: LinkKind): String = context.getString(
    when (kind) {
        LinkKind.ED2K -> R.string.link_ed2k
        LinkKind.MAGNET -> R.string.link_magnet
        LinkKind.TORRENT -> R.string.link_torrent
        LinkKind.NZB -> R.string.link_nzb
    }
)

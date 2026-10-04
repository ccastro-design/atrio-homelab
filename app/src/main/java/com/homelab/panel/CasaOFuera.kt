package com.homelab.panel

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Si se está en casa o fuera, que es lo que decide qué dirección se usa para cada servicio.
 *
 * El porqué de decidirlo por el nombre de la WiFi, y no por la dirección que responda, está
 * en `Network.kt`: el peligro es entrar en la red de un amigo con las mismas direcciones y
 * mandarle las contraseñas.
 */
class CasaOFuera {

    var fuera by mutableStateOf(false)

    /** Red no reconocida en la que, aun así, algo responde en una dirección de casa. */
    var redDesconocida by mutableStateOf<String?>(null)

    /**
     * Aplica lo que ha decidido el resolutor: además de qué direcciones tocan, puede traer
     * una red por la que preguntar.
     */
    fun aplicar(veredicto: NetworkVerdict) {
        if (veredicto.away != fuera) {
            Registro.traza("Casa/fuera") { "Cambia a ${if (veredicto.away) "fuera" else "casa"}" }
        }
        fuera = veredicto.away
        redDesconocida = veredicto.ask
    }

    /**
     * Reintentar una pestaña también re-decide casa o fuera: si la decisión inicial fue la
     * equivocada, el reintento la corrige y las demás pestañas se recargan solas al cambiar
     * [fuera].
     *
     * La reacción visual va ANTES del sondeo, que puede tardar segundos: sin esto el botón
     * parecía muerto. `loading` hace además de guarda contra cada toque extra.
     *
     * @param configVigente la configuración de ese momento, no la de cuando se pulsó: el
     *   sondeo tarda y el usuario puede haber cambiado el perfil entretanto.
     */
    fun reintentar(
        pestana: TabState,
        context: Context,
        alcance: CoroutineScope,
        configVigente: () -> PanelConfig
    ) {
        if (pestana.loading) return
        pestana.error = null
        pestana.loading = true
        pestana.progress = 0
        val cargaPrevia = pestana.loadId

        alcance.launch {
            val perfilAlEmpezar = configVigente().profile
            NetworkResolver.invalidate()
            val veredicto = NetworkResolver.resolve(context, configVigente())
            // No se aplica un veredicto provisional (el reintento típico ocurre justo cuando
            // la red anda a medio levantar) ni uno calculado con un perfil que el usuario ha
            // cambiado mientras se sondeaba.
            if (!veredicto.provisional && configVigente().profile == perfilAlEmpezar) {
                aplicar(veredicto)
            }

            val destino = configVigente().urlOf(pestana.service, fuera)
            if (destino.isNotBlank() && destino != pestana.url) {
                pestana.load(destino)
            } else if (pestana.loadId == cargaPrevia) {
                pestana.load()
            }
            // Si loadId cambió, otro camino —el re-resolve de la vuelta a primer plano— ya
            // relanzó la carga: no se pisa.
        }
    }
}

/**
 * Decide casa o fuera al arrancar, cuando cambia algo de la configuración que lo afecta, y
 * cada vez que la aplicación vuelve a primer plano.
 */
@Composable
fun DecidirCasaOFuera(config: PanelConfig, red: CasaOFuera) {
    val context = LocalContext.current

    // Al volver la aplicación a primer plano hay que volver a decidir casa o fuera: la
    // decisión anterior puede ser de hace horas y de otra red. La primera ON_START llega
    // con la composición inicial, que ya resuelve por su cuenta, así que se descarta.
    var vueltasAPrimerPlano by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val ciclo = (context as? androidx.activity.ComponentActivity)?.lifecycle
        var primera = true
        val observador = androidx.lifecycle.LifecycleEventObserver { _, evento ->
            if (evento == androidx.lifecycle.Lifecycle.Event.ON_START) {
                if (primera) primera = false else vueltasAPrimerPlano++
            }
        }
        ciclo?.addObserver(observador)
        onDispose { ciclo?.removeObserver(observador) }
    }

    // Qué direcciones tocan: manda la WiFi en la que estemos, y si no se puede leer, que
    // responda o no la dirección de casa.
    LaunchedEffect(config.profile, config.servers, config.groups, config.homeSsids, vueltasAPrimerPlano) {
        if (vueltasAPrimerPlano > 0) NetworkResolver.invalidate()
        val veredicto = NetworkResolver.resolve(context, config)

        if (!veredicto.provisional) {
            red.aplicar(veredicto)
            return@LaunchedEffect
        }

        // Un «fuera» provisional NO se aplica todavía. Aplicarlo en el acto era cambiar
        // todas las pestañas a las direcciones de fuera para devolverlas a las de casa:
        // dos recargas y por el camino se pierde lo que hubiera en ellas. Se espera a que
        // la red termine de levantarse y la segunda decisión ya se aplica, sea la que sea.
        delay(3_000)
        NetworkResolver.invalidate()
        red.aplicar(NetworkResolver.resolve(context, config))
    }
}

package com.homelab.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Valores con los que sale la aplicación recién instalada.
 *
 * Son decisiones tomadas mirando el panel de estreno en el móvil, y de las que se cambian
 * sin querer: basta tocar un número en el modelo para que quien instale mañana vea algo
 * distinto de lo acordado, sin que falle nada ni se entere nadie.
 */
class EstadoDeFabricaTest {

    /**
     * El fondo viene **sin velo**. Los fondos que trae la aplicación ya son oscuros —el de
     * partida, «Atrio», es un degradado azul marino— y el texto blanco se lee encima sin
     * ayuda; atenuarlos solo los apagaba.
     */
    @Test
    fun `el fondo de fabrica no se atenua`() {
        assertEquals(0, PanelConfig().backgroundDim)
        assertEquals(0, DefaultConfig.APARIENCIA.backgroundDim)
    }

    /** El fondo de partida es «Atrio», del que dependen los colores de texto de fábrica. */
    @Test
    fun `el fondo de partida es atrio`() {
        assertEquals(SystemBackgrounds.value("atrio"), DefaultConfig.APARIENCIA.backgroundImage)
    }
}

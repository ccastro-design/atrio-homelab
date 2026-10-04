package com.homelab.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cuándo avisa la sección «Tu red local» de que sus redes no se usan. Ver [redSinUsar]. */
class RedSinUsarTest {

    /** El 04/10/2026: la red apuntada y sin el permiso, y nada lo decía. */
    @Test
    fun `sin permiso las redes apuntadas no se usan`() {
        assertEquals(RedSinUsar.SIN_PERMISO, redSinUsar(hayRedes = true, permiso = false, ubicacion = true))
        assertEquals(RedSinUsar.SIN_PERMISO, redSinUsar(hayRedes = true, permiso = false, ubicacion = false))
    }

    @Test
    fun `con el permiso y la ubicacion apagada tampoco`() {
        assertEquals(RedSinUsar.SIN_UBICACION, redSinUsar(hayRedes = true, permiso = true, ubicacion = false))
    }

    @Test
    fun `con los dos se usan y no hay aviso`() {
        assertNull(redSinUsar(hayRedes = true, permiso = true, ubicacion = true))
    }

    /** Sin ninguna red apuntada no hay nada que deje de usarse. */
    @Test
    fun `sin redes apuntadas no se avisa`() {
        assertNull(redSinUsar(hayRedes = false, permiso = false, ubicacion = false))
    }
}

package com.geanalves.tvhome

import java.util.Calendar

/** Cores da tela: claro, ou escuro azul-noite com estrelas (modo escuro). */
object Cores {
    var escuro = false
        private set

    /** Noite = das 18h às 6h. */
    fun eNoite(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 18 || h < 6
    }

    /** Mude para true para escurecer a tela à noite (18h às 6h). */
    private const val ESCURO_A_NOITE = false

    /** Modo escuro ligado pelo botão da lua (fica salvo). */
    var manual = false

    /** Atualiza o modo conforme o botão e a hora. Devolve true se mudou. */
    fun atualizar(): Boolean {
        val novo = manual || (ESCURO_A_NOITE && eNoite())
        val mudou = novo != escuro
        escuro = novo
        return mudou
    }

    // Texto principal e secundário
    val tinta: Int get() = if (escuro) 0xFFF2F5FF.toInt() else 0xFF1C1C1E.toInt()
    val cinza: Int get() = if (escuro) 0xFF9AA6C0.toInt() else 0xFF7C7C82.toInt()

    /** Cor do texto/ícone em cima de algo pintado com [tinta] (botão selecionado). */
    val papel: Int get() = if (escuro) 0xFF0E1626.toInt() else 0xFFFFFFFF.toInt()

    // Fundo
    val fundoTopo: Int get() = if (escuro) 0xFF1B2A45.toInt() else 0xFFFFFFFF.toInt()
    val fundoBase: Int get() = if (escuro) 0xFF060A14.toInt() else 0xFFF1F1F4.toInt()
    val veu: IntArray
        get() = if (escuro) {
            intArrayOf(0xB3121C30.toInt(), 0x66121C30, 0xE6060A14.toInt())
        } else {
            intArrayOf(0x99F7F4F0.toInt(), 0x40F7F4F0, 0xD9F2EEEA.toInt())
        }

    // Peças
    val barra: Int get() = if (escuro) 0x8C080E1C.toInt() else 0xFFFFFFFF.toInt()
    val botao: Int get() = if (escuro) 0x33FFFFFF else 0xCCFFFFFF.toInt()
    val cartao: Int get() = if (escuro) 0xFF1C2740.toInt() else 0xFFE5E5EA.toInt()
    val cartaoApp: Int get() = if (escuro) 0xFF1C2740.toInt() else 0xFFFFFFFF.toInt()
    val sutil: Int get() = if (escuro) 0x1FFFFFFF else 0x1A000000

    /** Fundo das janelas e da barrinha do Wi-Fi (branco no claro). */
    val janela: Int get() = if (escuro) 0xFF1A2438.toInt() else 0xFFFFFFFF.toInt()

    /** Botões dentro das janelas. */
    val campo: Int get() = if (escuro) 0xFF27334D.toInt() else 0xFFF2F2F7.toInt()
}

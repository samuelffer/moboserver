package com.moboserver.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Endereços: o app não pode oferecer 100.64/10 (CGNAT) como IP público nem
 * apresentar UPnP/STUN como acesso confirmado.
 */
class AddressClassifierTest {

    @Test
    fun cgnat_e_reconhecido_na_faixa_100_64_0_0_10() {
        assertTrue(AddressClassifier.isCgnat("100.64.0.1"))
        assertTrue(AddressClassifier.isCgnat("100.127.255.254"))
        assertFalse(AddressClassifier.isCgnat("100.63.0.1"))
        assertFalse(AddressClassifier.isCgnat("100.128.0.1"))
        assertFalse(AddressClassifier.isCgnat("192.168.0.10"))
        assertFalse(AddressClassifier.isCgnat(""))
        assertFalse(AddressClassifier.isCgnat(null))
    }

    @Test
    fun ipv4_invalido_nao_e_aceito() {
        assertTrue(AddressClassifier.parseIpv4("192.168.0.1") != null)
        assertFalse(AddressClassifier.isCgnat("100.64.2.256"))
        assertFalse(AddressClassifier.isCgnat("100.64.2"))
        assertFalse(AddressClassifier.isCgnat("100..2.1"))
        assertFalse(AddressClassifier.isCgnat("100.64.2.-1"))
        assertEquals(null, AddressClassifier.parseIpv4("300.1.1.1"))
    }

    @Test
    fun cgnat_no_endereco_wan_do_roteador_e_reconhecido() {
        // LAN privada + WAN compartilhada: é CGNAT, e o app precisa avisar.
        val choice = AddressClassifier.classify(
            lanIp = "192.168.1.106",
            wanIp = "100.64.2.77",
            publicIp = "45.160.85.157",
            port = 19132,
            verified = null,
        )
        assertTrue(choice.cgnat)
        assertTrue(choice.status.contains("CGNAT"))
        // O IP observado pode ser mostrado, mas como não verificado.
        assertEquals("observado", choice.kind)
    }

    @Test
    fun cgnat_nunca_vira_endereco_para_amigos() {
        val choice = AddressClassifier.classify(
            lanIp = "100.64.2.77",
            wanIp = "100.64.2.77",
            publicIp = "100.64.2.77",
            port = 19132,
            verified = true,
        )
        assertEquals("local", choice.kind)
        assertEquals("100.64.2.77:19132", choice.share)
        assertTrue(choice.cgnat)
        assertTrue(choice.status.contains("CGNAT"))
    }

    @Test
    fun ip_observado_por_stun_nao_e_acesso_verificado() {
        val choice = AddressClassifier.classify(
            lanIp = "192.168.0.10",
            wanIp = "192.168.0.1",
            publicIp = "45.160.85.157",
            port = 19132,
            verified = null,
        )
        assertEquals("observado", choice.kind)
        assertEquals("45.160.85.157:19132", choice.share)
        assertEquals("Acesso não verificado", choice.status)
    }

    @Test
    fun teste_externo_real_marca_como_verificado() {
        val choice = AddressClassifier.classify(
            lanIp = "192.168.0.10",
            wanIp = "192.168.0.1",
            publicIp = "45.160.85.157",
            port = 19132,
            verified = true,
        )
        assertEquals("verificado", choice.kind)
        assertTrue(choice.status.contains("confirmou"))
    }

    @Test
    fun sem_ip_publico_o_endereco_do_roteador_e_rotulado_como_wan() {
        val choice = AddressClassifier.classify(
            lanIp = "192.168.0.10",
            wanIp = "177.10.20.30",
            publicIp = null,
            port = 19132,
            verified = false,
        )
        assertEquals("wan", choice.kind)
        assertEquals("177.10.20.30:19132", choice.share)
        assertEquals("Acesso não verificado", choice.status)
    }

    @Test
    fun sem_rede_externa_mostra_apenas_a_lan() {
        val choice = AddressClassifier.classify(
            lanIp = "192.168.0.10",
            wanIp = null,
            publicIp = null,
            port = 19133,
            verified = null,
        )
        assertEquals("local", choice.kind)
        assertEquals("192.168.0.10:19133", choice.share)
    }

    @Test
    fun sem_nenhum_endereco_nao_inventa_valor() {
        val choice = AddressClassifier.classify(null, null, null, 19132, verified = null)
        assertEquals("nenhum", choice.kind)
        assertEquals("—", choice.share)
    }
    @Test
    fun classify_rejeita_ipv4_invalido_antes_de_compartilhar() {
        for (ip in listOf("300.1.1.1", "1.2.3", "1.2.3.4.5", "01.2.3.4", "+1.2.3.4", "1.2.3.-4", "1.2.3.4:19132")) {
            val choice = AddressClassifier.classify(null, null, ip, 19132, verified = true)
            assertEquals("nenhum", choice.kind)
            assertEquals("—", choice.share)
            assertFalse(choice.shareable)
        }
    }

    @Test
    fun classify_nao_chama_privado_loopback_ou_compartilhado_de_publico() {
        for (ip in listOf("10.0.0.2", "172.16.0.2", "192.168.1.2", "127.0.0.1", "169.254.1.2", "100.64.0.1", "0.0.0.0", "192.0.2.1", "198.18.0.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255")) {
            assertFalse(AddressClassifier.isPublicIpv4(ip))
            val choice = AddressClassifier.classify(null, null, ip, 19132, verified = true)
            assertEquals("nenhum", choice.kind)
            assertEquals("—", choice.share)
        }
    }

    @Test
    fun wan_privado_fica_no_diagnostico_e_nao_vira_internet() {
        val choice = AddressClassifier.classify("192.168.0.10", "10.1.2.3", null, 19132, verified = true)
        assertEquals("local", choice.kind)
        assertEquals("192.168.0.10:19132", choice.share)
        assertEquals("10.1.2.3", choice.wanAddress)
        assertTrue(choice.wanLabel.contains("privado"))
    }

    @Test
    fun ipv6_e_separado_e_formatado_com_colchetes() {
        assertEquals("IPv6", AddressClassifier.ipv4Kind("2001:db8::1"))
        assertEquals("documentação", AddressClassifier.ipv6Kind("2001:db8::1"))
        assertEquals("privado (ULA)", AddressClassifier.ipv6Kind("fd00::1"))
        assertEquals("[fd00::1]:19132", AddressClassifier.formatHostPort("fd00::1", 19132))
        val choice = AddressClassifier.classify("fd00::1", "2001:db8::2", "2001:db8::3", 19132, verified = true)
        assertEquals("local", choice.kind)
        assertEquals("[fd00::1]:19132", choice.share)
        assertTrue(choice.publicLabel.contains("IPv6"))
        assertNull(AddressClassifier.parseIpv6("2001:::1"))
    }

    @Test
    fun confirmacao_precisa_do_alvo_publico_testado() {
        val choice = AddressClassifier.classify("192.168.0.10", "177.10.20.30", "45.160.85.157", 19132, verified = true, verifiedIp = "192.168.0.10")
        assertEquals("observado", choice.kind)
        assertEquals("Acesso não verificado", choice.status)
    }

}

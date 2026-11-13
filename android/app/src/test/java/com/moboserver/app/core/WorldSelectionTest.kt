package com.moboserver.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes de JVM (sem aparelho) da lógica que decidiu o rumo do bug "Nenhum
 * mundo selecionado": classificação do motivo, mesclagem de configuração e
 * limpeza condicional da seleção.
 */
class WorldSelectionTest {

    private fun facts(
        folder: Boolean = true,
        levelDat: Boolean = true,
        db: Boolean = true,
        files: Int = 3,
    ) = WorldFacts(folderExists = folder, hasLevelDat = levelDat, hasDbDir = db, dbFiles = files)

    // ---------------------------------------------------------------- motivos

    @Test
    fun `id vazio vira nenhum mundo selecionado`() {
        val failure = WorldDiagnosis.inspect("", null)
        assertNotNull(failure)
        assertEquals(StartProblem.NO_SELECTION, failure!!.problem)
        assertTrue(failure.openWorlds)
        // A mensagem manda escolher/importar, não reimportar um mundo existente.
        assertTrue(failure.message.contains("Escolha um mundo"))
    }

    @Test
    fun `id salvo sem pasta e um motivo diferente`() {
        val failure = WorldDiagnosis.inspect("w1", facts(folder = false))
        assertNotNull(failure)
        assertEquals(StartProblem.FOLDER_MISSING, failure!!.problem)
        assertTrue(failure.message.contains("não existe mais"))
        assertFalse(failure.message.contains("Importe o .mcworld de novo"))
    }

    @Test
    fun `pasta sem level_dat nem db e estrutura invalida`() {
        val failure = WorldDiagnosis.inspect("w1", facts(levelDat = false, db = false, files = 0))
        assertEquals(StartProblem.STRUCTURE_INVALID, failure!!.problem)
        assertTrue(failure.message.contains("level.dat"))
    }

    @Test
    fun `db vazio tambem e estrutura invalida`() {
        val failure = WorldDiagnosis.inspect("w1", facts(db = true, files = 0))
        assertEquals(StartProblem.STRUCTURE_INVALID, failure!!.problem)
        assertTrue(failure.message.contains("db/"))
    }

    @Test
    fun `mundo completo nao tem falha`() {
        assertNull(WorldDiagnosis.inspect("w1", facts()))
    }

    @Test
    fun `timeout do validador nao e mundo invalido`() {
        val failure = WorldDiagnosis.validationTimeout(60)
        assertEquals(StartProblem.VALIDATION_TIMEOUT, failure.problem)
        assertTrue(failure.retryable)
        assertTrue(failure.message.contains("NÃO foi alterado"))
        assertEquals("validacao_timeout", WorldDiagnosis.code(failure.problem))
    }

    @Test
    fun `codigos do validador viram mensagens especificas`() {
        val incompat = WorldDiagnosis.validationFailure("incompatible_metadata", "tag extra", "Mundo A")
        assertTrue(incompat.message.contains("metadados"))
        val incompleto = WorldDiagnosis.validationFailure("incomplete_import", "", "Mundo A")
        assertTrue(incompleto.message.contains("incompleta"))
        val invalido = WorldDiagnosis.validationFailure("invalid_file", "zip sem level.dat", "Mundo A")
        assertTrue(invalido.message.contains("não é um mundo Bedrock"))
        // Técnico fica separado da mensagem mostrada ao usuário.
        assertTrue(incompat.technical.contains("incompatible_metadata"))
    }

    @Test
    fun `servidor em execucao bloqueia troca de mundo`() {
        val failure = WorldDiagnosis.busy()
        assertEquals(StartProblem.BUSY, failure.problem)
        assertTrue(failure.message.contains("Pare o servidor"))
    }

    // ------------------------------------------------------------- mesclagem

    @Test
    fun `salvar ajustes preserva o mundo selecionado`() {
        val current = ServerConfig(activeWorldId = "wB", port = 19132, rakNet = false)
        // Rascunho antigo: criado antes de o mundo B ser escolhido.
        val draft = ServerConfig(
            activeWorldId = "",
            port = 19500,
            rakNet = true,
            serverName = "Casa",
        )
        val merged = ConfigMerge.applySettings(current, draft)
        assertEquals("wB", merged.activeWorldId)
        assertEquals(19500, merged.port)
        assertTrue(merged.rakNet)
        assertEquals("Casa", merged.serverName)
    }

    @Test
    fun `selecionar mundo nao mexe em outros campos`() {
        val current = ServerConfig(serverName = "Casa", port = 19500, rakNet = true, activeWorldId = "wA")
        val updated = ConfigMerge.selectWorld(current, "  wB ")
        assertEquals("wB", updated.activeWorldId)
        assertEquals("Casa", updated.serverName)
        assertEquals(19500, updated.port)
        assertTrue(updated.rakNet)
    }

    @Test
    fun `excluir A nao apaga a selecao de B`() {
        val current = ServerConfig(activeWorldId = "wB")
        val afterDeleteA = ConfigMerge.clearWorldIf(current, "wA")
        assertEquals("wB", afterDeleteA.activeWorldId)
    }

    @Test
    fun `excluir o mundo selecionado limpa a selecao`() {
        val current = ServerConfig(activeWorldId = "wA")
        val afterDeleteA = ConfigMerge.clearWorldIf(current, "wA")
        assertEquals("", afterDeleteA.activeWorldId)
    }

    @Test
    fun `selecao sobrevive a atualizacao de campo isolado`() {
        var config = ServerConfig(activeWorldId = "wA")
        // Simula a tela de exposição/UPnP salvando um campo qualquer.
        config = config.copy(autoForward = false)
        assertEquals("wA", config.activeWorldId)
    }
}

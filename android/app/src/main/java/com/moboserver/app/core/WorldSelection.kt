package com.moboserver.app.core

/**
 * O que pode impedir o servidor de abrir um mundo. Cada motivo tem mensagem
 * própria: "importe de novo" só faz sentido para estrutura inválida, não para
 * pasta apagada, mundo em uso ou validador que demorou demais.
 */
enum class StartProblem {
    NONE,
    /** Nenhum ID salvo: o usuário nunca escolheu (ou a seleção foi limpa). */
    NO_SELECTION,
    /** Existe ID salvo, mas a pasta não está mais no aparelho. */
    FOLDER_MISSING,
    /** A pasta existe, mas faltam level.dat ou db/. */
    STRUCTURE_INVALID,
    /** O validador embutido respondeu que o mundo não serve para este motor. */
    VALIDATION_FAILED,
    /** O validador não terminou no prazo e foi encerrado. */
    VALIDATION_TIMEOUT,
    /** O binário do servidor não pôde ser executado neste aparelho. */
    BINARY_FAILED,
    /** O servidor está ligado/iniciando: trocar de mundo agora é inseguro. */
    BUSY,
    OTHER,
}

/** Fatos observados no disco, para classificar sem adivinhar. */
data class WorldFacts(
    val folderExists: Boolean,
    val hasLevelDat: Boolean,
    val hasDbDir: Boolean,
    val dbFiles: Int,
    val sizeBytes: Long = 0L,
)

/** Falha pronta para a interface: título curto, explicação e detalhe técnico. */
data class StartFailure(
    val problem: StartProblem,
    val title: String,
    val message: String,
    /** Só vai para o diagnóstico (console/log), nunca para o cartão principal. */
    val technical: String = "",
    /** Mostra o atalho "Abrir Mundos" ao lado do erro. */
    val openWorlds: Boolean = false,
    /** Permite tentar de novo sem reimportar. */
    val retryable: Boolean = true,
)

/** Resultado de tentar selecionar um mundo: sempre observável pela UI. */
data class SelectOutcome(
    val ok: Boolean,
    val selectedId: String,
    val previousId: String,
    val failure: StartFailure? = null,
)

/**
 * Classificador puro: recebe fatos, devolve a falha. Não toca em disco nem em
 * SharedPreferences, então dá para testar todos os casos na JVM.
 */
object WorldDiagnosis {

    /**
     * @param id seleção persistida (pode ser vazia)
     * @param facts fatos do diretório, ou null quando [id] está vazio
     */
    fun inspect(id: String, facts: WorldFacts?): StartFailure? {
        if (id.isBlank()) {
            return StartFailure(
                problem = StartProblem.NO_SELECTION,
                title = "Nenhum mundo selecionado",
                message = "Escolha um mundo em Mundos para iniciar o servidor. Se nenhum foi importado " +
                    "ainda, exporte o mundo pelo Minecraft e use \"Importar mundo (.mcworld)\".",
                technical = "activeWorldId vazio no início",
                openWorlds = true,
            )
        }
        if (facts == null || !facts.folderExists) {
            return StartFailure(
                problem = StartProblem.FOLDER_MISSING,
                title = "O mundo selecionado não está mais no aparelho",
                message = "A pasta do mundo \"$id\" não existe mais. Escolha outro mundo em Mundos — os " +
                    "outros mundos continuam intactos e nada precisa ser reimportado.",
                technical = "diretório worlds/$id ausente",
                openWorlds = true,
            )
        }
        return when {
            !facts.hasLevelDat && !facts.hasDbDir -> StartFailure(
                problem = StartProblem.STRUCTURE_INVALID,
                title = "Mundo incompleto",
                message = "O mundo \"$id\" não tem level.dat nem a pasta db/. A importação foi " +
                    "interrompida ou a pasta foi copiada pela metade. Importe o .mcworld de novo.",
                technical = "level.dat e db/ ausentes",
                openWorlds = true,
            )
            !facts.hasLevelDat -> StartFailure(
                problem = StartProblem.STRUCTURE_INVALID,
                title = "Mundo incompleto",
                message = "O mundo \"$id\" não tem o arquivo level.dat. Importe o .mcworld de novo.",
                technical = "level.dat ausente",
                openWorlds = true,
            )
            !facts.hasDbDir || facts.dbFiles == 0 -> StartFailure(
                problem = StartProblem.STRUCTURE_INVALID,
                title = "Mundo incompleto",
                message = "O mundo \"$id\" não tem os dados do jogo (pasta db/ vazia). Importe o " +
                    ".mcworld de novo — o arquivo original continua no aparelho.",
                technical = "db/ ausente ou vazia",
                openWorlds = true,
            )
            else -> null
        }
    }

    /** Falha reportada pelo validador embutido (códigos de `-validate-world`). */
    fun validationFailure(code: String?, detail: String, worldName: String): StartFailure {
        val cleanName = worldName.ifBlank { "mundo selecionado" }
        val message = when (code) {
            "incompatible_metadata" ->
                "O $cleanName tem metadados que esta versão do motor não lê." +
                    (if (detail.isBlank()) "" else " Detalhe: $detail")
            "incomplete_import" ->
                "A importação do $cleanName ficou incompleta: faltam arquivos do mundo (level.dat ou db/). " +
                    "Exporte de novo pelo Minecraft e importe o .mcworld inteiro."
            "invalid_file" ->
                "O $cleanName não é um mundo Bedrock válido." +
                    (if (detail.isBlank()) "" else " Detalhe: $detail")
            else -> detail.ifBlank { "Não foi possível validar o $cleanName." }
        }
        return StartFailure(
            problem = StartProblem.VALIDATION_FAILED,
            title = "O servidor não conseguiu abrir este mundo",
            message = message,
            technical = "validador: code=${code ?: "?"} ${detail}",
            openWorlds = true,
        )
    }

    /** O validador estourou o prazo: não é o mesmo que "mundo inválido". */
    fun validationTimeout(seconds: Long): StartFailure = StartFailure(
        problem = StartProblem.VALIDATION_TIMEOUT,
        title = "A validação do mundo demorou demais",
        message = "O validador embutido não terminou em ${seconds}s e foi encerrado. O mundo NÃO foi " +
            "alterado; tente iniciar de novo. Se repetir, compartilhe o diagnóstico.",
        technical = "timeout de ${seconds}s no -validate-world",
    )

    fun binaryFailure(detail: String): StartFailure = StartFailure(
        problem = StartProblem.BINARY_FAILED,
        title = "Não foi possível executar o servidor",
        message = "O servidor embutido não rodou neste aparelho. Abra Ajustes → Diagnóstico e use " +
            "\"Testar servidor embutido\" para ver o motivo.",
        technical = detail,
    )

    fun busy(): StartFailure = StartFailure(
        problem = StartProblem.BUSY,
        title = "Servidor em execução",
        message = "Pare o servidor antes de trocar, excluir ou importar mundos.",
        technical = "seleção bloqueada durante STARTING/RUNNING/STOPPING",
    )

    /**
     * O usuário tentou iniciar enquanto uma importação ainda não terminou. Foi
     * por aqui que "Nenhum mundo selecionado" aparecia com um mundo já
     * escolhido: a seleção só é gravada quando a importação (e a validação)
     * termina, e o botão "Ligar" não olhava para isso.
     */
    fun importRunning(): StartFailure = StartFailure(
        problem = StartProblem.BUSY,
        title = "Importação em andamento",
        message = "O mundo ainda está sendo importado e validado. Aguarde a mensagem de confirmação " +
            "na aba Mundos antes de iniciar o servidor.",
        technical = "start durante import",
        openWorlds = true,
    )

    fun noRepository(): StartFailure = StartFailure(
        problem = StartProblem.OTHER,
        title = "Não foi possível abrir a pasta de mundos",
        message = "O app não conseguiu acessar o armazenamento interno. Feche e abra o app de novo.",
        technical = "ServerController.worlds() == null",
    )

    /** Nome curto do problema para o log (sem dados pessoais). */
    fun code(problem: StartProblem): String = when (problem) {
        StartProblem.NONE -> "ok"
        StartProblem.NO_SELECTION -> "sem_selecao"
        StartProblem.FOLDER_MISSING -> "pasta_ausente"
        StartProblem.STRUCTURE_INVALID -> "estrutura_invalida"
        StartProblem.VALIDATION_FAILED -> "validacao_falhou"
        StartProblem.VALIDATION_TIMEOUT -> "validacao_timeout"
        StartProblem.BINARY_FAILED -> "binario_falhou"
        StartProblem.BUSY -> "ocupado"
        StartProblem.OTHER -> "outro"
    }
}

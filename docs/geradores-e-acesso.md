# Avaliação do motor de mundo e do acesso externo

Nada aqui é aspiracional: cada linha descreve o que o código faz hoje.

## 1. O que o motor atual gera

| Item | Estado | Onde |
| --- | --- | --- |
| Overworld | **Parcial**: relevo por ruído, camadas de pedra/terra/grama, areia, água no nível do mar, carvão e ferro, árvores esparsas | `server/generator.go` |
| Nether | **Ausente**: superfície plana de netherrack | `generatorFor()` em `server/main.go` |
| End | **Ausente**: superfície plana de end stone | idem |
| Seed | **Fixa**: `terrainSeed = 0x4D4F424F53` | `server/generator.go` |
| Bioma | **Fixo**: Plains | `server/generator.go` |
| Cavernas / minérios variados / estruturas | **Ausentes** | — |
| Animais, monstros, spawn, IA, persistência | **Ausentes** | nenhum spawner no projeto |
| Preservação do que já existe no LevelDB | **Parcial**: chunks existentes não são regerados | provider do Dragonfly |
| Continuidade com mundo Bedrock importado | **Não há**: chunks novos usam este gerador | `generatorFor()` |

Conclusão: o motor atual **não entrega sobrevivência vanilla**. Explorar além da
área importada produz terreno simplificado, sem estruturas, cavernas, minérios do
jogo nem criaturas. Não é "sobrevivência corrigida".

## 2. Decisão: manter ou substituir

* **Dragonfly** (atual): servidor Go para Bedrock; a worldgen é a deste projeto
  (issue [#566](https://github.com/df-mc/dragonfly/issues/566)).
* **Bedrock Dedicated Server oficial**: binário Windows/Linux x86-64; não roda em
  Android sem emulação — descartado.
* **Playit + WaterdogPE**: exige Java 17 e proxy Java (ver §4) — descartado.

**Bloqueio declarado**: não há motor substituto comprovado que rode no celular
com as restrições dadas. O que dá para fazer sem enganar:

1. Dizer na interface (Ajuda/Diagnóstico) que o mundo usa motor simplificado.
2. Registrar por mundo qual motor gerou os chunks novos e **nunca** trocar em
   silêncio (`server/generation.go`, schema 2, `generation/worlds/<hash>.json`
   na pasta de dados, fora do mundo; o `generation.json` global antigo só é
   adotado por recuperação explícita para um mundo).
3. Área pré-gerada como paliativo explícito e limitado — não resolve exploração
   ilimitada nem entidades.

## 3. Proteção dos dados (implementado)

* O `.mcworld` importado é preservado pelo app (cópia de trabalho).
* Backup consistente continua sendo feito com o servidor parado.
* `generation/worlds/<hash-do-ID>.json` guarda `engine`, `overworld`
  (`terrain`/`flat`), origem (`imported`/`created`/`unknown`) e a versão que
  gravou. Pedir outro gerador apenas **avisa** e mantém o gerador efetivo
  anterior — sem regenerar chunks. Registro inválido bloqueia; corrompido é
  preservado e só segue com recuperação explícita.
* Comentário de `generator.go` corrigido: mundos importados **recebem** chunks
  novos deste gerador.

## 4. Playit: avaliado e não integrado

Fonte: <https://playit.gg/support/minecraft-bedrock-nethernet/>. A documentação
oficial afirma que o Bedrock Dedicated Server "can't connect to playit by
itself": é preciso rodar o **WaterdogPE** (proxy Java, Java 17+) e é ele que se
registra no serviço de sinalização (`nethernet:` + token); o jogador entra por
`<tunel>.tun.ply.gg:19132`.

Consequências: não há forma pública de o servidor Bedrock registrar-se sozinho;
integrar exigiria reimplementar o registro do playit **e** a sinalização do
WaterdogPE (contrato não publicado como API estável); e rodar WaterdogPE é Java
no celular, exatamente o que o produto proíbe. Decisão: **não integrar**. O túnel
próprio continua como está — testado no encaminhamento de datagramas, incompleto
na sinalização — e documentado em `docs/acesso-externo.md` §7.

## 5. Pendências abertas

* Sinalização HTTP pública (`/v1/join`, `/v1/join/{networkId}`) do NetherNet.
* Propriedade de portas/`GetListenAddresses` no mux e testes com agentes ICE reais.
* `Close`/deadlines e invalidação de sessão na reconexão do túnel.
* LAN simultânea ao relay (hoje o mux substitui os sockets locais).
* TLS no controle do MOBOTUN/2, provisionamento e limites por instalação.
* Endereço compartilhável da interface vindo do endpoint de sinalização.

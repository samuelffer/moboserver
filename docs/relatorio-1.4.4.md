# MoboServer 1.4.4 — mudanças e limitações

## Corrigido nesta revisão
- Política de geração por ID persistente de mundo (`server/generation.go`),
  schema 2, arquivo por mundo, flock, escrita atômica, resolução sem escrita,
  preflight em memória e commit somente após o servidor abrir/transporte responder.
- `main.go` resolve a política antes de criar o servidor e entrega o gerador
  efetivo ao Dragonfly. Divergência mantém o gerador registrado e avisa.
- Origem por metadados de importação; mundo antigo permanece `unknown`.
- Registro global antigo não é herdado automaticamente: migração ambígua bloqueia
  até adoção explícita para um mundo.
- Skins: motivos específicos (osso head ausente, sem cubos diretos, descendentes,
  UV por face, UV fora da textura, malha, parsing/seleção), RGBA→NRGBA com teste
  de transparência parcial, exportação explícita de textura+modelo+diagnóstico.
- Endereços: `AddressClassifier.classify()` valida IPv4 antes de classificar,
  separa válido/público, preserva WAN com rótulo, trata IPv6 à parte e nunca
  confirma acesso externo por STUN/UPnP/teste local.
- Logs de desligamento duplicados removidos; UDP legado não é anunciado ativo com
  RakNet desligado; constantes de view distance 4–24 (padrão 8) em UI/ViewModel/Go.
- Identidade Android+Go de fonte controlada: `version.properties`, revisão Git e
  hash de fonte injetados no binário; workflow verifica assinatura, package,
  versionName/Code, servidor ARM64 e commit compilado.

## Testes automatizados desta revisão
- Go: política A terrain vs B flat, divergência não troca gerador efetivo,
  registro inválido/corrompido preservado, recuperação explícita, legado ambíguo,
  mundo existente ≠ importado, falha de validação sem registro, preflight sem
  escrita, skins clássicas + novos códigos + alfa parcial + exportação,
  transporte sem UDP falso, clamp de view distance.
- JVM: `AddressClassifierTest` (classify direto, IPv4 inválido, faixas especiais,
  WAN privada, IPv6, confirmação só do alvo testado) e testes existentes de
  seleção/mesclagem de mundo/config.
- CI: self-tests RakNet/NetherNet/túnel, build ARM64, ELF Android, APK release,
  apksigner, package/versão, SHA-256 e ZIP.

## Pendências/bloqueios declarados
- **Geração vanilla**: não concluída. O motor continua simplificado; a política
  protege dados e torna a escolha auditável, mas não
  implementa worldgen vanilla nem entidades/estruturas do Minecraft.
- **Acesso externo**: não confirmado por teste real neste ambiente. O código
  separa observado/WAN/verificado, mas falta prova com segunda rede/aparelho.
- **Playit**: o caminho documentado oficial usa WaterdogPE/Java 17+ como proxy
  NetherNet. Integração direta do servidor Bedrock/playit agent não foi comprovada
  aqui; também não há prova de impossibilidade absoluta. Não integrado.
- **Entity runtime ID not found**: localizado em Dragonfly v0.11.6
  `server/session/world.go:1437` (`handleRuntimeID`, log Debug quando o handle não
  está no mapa). Sem reprodução controlada nesta revisão; não foi alterada a
  dependência nem o caminho de entidades.
- **Aparelho/Minecraft**: nenhum teste físico ou com cliente real foi executado.

# MoboServer

Servidor de **Minecraft Bedrock** que roda **dentro do seu celular Android**. Abra o app,
escolha o mundo, ligue o servidor e mande o endereço para os amigos — sem PC, sem hospedagem
paga, sem conta em serviço nenhum.

<p align="center">
  <img src="docs/icon-512.png" width="120" alt="MoboServer" />
</p>

## Como funciona

O aplicativo carrega um servidor Minecraft Bedrock completo (escrito em Go, baseado no
[Dragonfly](https://github.com/df-mc/dragonfly)) **embutido no APK** e o executa como um
processo nativo do próprio app:

```
┌──────────────────────────── MoboServer (APK) ────────────────────────────┐
│  UI Compose  ──►  ServerController  ──►  Processo do servidor (Go)       │
│      ▲                    │                        │                     │
│      │              Estado/eventos                 │ RakNet/UDP 19132    │
│      └────────────────────┘  (JSON por stdin/out)  ▼                     │
│                                          Minecraft Bedrock dos amigos    │
└──────────────────────────────────────────────────────────────────────────┘
```

* **Sem instalar nada**: o binário do servidor viaja dentro do APK, na pasta de bibliotecas
  nativas (o único lugar em que o Android permite executar arquivos de um app).
* **Mundos reais**: o formato do mundo é o LevelDB do Bedrock, então mundos importados
  (`.mcworld`) funcionam direto e o mundo criado no app pode ser exportado/aberto no Minecraft.
* **Controle total**: ligar/desligar, console ao vivo, jogadores online, expulsar/banir,
  salvar o mundo, ajustes de jogo.
* **Segundo plano**: serviço em primeiro plano + wake lock + Wi-Fi lock, com notificação
  permanente mostrando jogadores e porta.

## Recursos da versão 1.0.3

| Recurso | Detalhe |
| --- | --- |
| Ligar/desligar servidor | Salvamento automático do mundo ao desligar |
| Importar mundo | `.mcworld` / `.zip` exportado pelo Minecraft (via seletor de arquivos) |
| Mundo novo | Gerador de terreno natural (colinas, praias, árvores, minérios) ou superflat |
| Ajustes | Nome, porta, máximo de jogadores, modo de jogo, dificuldade, distância de visão, login Xbox |
| Jogadores | Lista em tempo real, expulsar e banir (persistente por XUID); desbanir em Ajustes |
| Console | Log ao vivo, cópia do log, mensagem para todos no chat |
| Internet | Descoberta de IP público (STUN), liberação automática de porta (UPnP) e teste de acesso externo |
| Rede local | Endereço Wi-Fi na tela inicial e **anúncio automático na aba LAN** (PC, celular e console na mesma rede) |
| Otimização | Limite de memória do runtime Go conforme o heap do app, 1 worker de chunk, view distance limitada |
| Diagnóstico | Teste do binário embutido, estado do aparelho e relatório de falhas com copiar/compartilhar |

## Requisitos

* Android 8.0 (API 26) ou superior, **arm64** (o Minecraft Bedrock é 64 bits).
* Internet ativa (a validação de login do Xbox Live acontece ao iniciar).
* Espaço livre para os mundos.

## Download

| Arquivo | Tamanho | SHA-256 |
| --- | --- | --- |
| [`releases_apk/MoboServer-v1.0.3-android-arm64.zip`](releases_apk/MoboServer-v1.0.3-android-arm64.zip) | 18,5 MB | `3311132e1332bd1f335f98ae899b868c91f310b4d4de58cd039314602520a242` |

O ZIP contém `MoboServer-v1.0.3.apk`, `LEIA-ME.txt` e `LICENSE`. O APK é assinado
(`apksigner verify` roda no CI) e as próximas versões instalam por cima desta.

## Instalação

Copie o APK do ZIP para o celular e abra o arquivo — o Android vai pedir permissão para
instalar apps de fontes desconhecidas. Para conferir o download, compare o SHA-256 acima:

```bash
sha256sum MoboServer-v1.0.3-android-arm64.zip
```

## Quem consegue entrar

| Plataforma | Como entrar |
| --- | --- |
| **PC (Windows 10/11)**, **Android**, **iPhone/iPad** | *Jogar → Servidores → Adicionar servidor* com o IP e a porta. Funciona de qualquer rede, desde que a porta esteja liberada. |
| **Xbox, PlayStation, Nintendo Switch** | Esses consoles **não têm** o botão de adicionar servidor (limitação de Microsoft/Sony/Nintendo). Eles entram quando o servidor aparece na aba **LAN/Mundos**, ou seja, com o console **na mesma rede Wi-Fi** do celular — é para isso que o app se anuncia sozinho na LAN. |

O servidor é Bedrock puro: **não** aceita Minecraft Java (PC Java), a não ser através de
tradutores como o Geyser, que não vêm incluídos.

## Uso

1. **Mundos** → crie um mundo novo ou importe o seu `.mcworld`.
2. **Ajustes** → nome do servidor, porta, jogadores, modo de jogo, dificuldade.
3. **Início** → **Ligar servidor**.
4. **Liberar porta** (UPnP) e **Testar acesso** para confirmar o acesso pela internet.
5. No Minecraft: *Jogar → Servidores → Adicionar servidor* → endereço + porta.

> Na mesma rede Wi-Fi, use o endereço local. Fora dela, o IP público com a porta liberada.
> Se o provedor usa CGNAT (IP compartilhado), o acesso externo direto não é possível.

## Estrutura do projeto

```
android/                 App Android (Kotlin + Jetpack Compose, tema escuro)
  app/src/main/java/com/moboserver/app/
    core/                Processo do servidor, estado, mundos, UPnP, STUN, serviço
    ui/                  Telas (Início, Mundos, Console, Ajustes) e tema
server/                  Servidor embutido em Go (Dragonfly + gerador de mundo)
  main.go                Flags, ciclo de vida, fonte de eventos
  control.go             Comandos (kick, ban, say, save...), banimentos, jogadores
  generator.go           Gerador de terreno natural determinístico
  selftest.go            Teste real: inicia servidor, gera chunk e faz ping RakNet
  events.go              Protocolo JSON de eventos com o app
.github/workflows/       Build do servidor + APK, self-test, ZIP e artefato
releases_apk/            ZIPs publicados pelo CI
```

### Protocolo app ↔ servidor

O app conversa com o processo do servidor por pipes, sem portas extras e sem risco de
exposição:

* **servidor → app** (`stdout`, uma linha JSON por evento): `ready`, `log`, `join`, `quit`,
  `players`, `fatal`, `stopped`.
* **app → servidor** (`stdin`, uma linha JSON por comando): `kick`, `ban`, `unban`, `say`,
  `save`, `players`, `stop`.

## Compilar

Pré-requisitos: Go 1.26+, JDK 17, Android SDK 35.

> O binário arm64 precisa de um passo extra (`scripts/patch-interp.py`) para apontar o
> interpretador ELF para `/system/bin/linker64`; o `make server-arm64` já faz isso.

```bash
# 1. Servidor embutido (também roda o self-test)
cd server
go mod tidy
go vet ./... && go build -o /tmp/mobosrv . && /tmp/mobosrv -selftest

# 2. Binário para Android (arm64) dentro do projeto Android
mkdir -p ../android/app/src/main/jniLibs/arm64-v8a
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -buildmode=pie -trimpath \
  -ldflags="-s -w" -o ../android/app/src/main/jniLibs/arm64-v8a/libmobosrv.so .

# 3. APK
cd ../android && gradle assembleRelease
# saída: android/app/build/outputs/apk/release/app-release.apk
```

Ou simplesmente faça push: o workflow `.github/workflows/build-apk.yml` compila o servidor,
roda o self-test (que sobe um servidor de verdade e faz ping nele), gera o APK assinado,
coloca o ZIP em `releases_apk/` e publica o artefato.

### Assinatura

`android/keystore/moboserver-signing.p12` é uma chave **autoassinada** de desenvolvimento
(senha documentada no `build.gradle.kts`). Ela existe para que todas as versões do APK usem a
mesma assinatura e possam ser atualizadas por cima da anterior. Não é um segredo de produção
e não deve ser usada na Play Store.

## Estado dos testes

Verificado automaticamente a cada build:

* servidor Go: `go vet`, binário arm64 e **self-test real** — sobe o servidor, escuta em UDP,
  gera/recebe chunks, responde ao ping RakNet (`MCPE;...`), liga/desliga e salva o mundo;
* geração de mundo nos dois modos (`terrain`, `flat`), importação de mundo existente e
  recuperação de `level.dat` corrompido / porta ocupada;
* APK: compila em release, `apksigner verify` aprova a assinatura e o CI confirma que
  `lib/arm64-v8a/libmobosrv.so` está dentro do APK;
* o ZIP publicado em `releases_apk/` é gerado por esse mesmo build.

**Não testado em aparelho físico** durante o desenvolvimento (o ambiente de build não tem
Android): o caminho de execução do binário, o serviço em primeiro plano, o UPnP de roteadores
reais e o layout em telas pequenas foram escritos seguindo as APIs documentadas, mas a
validação final é a primeira execução no seu celular. Se algo falhar, o console do app mostra
a mensagem de erro do servidor.

## Changelog

### 1.0.3

* **Correção do "Cannot run program ... error=2"**: o binário do servidor era um PIE compilado
  para Linux, que aponta para `/lib/ld-linux-aarch64.so.1` (loader do glibc) — esse arquivo não
  existe no Android, então o `execve` falhava com ENOENT mesmo com o binário presente. Agora o
  `PT_INTERP` é reescrito para `/system/bin/linker64` e o CI valida o ELF antes de empacotar.
* Mensagem de erro de execução mais clara, apontando o Diagnóstico.

### 1.0.2

* **Correção do crash ao abrir**: a tela inicial carregava `@mipmap/ic_launcher` como bitmap,
  mas em Android 8+ esse recurso é um *adaptive icon* (XML), e o Compose não consegue
  decodificá-lo — o app abria e fechava na hora. Agora usa um PNG dedicado.
* **Relatório de falhas**: se o app cair, a próxima abertura mostra o erro com "Copiar" e
  "Compartilhar" (antes fechava em silêncio).
* **Anúncio na LAN**: o servidor se anuncia como um mundo aberto para LAN, então PC, celular
  e consoles na mesma rede Wi-Fi encontram o mundo sem digitar IP.
* **Início/parada fora da thread principal**: evita travar a interface em aparelhos lentos.
* **Diagnóstico nos Ajustes**: testa o binário embutido (`mobosrv -version`) e mostra ABI,
  caminho e espaço livre.

### 1.0.1

* Correção do envio do comando `stop` (salvar o mundo ao desligar).
* Lista de banidos com "Desbanir" nos Ajustes.

## Limitações conhecidas

* Provedores com CGNAT impedem acesso externo direto.
* UPnP depende do roteador; sem ele, é preciso liberar a porta manualmente.
* O consumo de CPU/RAM é do aparelho — celulares modestos suportam poucos jogadores.
* Não há tunelamento embutido (por exemplo playit.gg) nesta versão.
* Apenas `arm64-v8a` (não há build para armeabi-v7a/x86).

## Licença

MIT — veja [LICENSE](LICENSE). Servidor baseado em Dragonfly (MIT).
Minecraft é marca da Mojang/Microsoft; este projeto não é afiliado à Mojang.

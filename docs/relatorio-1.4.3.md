# Relatório — correções da versão 1.4.3

Build: workflow `build-apk.yml`, run **43545243279** = sucesso (Go + testes de JVM + APK arm64 +
assinatura). ZIP publicado pelo próprio workflow: `releases_apk/MoboServer-v1.4.3-android-arm64.zip`
(19 MB, sha256 `2dbf698d5b80e11f1b526f4408a8d552d49be3414ad11d154afa8304c213cce0`).

## O que foi corrigido, item por item

### 1. Cabeça da skin

* `server/avatar.go` reescrito: o plano de recorte sai da **geometria**, não de palavras no JSON.
  O cubo do boneco `head` define a UV do rosto (`uv + depth`) e o cubo com `inflate > 0` é a
  camada externa; `texture_width/height` dá a escala (64 ou 128). Sem cubo de cabeça só o
  identificador `geometry.humanoid*` autoriza as UVs fixas.
* Estado explícito por jogador no app (`Loading`, `Ready`, `Unsupported`, `Failed`) + motivo no
  painel e no diálogo do jogador. O traço silencioso não existe mais: quando não há imagem, há
  ícone neutro e explicação.
* Reenvio: comando `avatar` no servidor (`resendAvatar`), pedido pelo app no máximo 1×/10 s por
  jogador; evento com `rev` para descartar PNG antigo que decodificou fora de ordem.
* Diagnóstico exportável (`Compartilhar diagnóstico`) inclui a seção `-- Avatares (skin da
  sessao) --` com sessão, chave, persona, dimensões, bytes de pixel/modelo, id da geometria,
  presença do cubo de cabeça, layout, resultado, hash e motivo. Nunca PNG/base64/tokens.
* RTT: `-1` = sem medição; `0` = válido (<1 ms), em Go e Kotlin.

Testes de Go (evidência `docs/test-evidence.txt`): `TestAvatarFaceCrop`,
`TestAvatarOuterLayerOverlay`, `TestAvatarSemiTransparentOverlay`, `TestAvatarLegacyOpaqueHatIgnored`,
`TestAvatarDoubledLayout`, `TestAvatarUnavailable`, `TestAvatarHashChangesWithSkin`,
`TestAvatarGeometryHeadCube`, `TestAvatarGeometryHatOverlay`, `TestAvatarGeometryDoubled128`,
`TestAvatarGeometryWithoutHeadCube`, `TestAvatarPersonaWithGeometry`, `TestAvatarModelLimits` — todos PASS.

### 2. Voltar / navegação

`MoboPanel(page, onPageChange)` é controlado; `AppRoot` guarda a página e o `BackHandler` resolve
em prioridade: diálogo → Avançado → Jogadores → saída. O “‹ Voltar” chama a mesma função.

### 3. Abertura e animações

`installSplashScreen()` com `keepOnScreenCondition { !UiReadiness.ready }` (primeira leitura real,
sem `sleep`, sem Activity extra); tema `Theme.MoboServer.Splash` com `@drawable/ic_splash` e fundo
claro/escuro; troca de aba em `AnimatedContent` (fade + 6 dp, 200 ms, 0 ms se a escala de animação
do sistema for 0).

### 4. Console

Registros/Chat segmentados, `LazyColumn` com chaves estáveis, filtros Todos/Avisos/Erros,
“Novos registros”, copiar/exportar/limpar só a visualização. Chat usa o evento estruturado do
`HandleChat` e só marca “enviada” após `chat_sent` (6 s sem confirmação → “não enviada”, texto
preservado).

### 5. Cartão inicial

Sem ping agregado; tempo ativo, estado real e último salvamento **confirmado** (`saved`, emitido
depois de gravar), `—` quando não houve. Nenhuma inferência de CPU/TPS/memória.

### 6. Endereços

`core/AddressClassifier.kt` (Kotlin puro, 7 testes de JVM): verificado > observado (STUN) > WAN
(UPnP) > local; **100.64.0.0/10 nunca é oferecido como público**; “Acesso não verificado” fora do
caso confirmado por teste externo.

### 7. Ícone

`scripts/make-launcher-icons.py` gera, a partir de `arquivos/icon_moboserver.png` (1254×1254,
RGBA): `mipmap-{m,h,x,xx,xxx}dpi/ic_launcher.png` (48…192 px), `ic_launcher_round.png` (máscara
circular), `ic_launcher_foreground.png` (108 dp com a arte em 66%, área segura) e
`drawable-nodpi/ic_splash.png`. A arte não é recortada na origem.

## O que ainda depende de um aparelho

Nada disso foi executado aqui (não há Android/emulador neste ambiente):

* skin real de um jogador entrando — o motivo concreto aparece no painel e no diagnóstico; sem a
  sessão do aparelho não é possível afirmar qual formato (Persona, personalizada ou falha de
  receção) está em causa;
* capturas do botão Voltar nas três camadas e da separação Registros/Chat;
* rotação, redução de animação do sistema e saída/reentrada com o servidor ativo.

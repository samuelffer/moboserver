# Relay do MoboServer (acesso externo sem mexer no roteador)

Este diretório contém o **lado público** do túnel do MoboServer. O celular abre
uma conexão de saída para o relay e recebe, de volta, uma porta UDP pública.
Quem joga conecta nessa porta e os datagramas chegam ao servidor Minecraft que
está rodando no Android.

```
 jogador (qualquer rede)                     VPS com o relay           celular (atrás de NAT/CGNAT)
        │                                          │                            │
        │  UDP  →  relay:20xxx                     │                            │
        └─────────────────────────────────────────►│                            │
                                                   │◄──── TCP 9000 (saída) ─────┘
                                                   │      MOBOTUN/1 <token>     │
                                                   │                            ├─ UDP 127.0.0.1:19132
                                                   │                            │  (um socket por jogador)
```

## Por que um relay próprio (e não frp/playit.gg)

| Critério | Relay próprio | frp | playit.gg |
| --- | --- | --- | --- |
| Binário extra no APK | **não** (o cliente é compilado junto do servidor Go) | sim (frpc aarch64 executado pelo app) | sim (agente oficial) |
| Conta / cadastro | não | depende do servidor | sim |
| UDP nativo | sim | sim (`type = "udp"`) | sim (Bedrock) |
| Controle de portas e auditoria | total | total | do fornecedor |
| Custo | VPS + tráfego | VPS + tráfego | gratuito com limites/telemetria |

O ponto decisivo foi o **binário extra**. Executar um segundo executável no
Android é justamente o caminho que quebrou as versões 1.0.2–1.0.4 deste projeto
(PIE/ELF/TLS do Bionic). O cliente do túnel é o mesmo binário do servidor: se o
servidor roda, o túnel roda. As duas alternativas seguem válidas e podem ser
usadas como relay: o app fala o protocolo `MOBOTUN/1` apenas com o nosso relay,
então `frp`/`playit` exigiriam outro adaptador.

## Componentes

* `server/cmd/relay` — o relay (Go, sem dependências externas).
* `server/internal/tunnel` — protocolo, relay e cliente (usados pelo relay e
  pelo servidor embutido no APK).
* App Android: campo **Relay (host:porta)** + **Token** em Ajustes. Vazio =
  túnel desligado, e tudo continua como antes (LAN + port forwarding).

## Implantação

Requisitos: uma VPS com IP público, Go 1.22+ (ou o binário compilado) e duas
portas liberadas:

* **TCP 9000** — conexões de controle dos celulares;
* **UDP 20000-20010** (exemplo) — portas públicas entregues aos celulares.

```bash
# no servidor
git clone https://github.com/samuelffer/moboserver && cd moboserver
cd server && go build -o ../relay/moboserver-relay ./cmd/relay
../relay/moboserver-relay -listen :9000 -tokens "$(openssl rand -hex 24)" -ports 20000-20010
```

O comando imprime o token; ele é o mesmo que se digita em **Ajustes → Token do
relay** no celular. Guarde-o: não há recuperação (rotacione trocando o valor).

### systemd

```ini
# /etc/systemd/system/moboserver-relay.service
[Unit]
Description=MoboServer relay
After=network-online.target

[Service]
ExecStart=/opt/moboserver-relay -listen :9000 -tokens SEU_TOKEN_AQUI -ports 20000-20010
Restart=always
DynamicUser=yes
NoNewPrivileges=yes
PrivateTmp=yes

[Install]
WantedBy=multi-user.target
```

### TLS (recomendado)

O relay fala TCP puro; coloque TLS na frente com `nginx` (módulo stream) e use
o mesmo domínio no app:

```nginx
stream {
    server {
        listen 9443 ssl;
        ssl_certificate     /etc/letsencrypt/live/relay.exemplo.com/fullchain.pem;
        ssl_certificate_key /etc/letsencrypt/live/relay.exemplo.com/privkey.pem;
        proxy_pass 127.0.0.1:9000;
    }
}
```

No app: **Relay** = `relay.exemplo.com:9443`, **Usar TLS** ligado.

## NetherNet: ainda não atendido por este relay

O túnel descrito acima encaminha **um fluxo UDP** (a porta do jogo, 19132), o que
atende a descoberta e o RakNet. A entrada por endereço do cliente atual usa
NetherNet, que tem duas partes:

1. **sinalização** — HTTP em TCP na mesma porta do jogo (`GET /v1/join`,
   `POST /v1/join/<id>`). Hoje não há encaminhamento TCP no relay;
2. **mídia** — WebRTC/ICE em UDP nas portas `19133-19140`
   (`-nethernet-ports`), uma por conexão, além do que o servidor anuncia no SDP.

Para publicar MoboServer para outra rede por esse caminho é preciso: encaminhar
o TCP da sinalização pelo relay (mesmo token/handshake), abrir a faixa UDP de
mídia e anunciar credenciais STUN/TURN no handler de sinalização (o
`endpoint.HandlerConfig.Credentials`, que o MoboServer hoje deixa vazio). Também
é preciso um TURN para o caso de o ICE não conseguir um par direto — cenário
típico de CGNAT.

Nada disso foi validado ainda: **o app não deve anunciar acesso externo por
NetherNet até que a entrada no mundo por esse caminho seja comprovada com um
cliente real em outra rede.** O campo "Acesso pela internet" da tela inicial
continua valendo apenas para o caminho RakNet.

## Limites desta versão

* Tokens são **estáticos** (sem provisionamento automático por sessão). Um
  token por pessoa/grupo é o modelo suportado; rotacionar = trocar o arquivo de
  serviço e reconfigurar o app.
* Sem autenticação por cliente além do token: quem tem o token pode abrir uma
  sessão. Use TLS + token longo e monitore os logs do relay.
* Sem limite de banda por sessão embutido — use `tc`, o firewall do provedor ou
  o próprio limite da VPS.
* A porta pública é escolhida na hora (`-ports`); o app mostra o endereço
  atribuído na tela inicial.
* O relay não guarda estado e não vê o conteúdo do mundo: apenas encaminha
  datagramas.

## Custo e disponibilidade

Uma VPS pequena (1 vCPU, 512 MB) atende várias sessões: o relay só move UDP,
não simula mundo. O tráfego cresce com o número de jogadores — um servidor
Bedrock com 4 jogadores fica na casa de poucos MB por minuto. Sem a VPS no ar,
o túnel simplesmente não conecta; a LAN e o port forwarding continuam
funcionando.

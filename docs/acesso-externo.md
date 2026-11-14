# Acesso pela internet — auditoria e decisão técnica

Revisão auditada: `de16bc2` (branch `arena/c8f4e5b2-moboserver`).
Este documento registra o que existe hoje, o que foi confirmado e o que **ainda não
funciona**. Nenhum teste de conexão externa real foi executado — não há VPS nem
segundo aparelho/rede neste ambiente.

## 1. Confirmado no código atual

| Ponto do pedido | Situação na revisão |
| --- | --- |
| `server/nethernet.go` configura listener e SettingEngine | Sim. `startNetherNet` monta `webrtc.SettingEngine`, aplica `SetNet`, faixa UDP (`SetEphemeralUDPPortRange`) ou porta única (`SetICEUDPMux`), e entrega `nethernet.ListenConfig.API`. A sinalização HTTP é um `net.Listen("tcp", address)` próprio. |
| `server/icenet.go` evita netlink | Sim. As interfaces vêm de um JSON escrito pelo app (`-ifaces`), o que permite `SettingEngine.SetNet` sem `netlinkrib`. Isso é o que fez a LAN funcionar no Android. |
| `relay/README.md` não atende NetherNet | Confirmado, e o próprio README diz isso. O túnel é `MOBOTUN/1`, **uma** porta UDP pública, um socket local por fluxo — desenho correto para RakNet, insuficiente para NetherNet. |
| Sinalização TCP/HTTP, jogo em UDP (WebRTC/ICE) | Confirmado. `minecraft.NetherNet` = HTTP na porta do servidor + ICE/DTLS/SCTP em UDP. |
| Túnel atual cobre só parte | Confirmado: cobre datagramas de um único endpoint por jogador; não cobre N portas públicas→N portas do agente ICE, nem TCP. |
| LAN funcionando com Bedrock 26.63 | Informado pelo usuário. A correção de `ICENet` e a autenticação Microsoft foram preservadas nesta revisão. |

## 2. Versões e APIs efetivamente disponíveis

`server/go.sum`: `pion/webrtc v4.2.21`, `pion/ice v4.4.4`, `df-mc/go-nethernet v1.0.26`,
`df-mc/dragonfly v0.11.6`, Go 1.26.

| API | Existe nesta versão | Uso |
| --- | --- | --- |
| `SettingEngine.SetICEUDPMux(ice.UDPMux)` | sim | mux de uma porta só |
| `SettingEngine.SetEphemeralUDPPortRange(min,max)` | sim | faixa de portas efêmeras (já usado) |
| `SettingEngine.SetNAT1To1IPs(ips, ICECandidateTypeHost)` | sim, **sem retorno de erro** | anuncia outro IP; a **porta não muda** |

`-nethernet-public-ip` foi adicionado nesta revisão: quando preenchido, o ICE anuncia esse
IP nos candidatos. Só é correto com mapeamento 1:1 das mesmas portas na VPS; com portas
diferentes seria preciso reescrita de porta, que a biblioteca não oferece por esse caminho.

## 3. O ponto que decide a arquitetura

Encaminhar uma faixa UDP na VPS e trocar o IP anunciado **não basta**. O agente ICE do
celular envia os *checks* para o endereço real do jogador. Atrás de CGNAT esse datagrama
sai pela rede da operadora (e o NAT do jogador descarta pacote não solicitado); ele não
passa pela VPS. Ou seja: reescrever candidato muda o que o celular **anuncia**, não por
onde ele **envia**. Só há três saídas reais:

1. **TUN/VpnService no celular** (pilha TCP/IP em espaço de usuário): o tráfego do jogo
   entra no TUN, atravessa o túnel, sai na VPS com NAT. Funciona atrás de CGNAT, exige a
   permissão `VpnService` (um prompt do sistema, sem configuração de roteador) e uma pilha
   de rede em Go (gVisor/netstack) — **dependência nova**.
2. **Mux UDP virtual** (sem TUN, sem tocar em SDP): implementar `ice.UDPMux` próprio. O
   `PacketConn` devolvido por `GetConn(ufrag, addr)` não fala com soquete real: escreve no
   túnel existente, que entrega o datagrama na VPS, emitido da porta pública atribuída. Os
   candidatos saem desse `LocalAddr`, então o endereço anunciado é, por construção,
   `VPS_IP:porta_pública` — sem edição de SDP e sem mexer em fingerprints/identidades.
   Precisa de: túnel bidirecional com alvo por datagrama, faixa de portas na VPS e
   associação ufrag↔porta.
3. Reescrita de SDP para apontar os candidatos remotos ao túnel — **proibido** no pedido e
   frágil por natureza.

A proposta do prompt (frp + faixa UDP + candidatos) corresponde ao caso 1 com frp como
transporte; o caso 2 chega ao mesmo resultado prático sem VPN e sem segundo executável.

### Por que a implementação ainda não está feita

* O caso 1 precisa de `gvisor.dev/gvisor` (ou equivalente) **e** do cliente frp
  (`github.com/fatedier/frp`) como bibliotecas. Este ambiente não tem toolchain Go e só
  alcança `github.com`/`npm`/`pypi`: não há como resolver `go.sum` de dependências novas, e
  o CI (único lugar que compila) falharia em `go mod` antes de qualquer teste.
* O caso 2 é código próprio, sem dependências novas — é o único que consigo compilar e
  testar no CI. Ele ainda **não** foi escrito.
* Nenhum dos dois pode ser dado como pronto sem a prova real: VPS com IPv4 público,
  serviço no ar e um jogador em outra rede. Não há VPS contratada (e não vou contratar sem
  autorização).

## 4. O que existe hoje para acesso externo

* `server/cmd/relay` + `server/internal/tunnel` (`MOBOTUN/1`): porta UDP pública, fluxo por
  jogador, um socket local por fluxo. Serve RakNet; **não** serve NetherNet.
* App: campo de relay/token em Avançado, estado do túnel em `RuntimeState`, classificação de
  endereços (`AddressClassifier`, 7 testes) que nunca oferece `100.64.0.0/10` como público e
  distingue *verificado* / *observado* / *WAN* / *LAN*.
* `-nethernet-public-ip` (novo): só anuncia; não cria encaminhamento.

## 5. Para colocar no ar (depende do operador)

1. VPS com IPv4 público (e, se houver domínio, DNS + certificado).
2. Serviço de provisionamento com identidade por instalação (chave Ed25519 no aparelho),
   alocação de porta TCP de sinalização + faixa UDP, TTL, renovação e liberação; nenhuma
   chave administrativa no APK.
3. Relay/agente com as portas atribuídas, TLS verificado e limites por instalação.
4. Prova real: celular em Wi-Fi atrás de CGNAT + jogador em 4G, e o inverso.

## 6. O que não está sendo afirmado

* O acesso externo **não** está operacional.
* A LAN continua como estava; nada nesta revisão altera `ICENet` nem a autenticação.
* Não há capturas, vídeos ou números de latência de conexão externa: nada disso foi medido.

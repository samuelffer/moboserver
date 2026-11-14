# Relay do MoboServer — implantação

Este diretório descreve o **único** componente que precisa de um endereço público:
o relay. O celular continua executando o mundo; ele apenas abre uma conexão de
saída para o relay.

## O que é a VPS (em português claro)

VPS é um computador alugado, ligado 24 h, com um **IPv4 público** — ou seja, um
endereço que qualquer pessoa na internet alcança. O celular, atrás do NAT da
operadora, não tem isso. Por isso o jogo não pode ser “aberto” direto no celular:
não existe porta de entrada para a internet na conexão móvel, e o roteador do
hospedeiro não resolve (o NAT é da operadora, não dele).

O relay roda nessa VPS e faz a ponte:

```
 jogador ──UDP──►  VPS:porta pública  ──túnel (saída do celular)──►  celular:ice
        ◄────────                    ◄──────────────────────────
```

Sem VPS o aplicativo continua funcionando **na rede local** e mostra, com todas as
letras, que o acesso externo não está configurado. Nada é prometido por engano.

## O que rodar na VPS

1. Compile o relay (mesmo módulo Go do servidor):

   ```bash
   cd server
   GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o mobosrv-relay ./cmd/relay
   ```

2. Suba com token e faixa de portas:

   ```bash
   sudo install -m 0755 mobosrv-relay /usr/local/bin/mobosrv-relay
   sudo mkdir -p /etc/moboserver
   echo 'MOBOSRV_TOKEN=troque-este-valor' | sudo tee /etc/moboserver/relay.env
   sudo systemctl enable --now mobosrv-relay
   ```

   O unit `mobosrv-relay.service` deste diretório usa:

   * `-listen :9000` — túnel RakNet (MOBOTUN/1, uma porta por sessão);
   * `-udp-listen :9001` — túnel por faixa (MOBOTUN/2), usado pelo ICE do
     NetherNet: uma porta pública por conexão;
   * `-ports 19200-19240` — faixa liberada no firewall;
   * `-tokens` — vem do arquivo de ambiente do systemd.

3. Firewall (ufw):

   ```bash
   sudo ufw allow 9000/tcp
   sudo ufw allow 9001/tcp
   sudo ufw allow 19200:19240/udp
   sudo ufw enable
   ```

4. Verificação de saúde: `systemctl status mobosrv-relay` e
   `ss -lunpt | grep mobosrv-relay`.

## Como o aplicativo usa

O app precisa de três valores, todos do operador (nunca digitados pelo usuário
comum quando o provisionamento automático estiver ligado):

| Valor | Flag do servidor | Exemplo |
| --- | --- | --- |
| endereço de controle | `-tunnel` | `relay.exemplo.net:9001` |
| token da instalação | `-tunnel-token` | `…` |
| IPv4 público do relay | `-relay-public-ip` | `203.0.113.10` |
| portas pedidas | `-relay-udp-ports` | `4` |

Com isso o ICE anuncia `203.0.113.10:<porta atribuída>` e os datagramas passam
pelo túnel nos dois sentidos. Sem esses valores o servidor sobe normalmente e
publica apenas a LAN.

## Estado atual e limites

* O caminho de dados (porta pública ↔ conexão ICE ↔ resposta ao jogador) está
  coberto por testes locais (`TestRelayMuxEntregaDatagramas`,
  `TestUDPRelayPreservaPortaEOrigem`).
* **Não** há prova de conexão real: falta um host com IPv4 público e um segundo
  aparelho/rede. Enquanto isso, o acesso externo é “não verificado” no app.
* TLS no controle ainda não está implementado no MOBOTUN/2 (o modo 1 já tem
  `-tunnel-tls`). Enquanto não houver domínio e certificado, o token viaja em
  texto claro dentro do TCP — trate o token como descartável e revogável.
* Limite de portas por sessão é `-udp-ports` (padrão 8): dimensione a faixa do
  firewall para `8 × instalações simultâneas`.

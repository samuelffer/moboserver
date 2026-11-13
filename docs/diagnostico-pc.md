# Diagnóstico pelo PC (Windows)

Serve para separar **"o servidor não responde"** de **"a rede não entrega o
pacote"**. Rode com o servidor ligado no celular.

## 1. O PC alcança o celular por TCP?

Abra no navegador do PC: `http://192.168.1.106:8080` (o endereço aparece na tela
inicial do app).

* **Abriu** → TCP funciona entre os dois aparelhos.
* **Não abriu** → rede/roteador (isolamento de AP, redes diferentes). Nada no
  app resolve isso.

## 2. O PC alcança o servidor por UDP? (o teste que decide)

O Minecraft usa UDP 19132. No PC, abra o **PowerShell** e cole:

```powershell
$ip="192.168.1.106"; $port=19132
$u=[System.Net.Sockets.UdpClient]::new()
$u.Client.ReceiveTimeout=3000
$magic=[byte[]](0,255,255,0,254,254,254,254,253,253,253,253,18,52,86,120)
$msg=[byte[]]@(0x01)+[BitConverter]::GetBytes([int64]1)+$magic+[BitConverter]::GetBytes([int64]1)
$u.Send($msg,$msg.Length,$ip,$port) | Out-Null
$ep=[System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any,0)
try { $d=$u.Receive([ref]$ep); [Text.Encoding]::UTF8.GetString($d[35..($d.Length-1)]) }
catch { "SEM RESPOSTA: UDP $ip`:$port nao chegou ao servidor" }
```

Resultados possíveis:

| Saída | Significado |
| --- | --- |
| `MCPE;Nome do servidor;2225;1.26.60;...;19132;19132;...` | O servidor respondeu: o caminho UDP funciona. Se o Minecraft não conecta, o problema é de **versão do cliente** (veja abaixo). |
| `SEM RESPOSTA` (após 3 s) | Nenhum datagrama chegou ao servidor. É rede (firewall do roteador, isolamento, VLAN) — **não é o app**. |

O campo 3 da resposta (`2225`) e o 4 (`1.26.60`) são o protocolo e a versão que o
servidor fala. Um cliente com versão/protocolo diferente não entra, mesmo com a
rede perfeita.

## 3. Versão do cliente

O Minecraft Bedrock do Windows mostra a versão na tela inicial (canto inferior) e
em *Configurações → Sobre*. Precisa ser **compatível com o protocolo do
servidor** (o app mostra `Bedrock <versão> • protocolo <n>` na tela inicial).

Se o PC usa **Minecraft Java Edition**, não há como conectar: Java e Bedrock são
protocolos diferentes.

## 4. NetherNet (clientes por endereço)

O app agora sobe **dois transportes na mesma porta**:

* **RakNet (UDP 19132)** — jogo e descoberta na rede local;
* **NetherNet (TCP 19132)** — sinalização HTTP em `/v1/join`, usada por clientes
  atuais antes de cair no RakNet (mídia em UDP 19133-19140).

A sinalização responde com um JSON que traz o nome e o protocolo do servidor
(é o mesmo dado que o cliente usa antes de negociar a conexão). No PC:

```powershell
(Invoke-WebRequest -UseBasicParsing "http://192.168.1.106:19132/v1/join").StatusCode
```

Um `200` significa que o endpoint está no ar. `200` sozinho não quer dizer que
a negociação funciona: no aparelho, o app também cria o agente ICE e conta os
candidatos (cartão "Entrada pelo endereço"). "Unable to connect" significa que a
porta TCP não está aberta ou acessível.

O app não usa HTTPS ali (não tem certificado de domínio público), então o cliente
pode pedir confirmação de confiança na primeira conexão — aceite para entrar.

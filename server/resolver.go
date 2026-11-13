package main

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"strings"
	"time"
)

// O Android não usa /etc/resolv.conf: os apps resolvem nomes pelo netd
// (Java/Kotlin) ou pelo libc ligado ao Bionic. Um binário Go puro, com o
// resolvedor próprio, acaba tentando [::1]:53 e falha com "connection refused",
// o que derruba qualquer acesso HTTPS — inclusive a validação do Xbox Live.
//
// A saída é o app mandar, por linha de comando, os servidores DNS que o Android
// está usando naquela rede; aqui instalamos um resolvedor que fala direto com
// eles.

// setupResolver aponta o resolvedor padrão do Go para os servidores informados.
func setupResolver(servers []string) {
	cleaned := make([]string, 0, len(servers))
	for _, server := range servers {
		server = strings.TrimSpace(server)
		if server == "" {
			continue
		}
		if net.ParseIP(server) == nil {
			continue
		}
		cleaned = append(cleaned, server)
	}
	if len(cleaned) == 0 {
		return
	}

	dialer := &net.Dialer{Timeout: 3 * time.Second}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			proto := "udp"
			if strings.HasPrefix(network, "tcp") {
				proto = "tcp"
			}
			var lastErr error = fmt.Errorf("nenhum servidor DNS disponivel")
			for _, server := range cleaned {
				conn, err := dialer.DialContext(ctx, proto, net.JoinHostPort(server, "53"))
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
	emitLog("info", "DNS em uso: "+strings.Join(cleaned, ", "))
}

// xboxAuthReachable testa se o serviço de descoberta da Microsoft responde.
// Devolve o erro para que a falha apareça exatamente como aconteceu (DNS, TLS,
// HTTP), em vez de um "sem internet" genérico.
func xboxAuthReachable(ctx context.Context, version string) error {
	url := fmt.Sprintf(
		"https://client.discovery.minecraft-services.net/api/v1.0/discovery/MinecraftPE/builds/%s",
		version,
	)
	client := &http.Client{Timeout: 10 * time.Second}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "MoboServer/"+appVersion)
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 500 {
		return fmt.Errorf("o serviço respondeu HTTP %d", resp.StatusCode)
	}
	return nil
}

package main

import (
	"crypto/x509"
	"os"
	"path/filepath"
	"sync"
)

// Android does not ship a CA bundle in the paths the Go runtime looks at
// (/etc/ssl/certs/ca-certificates.crt and friends). A pure Go binary therefore
// starts with an empty root pool and every HTTPS request fails with
// "x509: certificate signed by unknown authority" — including the Xbox Live
// discovery used to validate players. Go 1.20+ lets a program provide fallback
// roots, which are used only when no system roots were found, so adding them
// here keeps full certificate verification.
var rootsOnce sync.Once

// androidCertDirs are the locations of the system trust store. The APEX path is
// where Android 14+ keeps the Conscrypt copy of the same certificates.
var androidCertDirs = []string{
	"/system/etc/security/cacerts",
	"/apex/com.android.conscrypt/cacerts",
}

func installAndroidRoots() (int, bool) {
	loaded := 0
	pool := x509.NewCertPool()
	for _, dir := range androidCertDirs {
		entries, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, entry := range entries {
			if entry.IsDir() {
				continue
			}
			data, err := os.ReadFile(filepath.Join(dir, entry.Name()))
			if err != nil {
				continue
			}
			if pool.AppendCertsFromPEM(data) {
				loaded++
			}
		}
	}
	if loaded == 0 {
		return 0, false
	}
	rootsOnce.Do(func() { x509.SetFallbackRoots(pool) })
	return loaded, true
}

// setupTrustStore installs the Android roots and reports what happened, so the
// app can tell "sem raízes de confiança" apart from "a rede não alcança a
// Microsoft".
func setupTrustStore() {
	n, ok := installAndroidRoots()
	switch {
	case !ok:
		emitLog("warn", "Não encontrei os certificados do Android: conexões HTTPS podem falhar "+
			"(validação de certificado continua ativa).")
	case n > 0:
		emitLog("info", "Certificados de confiança do Android carregados: "+itoa(n))
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}

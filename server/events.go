package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"sync"
)

// The server talks to the Android app over stdout using one JSON object per
// line. Every object has a "t" field describing its type:
//
//	{"t":"ready","port":19132,...}
//	{"t":"log","lvl":"info","msg":"..."}
//	{"t":"join","name":"Steve","xuid":"123","device":"Android"}
//	{"t":"quit","name":"Steve","xuid":"123"}
//	{"t":"players","items":[...]}
//	{"t":"fatal","msg":"..."}
//	{"t":"stopped"}
//
// Nothing else is ever written to stdout, so the app can parse every line as
// JSON. Stderr is redirected into stdout by the app as well.
var emitMu sync.Mutex

// minLogLevel filters what reaches the app. INFO is the default (so the console
// is readable); DEBUG is only enabled from "Diagnostico avancado" in the app.
var minLogLevel = slog.LevelInfo

// setMinLogLevel changes the minimum level written to the app.
func setMinLogLevel(level slog.Level) {
	emitMu.Lock()
	minLogLevel = level
	emitMu.Unlock()
}

// levelValue converts the level name used in events to a slog level.
func levelValue(name string) slog.Level {
	switch name {
	case "debug":
		return slog.LevelDebug
	case "warn":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}

func emit(v any) {
	b, err := json.Marshal(v)
	if err != nil {
		return
	}
	b = append(b, '\n')
	emitMu.Lock()
	_, _ = os.Stdout.Write(b)
	emitMu.Unlock()
}

func emitLog(level, msg string) {
	if levelValue(level) < currentLogLevel() {
		return
	}
	emit(map[string]any{"t": "log", "lvl": level, "msg": msg})
}

func currentLogLevel() slog.Level {
	emitMu.Lock()
	defer emitMu.Unlock()
	return minLogLevel
}

// eventHandler is a slog.Handler that forwards Dragonfly's log records to the
// Android app as structured events.
type eventHandler struct{}

// Enabled keeps every record, including debug: the network layer of go-raknet
// reports read/handshake errors only at debug level, and those records carry
// the remote address, which is the only evidence available when a client
// cannot reach the server.
func (eventHandler) Enabled(_ context.Context, l slog.Level) bool { return l >= currentLogLevel() }

func (eventHandler) Handle(_ context.Context, r slog.Record) error {
	msg := r.Message
	var attrs []string
	r.Attrs(func(a slog.Attr) bool {
		attrs = append(attrs, fmt.Sprintf("%s=%v", a.Key, a.Value.Any()))
		return true
	})
	if len(attrs) > 0 {
		msg = msg + " (" + strings.Join(attrs, ", ") + ")"
	}
	emitLog(slogLevelName(r.Level), msg)
	return nil
}

func (h eventHandler) WithAttrs([]slog.Attr) slog.Handler { return h }
func (h eventHandler) WithGroup(string) slog.Handler      { return h }

func slogLevelName(l slog.Level) string {
	switch {
	case l >= slog.LevelError:
		return "error"
	case l >= slog.LevelWarn:
		return "warn"
	case l >= slog.LevelInfo:
		return "info"
	default:
		return "debug"
	}
}

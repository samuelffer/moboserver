# Atalhos de build do MoboServer.
.PHONY: server server-arm64 apk test clean

GO ?= go
ANDROID_DIR := android
JNI_DIR := $(ANDROID_DIR)/app/src/main/jniLibs/arm64-v8a

## Compila o servidor para o host (usado no self-test)
server:
	cd server && $(GO) build -trimpath -o ../build/mobosrv .

## Roda o self-test do servidor (sobe um servidor real e faz ping)
test: server
	./build/mobosrv -selftest

## Compila o servidor para Android arm64 dentro do projeto Android
server-arm64:
	mkdir -p $(JNI_DIR)
	cd server && CGO_ENABLED=0 GOOS=linux GOARCH=arm64 $(GO) build -buildmode=pie \
		-trimpath -ldflags="-s -w" -o ../$(JNI_DIR)/libmobosrv.so .
	python3 scripts/patch-android-elf.py $(JNI_DIR)/libmobosrv.so

## Gera o APK release
apk: server-arm64
	cd $(ANDROID_DIR) && gradle assembleRelease

clean:
	rm -rf build $(JNI_DIR) $(ANDROID_DIR)/app/build $(ANDROID_DIR)/.gradle

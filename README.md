# ReplayCam (Android)

Aplicativo Android focado em gravação contínua de câmera para capturar replays dos **últimos 20 segundos**.

## Funcionalidades principais

- **Começar gravação contínua:** grava continuamente em segmentos de 5 segundos na melhor qualidade suportada (prioridade: UHD > FHD > HD > SD).
- **Salvar replay (20s):** ao tocar no botão de replay, o app salva os 4 segmentos mais recentes (20s) em uma pasta de replay.

> Observação: neste MVP, o replay é salvo como um pacote com 4 arquivos MP4 (`part_1` a `part_4`) para manter estabilidade e performance no dispositivo durante os testes.

## Requisitos

- Android Studio Iguana+ ou Gradle 8+
- SDK Android 34
- Dispositivo Android com câmera traseira
- ADB configurado para instalação direta

## Build local

```bash
gradle assembleDebug
```

APK gerado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Instalar direto no celular (pipeline local)

1. Conecte o celular via USB e habilite depuração USB.
2. Execute:

```bash
bash scripts/build_and_install.sh
```

Esse script:

- compila o APK debug,
- detecta dispositivo conectado,
- instala/reinstala o APK automaticamente via `adb install -r`.

## Pipeline CI (GitHub Actions)

Workflow em `.github/workflows/android-apk.yml`:

- builda o APK debug a cada push/PR/manual (`workflow_dispatch`),
- prepara um artefato pronto para download direto (`replaycam-debug.apk`),
- publica checksum SHA-256 para validação,
- publica artefato com retenção de 14 dias.

### Baixar artefato da CI e instalar no device

Você pode baixar o último artifact bem-sucedido e instalar no celular com:

```bash
bash scripts/download_ci_artifact_and_install.sh <owner/repo>
```

Exemplo:

```bash
bash scripts/download_ci_artifact_and_install.sh meuusuario/replaycam
```

Requisitos desse fluxo: `gh` autenticado, `jq`, `adb` e device Android conectado.

# ReplayCam (Android)

Aplicativo Android focado em gravação contínua de câmera para capturar replays dos **últimos 20 segundos**.

## Funcionalidades principais

- **Começar gravação contínua:** grava continuamente em segmentos de 5 segundos na melhor qualidade suportada (prioridade: UHD > FHD > HD > SD).
- **Salvar replay (20s):** ao tocar no botão de replay, o app salva os 4 segmentos mais recentes (20s) em uma pasta de replay.

> Observação: neste MVP, o replay é salvo como um pacote com 4 arquivos MP4 (`part_1` a `part_4`) para manter estabilidade e performance no dispositivo durante os testes.

## Requisitos

- Android Studio Iguana+ ou Gradle 8.x (recomendado 8.7)
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
2. Execute (modo padrão):

```bash
bash scripts/build_and_install.sh
```

Esse script tenta build local e instala via `adb install -r`.

### Fallback automático para CI (quando build local falhar por rede/repositório)

Se você já sabe o repo GitHub (`owner/repo`), use:

```bash
bash scripts/build_and_install.sh <owner/repo>
```

Ou configure a variável de ambiente:

```bash
export REPLAYCAM_REPO_SLUG=<owner/repo>
bash scripts/build_and_install.sh
```

Nesse modo, se o build local falhar, o script baixa e instala automaticamente o último artifact bem-sucedido (`replaycam-debug-apk`) da pipeline.

Se você não passar `owner/repo`, o script tenta inferir automaticamente a partir do `git remote origin` (GitHub HTTPS/SSH).

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

# ReplayCam (Android)

Aplicativo Android focado em gravação contínua de câmera para capturar replays dos **últimos 20 segundos**.

## Funcionalidades principais

- **Começar gravação contínua:** mantém os últimos 20s em cache temporário (segmentos de 5s), sem salvar automaticamente na galeria.
- **Salvar replay (20s):** ao tocar no botão, o app salva na galeria exatamente os 20s anteriores ao acionamento em um único arquivo MP4.

> Observação: apenas a ação de replay grava na galeria (`Movies/ReplayCam`). A gravação contínua permanece em cache temporário.

## Requisitos

- Android Studio Iguana+ ou Gradle 8.x (recomendado 8.7)
- SDK Android 34
- Dispositivo Android com câmera traseira
- ADB configurado para instalação direta

## Build local

```bash
./gradlew assembleDebug
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

Nesse modo, se o build local falhar, o script dispara um novo workflow na CI, espera finalizar e baixa/instala o artifact versionado atualizado da run.

Quando o ambiente não consegue acessar repositórios de build (Maven/Google), o script detecta isso e já pula direto para o fallback da CI.

Se você não passar `owner/repo`, o script tenta inferir automaticamente a partir do `git remote origin` (GitHub HTTPS/SSH).

## Pipeline CI (GitHub Actions)

Workflow em `.github/workflows/android-apk.yml`:

- builda o APK debug a cada push/PR/manual (`workflow_dispatch`),
- prepara artefatos versionados por execução/PR (ex.: `replaycam-debug-pr-12-run-34.apk`),
- publica checksum SHA-256 para validação,
- publica artefato com retenção de 14 dias.

### Baixar artefato da CI e instalar no device

Você pode gerar um artifact novo na CI e instalar no celular com:

```bash
bash scripts/download_ci_artifact_and_install.sh <owner/repo> [ref]
```

Exemplo:

```bash
bash scripts/download_ci_artifact_and_install.sh meuusuario/replaycam main
```

Requisitos desse fluxo: `gh` autenticado, `jq`, `adb` e device Android conectado.

Se `ref` não for informado, o script usa a branch atual local (quando disponível) e, em último caso, a branch padrão do repositório.

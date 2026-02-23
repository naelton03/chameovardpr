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

## Configuração OAuth (Google Sign-In / YouTube Live)

No arquivo `app/src/main/res/values/strings.xml`, preencha `google_web_client_id` com o **OAuth Web Client ID**
(`...apps.googleusercontent.com`) do mesmo projeto no Google Cloud Console.

> Não use o Android Client ID nesse campo.


### Checklist obrigatório no Google Cloud (para evitar Erro 10/12500)

1. Projeto `replaycam` com **YouTube Data API v3** habilitada.
2. `google_web_client_id` preenchido com o OAuth **Aplicativo da Web**:
   `698685113444-d1926mfoqamcqehcp5bug9423ql8p1fg.apps.googleusercontent.com`
3. Tela de consentimento OAuth: adicionar explicitamente o e-mail do desenvolvedor em **Usuários de teste**
   (necessário porque `youtube.force-ssl` é escopo sensível).
4. OAuth Android: `packageName=com.example.replaycam` e SHA-1 igual ao SHA do APK que está rodando
   (compare com o log `GOOGLE_OAUTH_DEBUG_INFO`).
5. O app captura o `idToken` no retorno do Google Sign-In; se vier vazio, revise o `google_web_client_id`.
6. Se o ambiente de debug mudar e gerar outro certificado, atualize o novo SHA-1 manualmente no Console.

## SHA-1/SHA-256 estável para Google Cloud

Se o SHA muda a cada build/dispositivo, o app está sendo assinado com chaves diferentes.
Gere uma chave única e reutilize sempre a mesma para debug/release:

```bash
bash scripts/setup_oauth_keystore.sh
```

Depois adicione no seu `~/.gradle/gradle.properties`:

```properties
REPLAYCAM_KEYSTORE_PATH=/workspace/chameovardpr/.keystore/replaycam-oauth.jks
REPLAYCAM_KEY_ALIAS=replaycamoauth
REPLAYCAM_STORE_PASSWORD=replaycam123
REPLAYCAM_KEY_PASSWORD=replaycam123
```

O script imprime os fingerprints SHA-1 e SHA-256 para cadastrar no Google Cloud Console (OAuth Android).


> Se o app em execução ainda mostrar outro SHA (ex.: no log `GOOGLE_OAUTH_DEBUG_INFO`), desinstale a versão antiga e reinstale o APK assinado pela keystore estável.
> Enquanto isso, você pode cadastrar temporariamente esse SHA antigo em um segundo OAuth Android Client para não bloquear login.

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

Nesse modo, se o build local falhar, o script dispara um novo workflow na CI, espera finalizar e baixa/instala o artifact versionado atualizado da run.

Quando o ambiente não consegue acessar repositórios de build (Maven/Google), o script detecta isso e já pula direto para o fallback da CI.

Se você não passar `owner/repo`, o script tenta inferir automaticamente a partir do `git remote origin` (GitHub HTTPS/SSH).


## Dependência RTMP (PedroSG94)

A funcionalidade de live usa classes `RtmpCamera2` e `ConnectChecker` da biblioteca PedroSG94.
Se houver erro `ClassNotFoundException`/`SDK RTMP ausente no APK`, confirme que esta dependência
foi resolvida no build:
- `com.github.pedroSG94.RootEncoder:rtmp:2.4.8`

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


## Versão cross-platform (Android + iOS)

Para atender ao rebuild cross-platform, foi adicionada uma base React Native/Expo em `cross-platform/replaycam-mobile`.

- Guia técnico: `docs/cross-platform-rebuild.md`
- App novo: `cross-platform/replaycam-mobile`

Essa base replica o fluxo principal do ReplayCam (preview em tela, segmentos de 5s, buffer 20s, replay/exportação, permissões, retomada foreground/background, galeria, diagnóstico e bloco live por feature flag) e já organiza o projeto para evoluir as integrações nativas finais nos dois sistemas.

## Documentação funcional

- Veja o detalhamento atualizado em `docs/funcionalidades-atuais.md`.
- Branding completo da aplicação: `docs/branding-replayon.md`.

## UI React Native (referência visual)

- Exemplo de refactor para estilo nativo de câmera: `docs/react-native-replaycam-ui-refactor.tsx`.

# Rebuild cross-platform (Android + iOS)

A base cross-platform do ReplayCam está em `cross-platform/replaycam-mobile` e foi atualizada para ficar **fiel ao fluxo do Android antigo**.

## O que foi implementado no app cross-platform

- Preview de câmera em tela cheia com painel superior/inferior no estilo do app Android.
- Gravação contínua em **segmentos de 5s**.
- Buffer de replay com janela dos **últimos 20s**.
- Controle de zoom por níveis (ex.: 1.0x, 1.5x, 2.0x...) com indicação do zoom máximo da câmera selecionada.
- Ações de:
  - iniciar gravação,
  - salvar replay,
  - parar e exportar sessão completa.
- Retomada/pause automática ao trocar entre foreground/background.
- Solicitação de permissões de câmera, microfone e galeria.
- Acesso rápido à galeria/pasta (fallback por deep link + cópia de caminho para clipboard quando falhar).
- Logging/diagnóstico em arquivo local (`documentDirectory/replaycam-logs/diagnostics.log`) com handlers globais de erro no app.
- Módulo LIVE exposto na UI, porém desativado por feature flag (`FeatureToggles.isLiveEnabled = false`), alinhado ao comportamento atual do Android.
- Menu com **Configurar upload automático** para Google Drive: vincular/desvincular conta e ativar/desativar upload automático, com OAuth Google real e upload para pasta por data.


### UI visível alinhada ao Android atual

A UI visível está alinhada ao fluxo atual descrito pelo produto:
- card superior com tempo de gravação e status (incluindo zoom atual),
- botão dinâmico **Gravar/Parar**,
- botão **Salvar replay (20s)**.

Demais recursos permanecem no código, mas não expostos visualmente por padrão. O menu superior mantém as configurações de upload automático do Drive (igual ao Android antigo).


### Regra de replay validada

- Se o usuário salvar com menos de 20s de gravação (ex.: 5s, 10s, 15s), o app salva exatamente o que já foi gravado até o momento.
- Se salvar com 20s, salva os 20s completos.
- Se salvar após 20s (ex.: 40s), salva os últimos 20s da janela de replay.
- Replays consecutivos durante gravação são serializados para evitar disputa de segmento e perda de estado.
- Ao parar a gravação, exporta a sessão inteira gravada até o momento.

## Limitações técnicas atuais

A integração de Drive usa a mesma credencial Web Client ID do Android antigo (`google_web_client_id`).

- Replay e exportação usam composição real por segmentos via FFmpeg (concat/trim). Em ambientes sem FFmpeg nativo disponível no app, esse fluxo pode falhar até o módulo estar instalado corretamente.
- Integração completa YouTube Live + RTMP ainda depende de módulo nativo dedicado em RN/Expo.

## Como rodar

```bash
cd cross-platform/replaycam-mobile
npm install
npm run android
npm run ios
```

> `npm run ios` requer macOS + Xcode.

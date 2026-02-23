# Rebuild cross-platform (Android + iOS)

A base cross-platform do ReplayCam está em `cross-platform/replaycam-mobile` e foi atualizada para ficar **fiel ao fluxo do Android antigo**.

## O que foi implementado no app cross-platform

- Preview de câmera em tela cheia com painel superior/inferior no estilo do app Android.
- Gravação contínua em **segmentos de 5s**.
- Buffer de replay com janela dos **últimos 20s**.
- Ações de:
  - iniciar gravação,
  - salvar replay,
  - parar e exportar sessão completa.
- Retomada/pause automática ao trocar entre foreground/background.
- Solicitação de permissões de câmera, microfone e galeria.
- Acesso rápido à galeria/pasta (fallback por deep link).
- Logging/diagnóstico em arquivo local (`documentDirectory/replaycam-logs/diagnostics.log`).
- Módulo LIVE exposto na UI, porém desativado por feature flag (`FeatureToggles.isLiveEnabled = false`), alinhado ao comportamento atual do Android.

## Limitações técnicas atuais

- Sem compositor nativo de vídeo, replay e exportação usam fallback (cópia de segmento) quando seria necessário concatenação real de múltiplos segmentos.
- Integração completa Google Sign-In + YouTube RTMP ainda depende de módulo nativo dedicado em RN/Expo.

## Como rodar

```bash
cd cross-platform/replaycam-mobile
npm install
npm run android
npm run ios
```

> `npm run ios` requer macOS + Xcode.

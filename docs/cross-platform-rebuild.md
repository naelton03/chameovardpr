# Rebuild cross-platform (Android + iOS)

Foi criado um novo app em **React Native + Expo** em `cross-platform/replaycam-mobile` para servir como base única de código para Android e iOS.

## O que já foi reconstruído

- Tela principal com o mesmo fluxo funcional do app Android atual:
  - iniciar gravação contínua,
  - salvar replay dos últimos 20s,
  - parar e exportar gravação completa,
  - seleção de câmera traseira por capacidade,
  - estado/status em tempo real,
  - bloco de LIVE (YouTube/RTMP).
- Controlador de estado (`useReplayController`) que centraliza a lógica de sessão.
- Motor de replay (`ReplayEngine`) com interfaces preparadas para trocar mocks por implementação nativa real.

## O que falta para paridade de produção

1. Substituir os métodos simulados do `ReplayEngine` por integrações reais:
   - captura contínua por segmentos,
   - concatenação de segmentos em replay/sessão completa,
   - escrita em galeria (MediaStore Android + Photos iOS),
   - fluxo RTMP e OAuth YouTube.
2. Adicionar adaptadores nativos por plataforma (expo modules / native modules) para recursos que exigem API de baixo nível.
3. Habilitar telemetria e logger de erros em arquivo, equivalente ao Android atual.

## Como rodar

```bash
cd cross-platform/replaycam-mobile
npm install
npm run android
npm run ios
```

> Observação: `npm run ios` requer ambiente macOS com Xcode.

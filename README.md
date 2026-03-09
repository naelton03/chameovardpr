# ReplayCam (cross-platform)

Base única do projeto para **Android e iOS** usando React Native/Expo.

## Código-fonte ativo

```text
cross-platform/replaycam-mobile
```

## Como rodar

```bash
cd cross-platform/replaycam-mobile
npm install
npm run android
npm run ios
```

> `npm run ios` requer macOS + Xcode.

## Funcionalidades principais

- Gravação contínua em segmentos de 5s.
- Replay configurável (10s a 40s).
- Exportação da sessão completa.
- Configuração de upload automático no Google Drive.
- Logging de diagnóstico e fallback para abertura da galeria.

## Documentação

- Guia técnico do rebuild: `docs/cross-platform-rebuild.md`
- Funcionalidades atuais: `docs/funcionalidades-atuais.md`
- Matriz de validação: `docs/replay-validation-matrix.md`
- Branding: `docs/branding-replayon.md`

# Feature: qualidade de gravação por capacidade do dispositivo

## Regra de negócio
- O app deve exibir configurações de gravação com base apenas no que o dispositivo suporta.
- O usuário escolhe FPS e qualidade antes de gravar.
- Não podem aparecer opções incompatíveis com o hardware.

## Implementação
- Leitura de capacidade da câmera selecionada (`maxFps`, `maxResolution`).
- Derivação dinâmica das opções:
  - FPS: somente `30` e/ou `60` quando suportados.
  - Qualidade: `4K`, `1080`, `720` conforme resolução máxima disponível.
- Persistência da preferência (`recordingFps`, `recordingQuality`) em arquivo de prefs.
- Aplicação no engine via `videoBitrate` adaptativo por perfil de qualidade+fps.

## Perfis de bitrate aplicados
- 4K/60: 53 Mbps
- 4K/30: 35 Mbps
- 1080/60: 12 Mbps
- 1080/30: 8 Mbps
- 720/60: 7 Mbps
- 720/30: 5 Mbps

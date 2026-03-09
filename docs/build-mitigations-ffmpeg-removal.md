# Mitigação de build Android: remoção do ffmpeg-kit

## Problema
O build Android falhava ao resolver `com.arthenica:ffmpeg-kit-https:6.0-2` via Gradle.

## Mitigação aplicada
- Removida a dependência `ffmpeg-kit-react-native` do `package.json`.
- `videoComposer` foi adaptado para operar sem FFmpeg:
  - caminho ideal: cópia direta quando há segmento único sem trim;
  - fallback: cópia do último segmento quando seria necessário concat/trim avançado.

## Impacto funcional
- Elimina a causa raiz da falha de resolução Maven no Android CI.
- Replay continua salvando arquivo MP4.
- Em cenários de múltiplos segmentos, a composição fica degradada (sem concatenação/trim fino) até adoção de uma alternativa estável de composição.

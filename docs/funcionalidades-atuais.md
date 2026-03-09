# Documentação de funcionalidades atuais — ReplayCam

Este documento descreve **o que o app contém hoje** com base na implementação atual.

## 1) Captação contínua com buffer de replay

- O app grava continuamente em segmentos temporários de vídeo no cache interno.
- O buffer ativo mantém a janela dos **últimos 20 segundos** para replay imediato.
- A gravação contínua só fica em cache; não é salva automaticamente na galeria.

## 2) Salvar replay dos últimos 20s

- Ao tocar em **Salvar replay (20s)**, o app fecha o segmento atual, seleciona os segmentos mais recentes e monta um MP4 único.
- O replay é salvo na galeria em `Movies/ReplayCam` (ou caminho equivalente em versões antigas do Android).
- O arquivo final recebe nome no padrão `replay_<timestamp>.mp4`.

## 3) Salvar gravação completa da sessão

- Ao tocar em **Parar gravação**, além de encerrar a captura contínua, o app consolida os segmentos gravados na sessão.
- Essa consolidação gera um MP4 completo com toda a sessão corrente.
- O resultado é salvo na galeria como `recording_<timestamp>.mp4`.

## 4) Seleção de câmera traseira por capacidade

- O app detecta as câmeras traseiras disponíveis no dispositivo.
- Exibe opções com dados de capacidade em tempo de execução (zoom, FPS máximo, resolução máxima e indicação de multi-câmera lógica).
- Troca de câmera é bloqueada enquanto a gravação estiver em andamento.

## 5) Retomada automática após ir para segundo plano

- Se o app for para background durante gravação contínua, ele pausa a gravação.
- Ao voltar para foreground (com permissões válidas), pode retomar automaticamente a gravação contínua.

## 6) Temporizador e status em tempo real

- O app mostra cronômetro de gravação no layout principal.
- Também exibe mensagens de status para ações e erros (câmera, buffer, salvamento e live quando aplicável).

## 7) Acesso rápido à galeria/pasta de vídeos

- Há botão para abrir a galeria de vídeos do dispositivo.
- Se a abertura direta falhar, o app copia o caminho de saída para a área de transferência e informa o usuário.

## 8) Gestão de permissões e compatibilidade Android

- Solicita permissões de câmera e áudio em runtime.
- Em Android antigo, inclui permissão de escrita externa quando necessário.
- O app trata diferenças de salvamento entre Android Q+ (MediaStore com `RELATIVE_PATH`) e versões anteriores.

## 9) Diagnóstico e logging de erros

- Possui logger com handlers globais para capturar exceções e eventos importantes.
- Registra logs diagnósticos em arquivo para facilitar suporte e depuração.
- Também possui rotina para solicitar acesso ampliado a arquivos (quando aplicável) para logging em raiz.

## 10) Módulo de live YouTube/RTMP (implementado, porém desativado por feature flag)

- O código de live está presente (Google Sign-In, criação de sessão no YouTube, ingest RTMP, callbacks de conexão e fallback para RTMP sem TLS).
- A UI de live (título, privacidade, botão LIVE e surface de stream) também existe.
- **Estado atual:** a funcionalidade está desligada por `FeatureToggles.isLiveEnabled = false`.

---

## Resumo executivo

Hoje o app já cobre bem o caso principal de uso: **gravar continuamente, salvar replay dos últimos 20s e exportar a sessão completa para a galeria**, com suporte a seleção de câmera, status em tela e diagnóstico. A base de live já está no projeto, mas permanece inativa por toggle.

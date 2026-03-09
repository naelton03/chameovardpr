# Feature: classificação obrigatória ao salvar replay

## Refinamento de negócio

### Objetivo
Garantir que cada replay salvo seja classificado pelo operador no momento do salvamento, para facilitar organização e busca posterior dos lances.

### Regras funcionais
1. Ao tocar em **Salvar replay**, o app deve abrir imediatamente uma modal com a pergunta: **"O que foi este lance ?"**.
2. A modal deve conter exatamente 3 opções de seleção única:
   - **GOL**
   - **DEFESA**
   - **LANCE**
3. Nenhuma opção deve vir pré-selecionada.
4. A modal não pode ter botão de fechar/sair e não deve permitir sair sem escolher uma opção.
5. Deve existir apenas um botão de ação: **Salvar**, posicionado no canto da modal.
6. O botão **Salvar** deve iniciar desabilitado e só habilitar quando houver opção selecionada.
7. Ao confirmar, o tipo escolhido deve ser persistido junto ao nome do arquivo salvo, com prefixo correspondente (ex.: `GOL_replay_...mp4`).

### Critérios de aceite
- [x] Modal abre ao acionar salvar replay.
- [x] Seleção é obrigatória e única.
- [x] Sem opção de fechar/cancelar na modal.
- [x] Botão de salvar desabilitado sem seleção.
- [x] Nome do arquivo final inclui prefixo da classificação selecionada.

## Refinamento técnico

### Camadas alteradas
- **UI (`ReplayScreen`)**
  - Intercepta o clique de salvar replay para abrir modal de classificação.
  - Mantém estado local para tipo selecionado.
  - Bloqueia confirmação até seleção válida.

- **Estado/Controller (`useReplayController`)**
  - `saveReplay` passou a receber opcionalmente `playType`.
  - Mensagem de status inclui classificação quando informada.

- **Engine (`ReplayEngine`)**
  - `saveReplayWindow` recebe `playType` opcional.
  - Nome de arquivo passa a usar prefixo `<PLAYTYPE>_` quando classificação é enviada.

- **Tipos (`types/replay.ts`)**
  - Criação do tipo `ReplayPlayType = 'GOL' | 'DEFESA' | 'LANCE'` para tipagem forte ponta a ponta.

### Decisões técnicas
- Seleção modelada com union type para impedir valores inválidos.
- Prefixo no `fileName` implementado dentro da engine para centralizar regra de nomenclatura.
- Fluxo atual de upload automático permanece compatível, pois utiliza o `fileName` retornado após composição.

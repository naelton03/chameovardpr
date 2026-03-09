# Matriz de Validação Funcional — Replay (10s a 40s)

## Objetivo
Validar que o fluxo de **Salvar Replay** mantém o padrão esperado para qualquer duração configurada (10s, 15s, 20s, 25s, 30s, 35s, 40s):

- Cada clique em **Salvar Replay** gera **um novo arquivo** na galeria.
- Replays consecutivos podem compartilhar parte do mesmo conteúdo (sobreposição) sem problema.
- A janela salva deve sempre respeitar a regra:
  - **início = instante_do_clique - duração_configurada**
  - **fim = instante_do_clique**

---

## Pré-condições (para todos os cenários)
1. App com permissões de câmera, microfone e escrita em mídia (quando aplicável).
2. Gravação contínua ativa por pelo menos 90 segundos.
3. Verificar espaço disponível no dispositivo.
4. Limpar galeria/pasta de teste antes de iniciar (para facilitar contagem).

---

## Cenário base de sobreposição (exemplo solicitado)

### Configuração: 20s

- Clique 1 em **02:20** → esperado: replay de **02:00 a 02:20**
- Clique 2 em **02:35** → esperado: replay de **02:15 a 02:35**

**Critérios de aceite**
- Existirem **2 arquivos** distintos na galeria.
- Ambos com duração aproximada de 20s (pequena variação por keyframe/encoder é aceitável).
- Sobreposição de trecho entre os dois vídeos (02:15–02:20) é esperada e válida.

---

## Matriz por duração configurada

> Fórmula para expectativa:
> - Replay A: clique em `T1` → janela `[T1 - D, T1]`
> - Replay B: clique em `T2` → janela `[T2 - D, T2]`
>
> Onde `D` = duração configurada.

Use os mesmos instantes de clique para padronizar comparação:
- `T1 = 02:20`
- `T2 = 02:35`

| Duração (D) | Replay A (clique 02:20) | Replay B (clique 02:35) | Sobreposição esperada | Resultado esperado |
|---|---|---|---|---|
| 10s | 02:10 → 02:20 | 02:25 → 02:35 | Não | 2 vídeos salvos normalmente |
| 15s | 02:05 → 02:20 | 02:20 → 02:35 | Fronteira (instante) | 2 vídeos salvos normalmente |
| 20s | 02:00 → 02:20 | 02:15 → 02:35 | 5s | 2 vídeos salvos normalmente |
| 25s | 01:55 → 02:20 | 02:10 → 02:35 | 10s | 2 vídeos salvos normalmente |
| 30s | 01:50 → 02:20 | 02:05 → 02:35 | 15s | 2 vídeos salvos normalmente |
| 35s | 01:45 → 02:20 | 02:00 → 02:35 | 20s | 2 vídeos salvos normalmente |
| 40s | 01:40 → 02:20 | 01:55 → 02:35 | 25s | 2 vídeos salvos normalmente |

---

## Casos adicionais recomendados

### 1) Cliques muito próximos
- Duração: 20s
- Cliques em 02:20 e 02:22
- Esperado: 2 arquivos salvos; segundo replay tende a ser quase totalmente sobreposto ao primeiro.

### 2) Repetição tripla
- Duração: 40s
- Cliques em 02:20, 02:35, 02:50
- Esperado: 3 arquivos distintos; todos com janela coerente com clique.

### 3) Mudança de duração em tempo real
- Gravar contínuo com 20s, salvar replay.
- Alterar para 35s, aguardar buffer estabilizar, salvar replay novamente.
- Esperado: arquivos novos e duração correspondente à configuração ativa no momento de cada clique.

### 4) Borda de início de gravação
- Iniciar gravação e clicar replay cedo (ex.: em 00:08 com duração 20s).
- Esperado: app não quebrar; replay salvo com o material disponível até aquele ponto.

---

## Checklist rápido de QA (manual)

Para cada duração:
- [ ] Iniciar gravação contínua e aguardar tempo suficiente.
- [ ] Acionar replay em 02:20 e 02:35.
- [ ] Confirmar 2 novos arquivos na galeria.
- [ ] Confirmar que os nomes dos arquivos são distintos.
- [ ] Confirmar duração aproximada de cada replay.
- [ ] Confirmar que início/fim da janela batem com a fórmula.
- [ ] Confirmar que sobreposição (quando existir) não impede salvar ambos.

---

## Critério de aprovação final
A funcionalidade está aprovada quando, em todas as durações configuráveis (10s a 40s), cliques consecutivos em **Salvar Replay**:
1. sempre geram arquivos distintos na galeria, e
2. preservam a janela temporal correta relativa ao instante do clique.

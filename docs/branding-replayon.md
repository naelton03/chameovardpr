# Branding ReplayOn

Este documento define o branding oficial do app **ReplayOn** para produto, design, engenharia e marketing.

## 1) Essência da marca

### Nome
- **ReplayOn**

### Posicionamento
- App mobile para capturar e salvar replays de vídeo com agilidade, com foco em ação imediata e UX clara.

### Proposta de valor
- "Gravou. Voltou. Salvou." 
- "Capture o momento que acabou de acontecer."

### Personalidade
- Tecnológica
- Ágil
- Confiável
- Energética

### Tom de voz
- Direto e prático
- Confiante sem ser agressivo
- Pouco texto, alta clareza

---

## 2) Identidade visual

### Logo principal
- Ícone circular neon + símbolo de play/energia + wordmark **ReplayOn**.
- Uso principal em fundo escuro para reforçar contraste e brilho.

### Ícone do app (launcher)
No app Android, o ícone foi configurado com:
- foreground vetorial: `@drawable/ic_replayon_foreground`
- adaptive icon: `@mipmap-anydpi-v26/ic_launcher` e `ic_launcher_round`
- background: `@color/ic_launcher_background`

Referências técnicas:
- `app/src/main/res/drawable/ic_replayon_foreground.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/AndroidManifest.xml`

### Assinatura textual
- **ReplayOn** (sempre com "R" e "O" maiúsculos)

---

## 3) Paleta de cores

### Primárias (produto)
- **Midnight** `#090B1F` (fundo principal / launcher background)
- **Neon Blue** `#35C6FF` (destaques de ação e energia)
- **Neon Purple** `#C84DFF` (destaques secundários / estado ativo)

### Funcionais
- **Success/Gravar** verde (botão iniciar)
- **Danger/Parar** vermelho (estado gravando / parar)
- **Info/Replay** azul ciano (ação salvar replay)

### Recomendação de contraste
- Texto principal: branco ou quase branco sobre fundos escuros.
- Nunca usar neon sobre fundos claros sem contorno/sombra.

---

## 4) Tipografia

### App/UI
- Fonte padrão Android (Material) com hierarquia clara:
  - Títulos: `TitleMedium` / `TitleLarge`
  - Corpo: `BodyMedium`
  - Auxiliar: `BodySmall`

### Regras
- Evitar blocos longos.
- Priorizar labels curtos para ações críticas.
- Botões de ação com verbos objetivos.

---

## 5) Componentes de marca no app

### Header
- Ícone da marca + texto `ReplayOn` no topo do painel principal.

### Superfícies
- Painéis translúcidos escuros (glass/dark) para manter legibilidade sobre preview da câmera.

### Estados críticos
- Gravação ativa deve ser visualmente inequívoca (texto/cores de alerta).

---

## 6) UX writing (padrão)

### Botões
- **Começar gravação**
- **Parar gravação**
- **Salvar replay**

### Status
- "Status: gravando continuamente"
- "Status: replay salvo na galeria"
- "Falha vinculação Drive: ..."

### Princípios
- Mensagens curtas
- Diagnóstico técnico vai para log
- Toasts focam no que usuário precisa saber

---

## 7) Branding + engenharia

### Regras de implementação
1. Não alterar nome para variações (`Replay On`, `replayon`, etc.).
2. Preservar cor de fundo escura em superfícies principais da marca.
3. Qualquer novo ícone/splash deve manter linguagem visual neon azul/roxo.
4. Mudanças visuais relevantes devem atualizar este documento.

### Arquivos atualmente ligados ao branding
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/drawable/ic_replayon_foreground.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/values/strings.xml`

---

## 8) Guia rápido de uso (Do/Don't)

### Do
- Usar marca em fundo escuro.
- Manter contraste alto em elementos de ação.
- Usar rótulos de ação curtos e claros.

### Don't
- Distorcer logo.
- Trocar ordem/estilo do nome ReplayOn.
- Usar paleta clara que comprometa o estilo neon/tech.

---

## 9) Roadmap de branding (futuro)

- Splash screen oficial com animação curta da marca.
- Kit social (avatar, capa, thumbnails).
- Biblioteca de componentes com tokens de cor/spacing centralizados.
- Versão oficial da logo em SVG/PDF para marketing.

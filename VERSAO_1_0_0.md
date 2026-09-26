# Versão 1.0.0 — núcleo estável reconstruído

## Decisões de arquitetura

- Base funcional: fluxo MediaProjection da v0.2.
- Overlay e captura são serviços separados.
- O painel flutuante é iniciado pelo usuário e permanece sobre a Uber.
- A autorização de captura deve ser iniciada **já com a Uber em primeiro plano**, através do painel.
- Sem AccessibilityService, evitando o bloqueio de instalação observado na v0.6.0.
- Sem captura automática/loop de OCR.
- Sem abertura automática da Uber após consentimento.
- Sem botão REATIVAR; a perda da sessão retorna o painel para ATIVAR LEITURA.

## Interface

Painel flutuante compacto e aberto, arrastável pelo cabeçalho. Botão grande central:

ATIVAR LEITURA → ANALISAR → BOA / RAZOÁVEL / RUIM.

Abaixo do resultado aparecem apenas duas métricas curtas: R$/km e R$/h após gasolina.

## Economia

Meta BOA padrão:

- R$ 1,25/km após gasolina;
- R$ 35/h após gasolina.

RAZOÁVEL: pelo menos 80% das duas metas.
RUIM: abaixo dessa faixa.

A distância usada é sempre busca + viagem.

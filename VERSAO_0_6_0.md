# v0.6.0 — reconstrução do motor de leitura

## Motivo

As versões 0.5.x dependiam de MediaProjection. No aparelho real, a sessão funcionava enquanto Corrida Ideal estava em primeiro plano, mas era encerrada ao alternar para Uber Driver, fazendo a bolha virar REATIVAR.

## Nova arquitetura

- Remove MediaProjection.
- Remove serviço de captura/gravação em primeiro plano.
- Remove permissão `SYSTEM_ALERT_WINDOW`.
- Usa `AccessibilityService.takeScreenshot()` sob demanda.
- Usa `TYPE_ACCESSIBILITY_OVERLAY` para a bolha sem pedir "exibir sobre outros apps".
- O serviço é configurado para receber eventos somente de `com.ubercab.driver`.
- Um toque na bolha = uma captura e um OCR.
- Se screenshot falhar, tenta texto da árvore de acessibilidade como fallback.
- Voz curta: Corrida boa / Corrida razoável / Corrida ruim.

## Economia

A meta principal mudou de bruto/km para líquido após combustível:

- Gasolina padrão R$ 6,88/L.
- Consumo base 12,6 km/L.
- BOA: >= R$ 1,25/km após gasolina e >= R$ 35/h após gasolina.
- RAZOÁVEL: >= 80% das duas metas.
- RUIM: abaixo da faixa razoável.

## Observação Android

APKs instalados fora da Play Store podem ter Acessibilidade bloqueada por "configurações restritas". O app inclui um botão para abrir Informações do app; o usuário então usa o menu de três pontos e escolhe "Permitir configurações restritas" antes de ativar o serviço de Acessibilidade.

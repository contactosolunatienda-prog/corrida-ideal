# Corrida Ideal v0.5 — estável / leitura automática por Acessibilidade

Esta versão troca completamente o mecanismo de leitura da oferta.

## O que foi removido
- MediaProjection/compartilhamento de tela.
- Pedido repetido para escolher “tela inteira” ou “um aplicativo”.
- Serviço flutuante com microfone/GPS que podia reiniciar e entrar em falha.
- Voz detalhada durante a oferta.

## Como funciona agora
1. Ative o serviço **Corrida Ideal** em **Acessibilidade** uma única vez, antes de ficar online na Uber.
2. Abra o Uber Driver.
3. O serviço observa somente o pacote do Uber Driver (`com.ubercab.driver`).
4. Ao detectar uma tela de oferta, tenta ler os textos visíveis diretamente.
5. Se o texto acessível não for suficiente, usa uma captura via AccessibilityService + OCR, sem abrir a tela de compartilhamento do Android.
6. O botão flutuante mostra apenas:
   - `✓ BOA`
   - `• RAZOÁVEL`
   - `✕ RUIM`
7. A fala diz apenas “Corrida boa”, “Corrida razoável” ou “Corrida ruim”.
8. Um toque no botão força nova leitura. Arrastar move o botão. `×` oculta até o Corrida Ideal ser aberto novamente.

## Segurança e decisão
O app não aceita nem recusa corridas. Ele apenas analisa a oferta e apresenta uma indicação econômica.

## Parâmetros padrão
- Gasolina: R$ 6,98/L
- Consumo-base: 12,6 km/L
- Melhor consumo realista: 13,5 km/L
- Meta BOA: R$ 1,70 bruto/km total
- Meta BOA: R$ 35 líquido/hora

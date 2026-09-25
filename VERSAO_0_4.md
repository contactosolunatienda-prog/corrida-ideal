# Corrida Ideal v0.4 — histórico + relatório do dia

Principais melhorias sobre a v0.3:

- Mantém a classificação econômica calibrada da v0.3: meta verde padrão de R$ 1,70 bruto/km total e R$ 35 líquido/h.
- Depois que uma oferta é lida, o modo de captura passa a acompanhar a mudança da tela para tentar reconhecer automaticamente o aceite e o fim da viagem.
- Se a detecção automática falhar, o painel expandido oferece `✓ ACEITEI`, `✕ NÃO ACEITEI` e `■ FINALIZAR`.
- Somente corridas aceitas passam a compor o relatório financeiro; ofertas apenas analisadas entram somente nos contadores de decisão.
- Durante uma corrida aceita, GPS/OBD alimentam distância, tempo e consumo observado.
- Relatório do dia com bruto, combustível, líquido, km, consumo médio, R$/km e R$/hora.
- Mostra quantas ofertas verdes/amarelas/vermelhas foram analisadas e quantas foram aceitas.
- Valida o desempenho das recomendações: quantas corridas recomendadas terminaram batendo as metas verdes.
- Histórico das últimas corridas com previsto x realizado.
- Leitura OBD do PID 2F (nível do tanque), quando disponível.
- Campo opcional de capacidade do tanque para estimar combustível restante, valor do combustível e autonomia em km.

## Parâmetros recomendados

- Gasolina: R$ 6,98/L
- Consumo base: 12,6 km/L
- Melhor consumo realista: 13,5 km/L
- Meta verde bruta: R$ 1,70/km total (busca + viagem)
- Meta verde líquida: R$ 35/h
- Teto de alerta: 60 km/h (ajuste ao uso/limites da via)
- Capacidade do tanque: opcional; informe o valor real do seu veículo se quiser estimativa de autonomia.

## Como uma corrida entra no relatório

1. A oferta é lida pela bolha/captura.
2. O app classifica verde/amarela/vermelha e guarda a oferta como pendente.
3. Se a tela mudar para o fluxo de buscar passageiro/iniciar viagem, o app tenta marcar o aceite automaticamente.
4. Se não reconhecer, abra os detalhes da bolha e toque `✓ ACEITEI`.
5. Durante a viagem, o app mede o trecho com GPS e usa consumo OBD quando disponível.
6. Quando detectar tela de conclusão/avaliação, tenta encerrar automaticamente. Se não, use `■ FINALIZAR`.
7. O resultado aparece em `📊 RELATÓRIO DO DIA / ÚLTIMAS CORRIDAS`.

A detecção automática depende do texto que a versão atual do Uber Driver exibe. Os botões manuais existem como redundância para evitar perder corridas no histórico.

## Ajuste 0.4.1 — ocultar e restaurar a bolha

- `×` e `⏻ ENCERRAR / OCULTAR BOLHA` removem o painel flutuante ao terminar o expediente.
- A leitura de tela é encerrada junto para poupar bateria.
- Ao abrir o **Corrida Ideal** novamente, a bolha reaparece automaticamente se a permissão de sobreposição continuar ativa.
- Por segurança do Android, a autorização de captura de tela pode precisar ser concedida novamente em uma nova sessão.

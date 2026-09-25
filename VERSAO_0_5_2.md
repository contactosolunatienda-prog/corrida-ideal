# Corrida Ideal v0.5.2

Versão de estabilidade para uso na rua.

## Mudanças principais

- Remove completamente o serviço de Acessibilidade da v0.5.1.
- Volta à captura de tela autorizada pelo próprio Android, mas somente uma vez no início do turno.
- A bolha nunca abre a tela de autorização enquanto uma oferta está na Uber.
- Se a sessão morrer, a bolha mostra `ATIVAR NO APP` em vez de tirar o motorista da Uber.
- OCR só roda quando o motorista toca em `ANALISAR`; não existe OCR contínuo em loop.
- Resposta visual: `BOA`, `RAZOÁVEL` ou `RUIM`.
- Resposta falada curta: “Corrida boa”, “Corrida razoável” ou “Corrida ruim”.
- Botão flutuante maior com ícone de carro.
- O `×` esconde somente a bolha. A sessão de leitura continua ativa; ao abrir o Corrida Ideal novamente a bolha volta.
- Botão `ENCERRAR TURNO` encerra a bolha e a sessão de captura.
- OBD continua opcional e não pede Bluetooth ao abrir o app.
- Mantém relatório e histórico.
- Mantém a chave de assinatura estável iniciada na v0.5.1 para as próximas atualizações.

## Fluxo recomendado

1. Abra Corrida Ideal antes de ficar online.
2. Toque `INICIAR TURNO`.
3. Autorize “tela inteira” uma única vez para aquela sessão.
4. Abra Uber Driver.
5. Quando surgir oferta, toque `ANALISAR`.
6. Veja/escute BOA, RAZOÁVEL ou RUIM sem sair da Uber.

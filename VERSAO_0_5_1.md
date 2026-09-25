# Corrida Ideal v0.5.1

Correção de estabilidade da v0.5.

- A leitura automática usa apenas os textos de Acessibilidade expostos pela Uber.
- OCR por screenshot só acontece quando o motorista toca em `ANALISAR` e a leitura normal não encontrou a oferta.
- Não usa MediaProjection e não abre a pergunta de compartilhar tela inteira/aplicativo.
- Eventos repetidos da Uber são agrupados e conteúdo idêntico não é reprocessado continuamente.
- OCR e screenshot têm proteção contra chamadas simultâneas e erros de hardware buffer.
- A fala foi reduzida a: `Corrida boa`, `Corrida razoável` ou `Corrida ruim`.
- Botão flutuante maior e com ícone de carro.
- Novo ícone do aplicativo: carro + faixa verde/amarela/vermelha.
- Bluetooth/OBD não pede permissão ao abrir o app; só quando o motorista toca em conectar OBD.
- Workflow passa a preservar a chave debug em cache para que as próximas builds possam ser instaladas por cima da v0.5.1.

Observação: a primeira instalação da v0.5.1 ainda pode exigir remover a versão anterior porque as builds antigas foram assinadas por chaves debug temporárias diferentes.

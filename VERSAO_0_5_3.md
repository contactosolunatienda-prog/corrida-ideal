# Corrida Ideal v0.5.3

Correção de estabilidade focada no uso real durante ofertas.

- Remove o serviço separado da bolha: captura e bolha agora vivem no mesmo serviço em primeiro plano.
- Remove `specialUse` e a dependência do antigo `OverlayService`.
- Android 14+: solicita diretamente captura da tela inteira, evitando a escolha entre tela inteira e aplicativo específico.
- Após autorizar o turno, tenta voltar automaticamente para o Uber Driver.
- A bolha não possui mais `×`, reduzindo desaparecimento por toque acidental.
- A bolha fica maior e mostra apenas ANALISAR, BOA, RAZOÁVEL, RUIM, TENTAR ou REATIVAR.
- Um toque captura uma única imagem e roda OCR; não há OCR contínuo.
- Erros de captura/OCR são tratados sem derrubar o serviço.
- A voz continua curta: “Corrida boa”, “Corrida razoável” ou “Corrida ruim”.

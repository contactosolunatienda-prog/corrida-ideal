# Corrida Ideal v0.5.5 — revisão de estabilidade

Esta versão prioriza voltar ao fluxo que já funcionava nas versões iniciais, mantendo apenas as melhorias de interface e os parâmetros econômicos atuais.

## O que foi revisto

- O seletor de captura volta a usar `createScreenCaptureIntent()` sem forçar Tela inteira nem Um único app. No Android 14+, o próprio sistema oferece a escolha. Para este uso, selecione **Um único app > Uber**.
- A Activity de autorização permanece em primeiro plano por cerca de 1,2 s após o consentimento, dando tempo para o serviço consumir o token e criar a sessão antes de o usuário trocar para a Uber.
- O Corrida Ideal não abre a Uber automaticamente. Isso evita a corrida entre a troca de app e a inicialização do MediaProjection observada nas versões recentes.
- A bolha só é criada depois que o serviço de captura iniciou com sucesso.
- Um toque em ANALISAR faz uma única captura. Não há OCR contínuo.
- Foi adicionado timeout de captura: se nenhum frame chegar em 1,6 s, a bolha volta para TENTAR e um novo toque funciona; ela não fica presa em LENDO.
- Se a sessão MediaProjection realmente terminar, a bolha mostra REATIVAR. O toque abre o Corrida Ideal; a autorização é refeita a partir de uma Activity visível, evitando depender de lançamento de Activity em segundo plano.
- Respostas na rua continuam curtas: BOA, RAZOÁVEL ou RUIM, com voz curta.
- Parâmetros padrão: meta boa de R$ 1,70/km bruto e R$ 35/h após combustível; faixa razoável a partir de 80% dessas metas.

## Validações feitas antes do pacote

- Parser testado com ofertas contendo valor, dois trechos, tempos e valor por km secundário.
- Caso R$ 27,50 / 3 km busca / 12 km viagem / 28 min resulta BOA.
- Caso R$ 29,09 / 12,4 km busca / 15,7 km viagem / 31 min resulta RUIM.
- O valor secundário R$/km não substitui o valor total da oferta.
- Manifesto mantém somente o foreground service de MediaProjection necessário para a leitura.
- A sessão usa um único `createVirtualDisplay` por consentimento, compatível com a regra do Android 14+.

## Limite da validação

A compilação Android final ainda precisa passar pelo GitHub Actions e o comportamento da MediaProjection precisa ser confirmado no aparelho real, porque o sistema/OEM controla o seletor e pode encerrar a sessão. O código foi revisado para falhar de forma recuperável, sem travar a bolha.

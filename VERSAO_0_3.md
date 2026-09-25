# Corrida Ideal v0.3

## Calibração econômica

A decisão agora usa parâmetros mais intuitivos para oferta de corrida:

- **Meta VERDE bruta por km total:** R$ 1,70/km por padrão.
- **Meta VERDE líquida por hora:** R$ 35/h por padrão.
- A faixa **AMARELA** começa automaticamente em 80% dessas metas (≈ R$ 1,36/km bruto e R$ 28/h líquido com os padrões acima).
- **VERMELHA** é reservada para ofertas abaixo dessa faixa ou economicamente inviáveis.

O R$/km considera **km até o passageiro + km da viagem**.

## Correção do OCR

Quando a leitura de tela identifica uma oferta, os campos `Valor`, `Km até o passageiro`, `Km da viagem` e `Tempo` passam a mostrar exatamente os valores usados no cálculo. Isso evita divergência entre os campos visíveis e o cartão de análise.

## Consumo e média

O consumo não pode melhorar o valor **bruto** por km da oferta. Quando o R$/km bruto já atinge a meta verde, mas o retorno por hora ainda está abaixo da meta, o app mostra:

- consumo necessário se o tempo estimado permanecer igual;
- média de deslocamento necessária usando o consumo-base.

A média é apenas referência econômica/temporal e nunca autorização para exceder o limite da via.

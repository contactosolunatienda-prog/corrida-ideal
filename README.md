# Corrida Ideal — Android

Aplicativo auxiliar para motorista de app. Ele não entra na conta da Uber, não usa sua senha e não aceita/recusa corridas automaticamente.

## v0.2 — uso principal

A v0.2 elimina a necessidade de falar a oferta inteira. O fluxo é:

1. Abra **Corrida Ideal**.
2. Toque **ATIVAR BOLHA 📸** e permita "Exibir sobre outros apps".
3. Abra a **Uber Driver**.
4. Quando uma oferta aparecer, toque uma vez na bolha `📸`.
5. Na primeira utilização da leitura de tela, o Android pedirá permissão para compartilhar/capturar a tela. Depois de autorizada a sessão, volte à Uber.
6. Toque `📸` de novo: o app faz OCR da tela, tenta identificar valor, km até o passageiro, km da viagem e tempo, e calcula verde/amarela/vermelha.
7. Segure a bolha para abrir os detalhes. Arraste a bolha para mudar de posição.

A captura é processada no aparelho e não é salva pelo Corrida Ideal.

## Modo AUTO experimental

Com o painel de detalhes aberto, o botão **AUTO** faz OCR periódico enquanto a sessão de captura estiver ativa. Ele é desligado por padrão. Use apenas quando estiver na tela da Uber, porque o OCR processará o que estiver visível no período.

## O que é calculado

- distância total = km até buscar + km da viagem;
- combustível estimado;
- valor líquido após combustível;
- R$/km bruto e líquido;
- R$/hora líquido;
- consumo mínimo / consumo-alvo para a corrida atingir sua meta;
- média de deslocamento necessária para atingir a meta de R$/hora;
- comparação com OBD2 e média real do trecho.

## Cores

- Verde: já bate as metas configuradas.
- Amarela: pode bater dentro do consumo/média realistas configurados.
- Vermelha: exigiria cenário fora do limite configurado.

A média de velocidade é um indicador econômico/temporal; nunca substitui o limite legal da via.

## OBD2

Pareie o ELM327/OBD2 nas configurações Bluetooth do Android, selecione no Corrida Ideal e toque **CONECTAR OBD**. O app tenta ler velocidade e consumo e aprende faixas de velocidade × km/L do seu próprio carro.

## Voz

A voz continua disponível como alternativa. Exemplos simples:

- `consumo 13 vírgula 5`
- `limite 60`
- `como está a corrida?`

Você não precisa mais ditar valor + distâncias + tempo se a leitura da tela funcionar.

## Limitações

- O OCR depende de a oferta estar visível e legível. A interface da Uber pode mudar.
- Se o OCR não identificar todos os campos, o app informa o que faltou em vez de inventar uma decisão.
- A primeira versão do parser usa heurísticas e pode precisar de ajuste depois de testarmos uma captura real da tela de oferta da Uber.
- A leitura automática usa uma sessão de MediaProjection que precisa ser autorizada pelo usuário no Android.

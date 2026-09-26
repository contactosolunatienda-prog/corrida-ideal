# Corrida Ideal — v0.6.0 Android

Aplicativo auxiliar para motorista. Não usa senha da Uber e não aceita/recusa corridas automaticamente.

## O que mudou de verdade

A v0.6.0 abandona completamente MediaProjection/gravação de tela, porque no aparelho de teste a sessão era encerrada ao sair do Corrida Ideal e entrar na Uber.

Agora o motor usa um **Serviço de Acessibilidade** restrito ao pacote do Uber Driver. O Android mantém esse serviço disponível quando a Uber está na frente. A captura acontece somente quando o motorista toca na bolha **ANALISAR**.

## Ativação uma única vez

1. Instale e abra Corrida Ideal.
2. Toque **ATIVAR LEITURA EM ACESSIBILIDADE**.
3. Ative **Corrida Ideal — leitura da Uber**.
4. Se o Android bloquear a opção por ser APK instalado fora da Play Store, abra **Informações do app > ⋮ > Permitir configurações restritas** e volte à Acessibilidade.
5. Abra Uber Driver. A bolha aparece sobre a Uber.
6. Quando surgir oferta, toque **ANALISAR**.

Não existe mais autorização "gravar tela", "toda a tela", "um único app" nem botão REATIVAR.

## Privacidade e comportamento

- O serviço recebe eventos somente do pacote `com.ubercab.driver`.
- A captura é feita somente quando o motorista toca na bolha.
- O app não toca em botões da Uber, não aceita e não recusa corridas.
- O OCR roda localmente com ML Kit.

## Parâmetros padrão

- Gasolina: R$ 6,88/L
- Consumo base: 12,6 km/L
- Melhor consumo realista: 13,5 km/L
- BOA: pelo menos **R$ 1,25/km depois do combustível** E **R$ 35/h depois do combustível**
- RAZOÁVEL: pelo menos 80% das duas metas
- RUIM: abaixo dessa faixa

Sempre usa **km total = km até buscar + km da viagem**.

## OBD2

Continua opcional. Não é necessário para analisar uma oferta.

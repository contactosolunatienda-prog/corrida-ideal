# Corrida Ideal v1.0.0 — reconstrução estável

Esta versão foi refeita sobre o **fluxo de captura da v0.2**, que foi a primeira versão que funcionou de verdade no aparelho durante o uso da Uber.

## Objetivo principal

Analisar uma oferta da Uber sem o motorista sair da tela da Uber e responder apenas:

- **BOA**
- **RAZOÁVEL**
- **RUIM**

A voz fala somente “Corrida boa”, “Corrida razoável” ou “Corrida ruim”.

## O que mudou em relação às versões problemáticas

- não usa AccessibilityService;
- não usa leitura automática contínua;
- painel e captura voltaram a ser **dois serviços separados**, como no desenho que funcionou na v0.2;
- a captura não é iniciada dentro do Corrida Ideal para depois trocar de app;
- o fluxo recomendado é: **abrir o painel → ir para Uber → ativar a leitura já por cima da Uber → analisar**;
- não abre a Uber automaticamente;
- não tenta reaproveitar silenciosamente uma sessão encerrada;
- não existe o antigo botão “REATIVAR”; se a sessão acabar, o painel mostra **ATIVAR LEITURA**;
- painel flutuante maior e sempre visível, em vez de uma bolha minúscula;
- o painel desaparece por alguns instantes durante a captura para não encobrir os dados da oferta;
- timeout de captura evita ficar preso eternamente em “LENDO”.

## Parâmetros padrão

- gasolina: **R$ 6,88/L**;
- consumo-base: **12,6 km/L**;
- melhor consumo realista: **13,5 km/L**;
- meta BOA: **R$ 1,25/km depois da gasolina**, usando km até buscar + km da viagem;
- meta BOA: **R$ 35/h depois da gasolina**;
- RAZOÁVEL começa em 80% dessas metas;
- abaixo disso: RUIM.

## Uso na rua

1. Abra Corrida Ideal.
2. Toque **ATIVAR JANELA FLUTUANTE**.
3. Abra a Uber.
4. Já com a Uber aberta, toque **ATIVAR LEITURA** na janela do Corrida Ideal.
5. No aviso do Android, autorize a captura. **Tudo na tela** é o modo recomendado para este fluxo.
6. O aviso fecha e você volta para a Uber.
7. Quando a oferta aparecer, toque **ANALISAR**.
8. O painel mostra BOA / RAZOÁVEL / RUIM e fala somente esse resultado.

## Privacidade

A captura é processada localmente pelo ML Kit. O app não envia a imagem da tela para servidor e não aceita ou recusa corridas automaticamente.

## OBD2

Continua opcional. A análise funciona usando o consumo-base configurado mesmo sem OBD.

## Histórico

A infraestrutura de relatório das versões anteriores foi mantida para registrar ofertas analisadas. A prioridade desta versão é a estabilidade do fluxo de leitura; detecção automática de aceite/fim de corrida não participa do caminho crítico da captura.

# Corrida Ideal — v0.5.3 Android

Aplicativo auxiliar para motorista de app. Não acessa a senha da Uber e não aceita/recusa corridas automaticamente.

## Uso rápido

1. Antes de ficar online, abra Corrida Ideal e toque **INICIAR TURNO**.
2. Permita **Exibir sobre outros apps** se o Android pedir.
3. Autorize a captura da tela **uma vez para a sessão**.
4. Abra Uber Driver.
5. Quando surgir uma oferta, toque na bolha **ANALISAR**.
6. O resultado fica na própria bolha: **BOA**, **RAZOÁVEL** ou **RUIM** e a voz fala somente essa avaliação.

A bolha nunca abre a tela de autorização durante uma oferta. Se a sessão de leitura for encerrada pelo Android, ela muda para **ATIVAR NO APP**. Assim o motorista não é retirado da tela da Uber no meio de uma decisão.

## Estabilidade

A v0.5.3 remove o serviço de Acessibilidade usado na v0.5.1. O OCR não roda continuamente; ele só é executado quando o motorista toca na bolha. Isso reduz bastante CPU, memória e risco de travamento.

## Fechar a bolha

O `×` esconde somente a bolha. Se a sessão ainda estiver ativa, ao abrir Corrida Ideal de novo ela volta. Para encerrar tudo e economizar bateria, use **ENCERRAR TURNO** no app.

## Parâmetros padrão

- Gasolina: R$ 6,98/L
- Consumo base: 12,6 km/L
- Melhor consumo realista: 13,5 km/L
- BOA: meta bruta mínima R$ 1,70/km total e meta líquida mínima R$ 35/h
- RAZOÁVEL: começa em 80% da meta BOA ou cenário que ainda pode atingir a meta dentro dos parâmetros realistas
- RUIM: abaixo do piso ou cenário fora do realista

## Relatório

Mantém o relatório do dia, histórico de ofertas analisadas e dados de combustível. O OBD2 continua opcional.


## v0.5.3
Bolha e captura foram unificadas em um único serviço de primeiro plano para evitar desaparecimentos e travamentos. No Android 14+, a autorização solicita diretamente a tela inteira.


## v0.5.4
No Android 14+ escolha **Um único app > Uber** no consentimento de captura. O modo de tela inteira foi removido como padrão porque, neste aparelho, a sessão estava sendo encerrada ao alternar para a Uber. O botão REATIVAR agora reabre a autorização.

## v0.5.5 — estabilidade antes de novos recursos

A v0.5.5 volta ao caminho de autorização padrão do Android, não abre a Uber automaticamente e adiciona timeout para impedir que a bolha fique presa em LENDO. Consulte `VERSAO_0_5_5.md`.

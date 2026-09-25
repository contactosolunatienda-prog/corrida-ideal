# Corrida Ideal — v0.4 Android

Aplicativo auxiliar para motorista de app. Não acessa senha da Uber, não aceita/recusa corridas automaticamente e mantém a decisão com o motorista.

## Uso principal

1. Abra o Corrida Ideal e salve seus parâmetros.
2. Ative a bolha e a leitura de tela.
3. Abra o Uber Driver.
4. Quando chegar uma oferta, toque na bolha 📸. O OCR lê valor, km até buscar, km da viagem e tempo.
5. O app classifica 🟢 / 🟡 / 🔴 e fala o resultado.
6. Depois de uma oferta válida, a leitura automática fica ativa para tentar reconhecer quando você aceitou e quando a viagem terminou.
7. Se a detecção falhar, segure a bolha para abrir os detalhes e use `✓ ACEITEI` ou `■ FINALIZAR`.
8. No fim do dia, abra `📊 RELATÓRIO DO DIA / ÚLTIMAS CORRIDAS`.

## Classificação padrão

- 🟢 Verde: pelo menos R$ 1,70 bruto/km total **e** R$ 35 líquido/h.
- 🟡 Amarela: próxima da meta (piso automático de 80%) ou pode chegar à meta com consumo/tempo realistas.
- 🔴 Vermelha: retorno abaixo do piso ou cenário necessário fora do que foi configurado como realista.

## Relatório do dia

O relatório usa apenas corridas aceitas pelo fluxo do app (detecção automática ou botão `✓ ACEITEI`). Ele mostra:

- quantidade de corridas concluídas/em andamento;
- faturamento bruto registrado pelas ofertas;
- litros e valor de combustível estimados;
- líquido após combustível;
- km totais;
- km planejados de busca e de viagem;
- consumo médio;
- bruto por km, líquido por km e líquido por hora;
- ofertas analisadas por cor e aceitas por cor;
- quantas indicações verde/amarela terminaram realmente batendo a meta verde;
- últimas corridas com previsto x realizado.

Se o OBD fornecer nível do tanque (PID 2F) e você informar a capacidade do tanque, o relatório também estima combustível restante, valor do combustível restante e autonomia em km.

## Dados e privacidade

- OCR e cálculos são feitos no aparelho.
- Nenhuma captura de tela é salva pelo app.
- Histórico e relatório ficam em SharedPreferences no próprio Android.
- O app não usa sua senha da Uber.

## Limitações do MVP

- A leitura depende do layout/texto atual do Uber Driver. Mudanças na interface podem exigir ajuste do parser.
- O valor bruto do relatório usa o valor lido na oferta. Gorjetas, ajustes e alterações posteriores da Uber não são consultados diretamente.
- A distância real depende de GPS; se indisponível, o app usa a distância planejada da oferta como fallback.
- O consumo real depende do PID OBD disponível. Sem leitura confiável, usa o consumo configurado.
- O nível do tanque PID 2F não é suportado por todos os veículos/adaptadores.

## Compilação

O workflow `.github/workflows/android.yml` gera o APK de debug automaticamente em cada push para `main`/`master`.

### Encerrar o expediente / ocultar a bolha

Abra os detalhes da bolha (segure a bolha) e toque em **⏻ ENCERRAR / OCULTAR BOLHA** ou no **×**. A bolha some e a leitura de tela é encerrada para economizar bateria. Ao abrir o Corrida Ideal novamente, a bolha volta automaticamente. A permissão de captura de tela do Android pode precisar ser autorizada novamente para uma nova sessão.

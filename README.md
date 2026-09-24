# Corrida Ideal — MVP Android

Aplicativo auxiliar para motorista de app. Ele **não acessa sua senha da Uber e não aceita/recusa corridas automaticamente**. A decisão continua sendo do motorista.

## O que esta versão faz

- Entrada manual da oferta: valor, km até o passageiro, km da viagem e tempo estimado.
- Painel flutuante sobre outros apps.
- Classificação **verde / amarela / vermelha**.
- Cálculo de:
  - distância total;
  - gasto estimado de combustível;
  - valor líquido após combustível;
  - R$/km bruto e líquido;
  - R$/hora líquido;
  - **par necessário para a corrida virar verde: km/L + velocidade média de deslocamento**;
  - tempo máximo aproximado para bater a meta de R$/hora.
- Entrada por voz no painel flutuante.
- Leitura de velocidade por GPS.
- Integração Bluetooth Classic com adaptadores OBD2/ELM327 já pareados.
- Leitura OBD de velocidade (PID 0D).
- Tentativa de leitura direta de taxa de combustível (PID 5E); se não existir, estima usando MAF (PID 10) para gasolina.
- Aprendizado empírico de velocidade × km/L em faixas de 5 km/h.
- Alerta visual piscando quando a velocidade ultrapassa o teto configurado pelo usuário.
- Possibilidade de substituir o consumo OBD por voz para a corrida atual.

## Comandos de voz

Toque no botão `🎙 Falar` do painel e diga, por exemplo:

- `corrida 27 vírgula 50, 3 até buscar, 12 de viagem, 28 minutos`
- `consumo 13 vírgula 5`
- `salvar média 13 vírgula 2`
- `limite 60`
- `como está a corrida?`

Ao falar `consumo 13,5`, esse valor passa a valer para a corrida atual. Use o botão **Usar OBD/base novamente** para remover a substituição manual.

## Lógica econômica

Os padrões iniciais são editáveis:

- gasolina: R$ 6,98/L;
- consumo base: 12,6 km/L;
- melhor consumo realista: 14,0 km/L;
- meta líquida: R$ 1,20/km;
- meta líquida: R$ 35/h;
- teto de alerta de velocidade: 60 km/h.

A cor é definida assim:

- **Verde:** com o consumo atual e o tempo estimado, a corrida já bate as duas metas.
- **Amarela:** ainda não bate as duas metas, mas existe um par `consumo + média` dentro do melhor consumo realista e do teto de velocidade configurado.
- **Vermelha:** o par necessário fica fora do cenário configurado como realista.

A velocidade calculada é **média de deslocamento**, não recomendação para exceder o limite legal. O teto configurado no app deve ser ajustado à via/rota quando necessário.

## Como o app aprende a velocidade econômica do carro

Quando o OBD fornece velocidade e consumo, o aplicativo acumula amostras em faixas de 5 km/h. Depois de pelo menos 8 amostras válidas em uma faixa, ela pode aparecer como faixa observada capaz de entregar o consumo necessário.

Isso evita assumir que existe uma única “velocidade ideal” universal. Trânsito, marcha, relevo, ar-condicionado, carga e estilo de condução alteram o resultado.

## OBD2

1. Pareie o ELM327/OBD2 nas configurações Bluetooth do Android.
2. Abra o app e permita **Dispositivos próximos**.
3. Selecione o OBD na lista.
4. Toque em **Conectar OBD**.

O app tenta os seguintes dados OBD-II:

- `010D` — velocidade do veículo;
- `015E` — taxa de combustível em L/h;
- fallback `0110` — MAF em g/s.

O fallback por MAF foi configurado para gasolina (AFR estequiométrica 14,7:1 e densidade aproximada de 745 g/L). Em etanol/flex com mistura diferente, a estimativa por MAF precisa de calibração específica.

## Permissões

O aplicativo pede apenas o necessário para os recursos escolhidos:

- microfone — comando por voz;
- localização — velocidade por GPS;
- dispositivos próximos/Bluetooth — OBD2;
- exibir sobre outros apps — painel flutuante;
- notificações — serviço ativo em primeiro plano.

## Compilar no Android Studio

1. Abra a pasta `CorridaIdeal` no Android Studio.
2. Aguarde a sincronização do Gradle.
3. Instale o Android SDK 35 se o Android Studio solicitar.
4. Execute em um Android físico (o OBD e o painel flutuante fazem mais sentido no aparelho real).

## Compilar pelo GitHub Actions

O projeto inclui `.github/workflows/android.yml`.

1. Crie um repositório GitHub e envie todo o conteúdo desta pasta.
2. Abra a aba **Actions**.
3. Execute **Build Android APK** ou faça um push para `main`/`master`.
4. O artefato gerado se chama `CorridaIdeal-debug-apk` e contém `app-debug.apk`.

## Limitações deste MVP

- O app **não lê automaticamente a tela da Uber nesta versão**. A oferta entra por digitação ou voz.
- O reconhecimento de voz contínuo por palavra-chave não fica permanentemente escutando, porque o Android restringe microfone contínuo em segundo plano e a própria API de reconhecimento não é indicada para escuta contínua. O painel oferece um botão de microfone sempre acessível e uma ação na notificação.
- Alguns veículos não oferecem PID 5E. Nesses casos, o app usa MAF quando possível.
- ELM327 clones variam bastante de qualidade e compatibilidade.
- A média calculada pelo GPS começa quando o painel/medição é reiniciado. Use o botão `↻` para zerar o trecho.

## Estrutura

- `RideEconomics.kt` — cálculo econômico e classificação.
- `VoiceParser.kt` — interpretação dos comandos em português.
- `ObdHub.kt` — conexão ELM327 e PIDs OBD-II.
- `OverlayService.kt` — painel flutuante, GPS, voz, alertas e TTS.
- `RuntimeState.kt` — estado ao vivo e aprendizado velocidade × consumo.
- `MainActivity.kt` — configuração, entrada manual e seleção do OBD.

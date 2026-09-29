# Corrida Ideal 1.0.3 — correção do leitor

Esta pasta contém os arquivos da correção que troca o motor de leitura por
`AccessibilityService.takeScreenshot()` sob demanda.

## O que muda

- Remove a dependência de MediaProjection para a leitura.
- A bolha usa `TYPE_ACCESSIBILITY_OVERLAY`, sem "Exibir sobre outros apps".
- Acessibilidade é ativada uma vez nas configurações do Android.
- Um toque em ANALISAR faz uma captura + OCR.
- Se a captura falhar, o app tenta ler a árvore de acessibilidade do Uber.
- Mantém o histórico/relatório e os parâmetros do projeto.
- O botão ENCERRAR TURNO apenas pausa a bolha; não desativa a permissão de Acessibilidade.
- Parâmetros padrão desta revisão: gasolina R$ 6,66/L e consumo 13,3 km/L.

## Arquivos a substituir no repositório

1. `app/src/main/AndroidManifest.xml`
2. `app/src/main/java/com/yuri/corridaideal/MainActivity.kt`
3. `app/src/main/java/com/yuri/corridaideal/UberAccessibilityService.kt`
4. `app/src/main/res/xml/accessibility_service_config.xml`
5. `app/src/main/res/values/strings.xml`
6. `app/build.gradle.kts`

Os arquivos antigos `ScreenCaptureService.kt`, `CapturePermissionActivity.kt` e
`OverlayService.kt` deixam de ser registrados no Manifest e não participam mais
do fluxo de leitura.

## No celular

Depois de instalar a 1.0.3:

1. Abra Corrida Ideal.
2. Toque em ATIVAR LEITURA EM ACESSIBILIDADE.
3. Ative "Corrida Ideal — leitura da Uber".
4. Se o Android bloquear, abra as informações do app e use o menu ⋮ para
   permitir configurações restritas; depois volte à Acessibilidade.
5. Abra a Uber.
6. A bolha ANALISAR deve aparecer.
7. Toque ANALISAR em uma oferta.

A leitura não deve voltar para "ATIVAR LEITURA" só porque você trocou do
Corrida Ideal para a Uber.

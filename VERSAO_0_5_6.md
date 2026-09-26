# Corrida Ideal v0.5.6 — rollback do núcleo de captura

Esta versão não continua a arquitetura experimental 0.5.3–0.5.5.
Ela restaura a separação que já funcionou no aparelho na versão 0.2:

- ScreenCaptureService: somente MediaProjection + OCR + voz curta.
- OverlayService: somente bolha flutuante.
- CapturePermissionActivity: fluxo simples, sem atraso, sem abrir Uber automaticamente.
- Um toque na bolha = uma leitura.
- Sem Acessibilidade.
- Sem OCR contínuo.
- Resultado: BOA / RAZOÁVEL / RUIM.
- Parâmetros mantidos: meta BOA bruta R$ 1,70/km total e meta líquida R$ 35/h.

Objetivo principal: recuperar a estabilidade do fluxo que já funcionou antes de continuar adicionando recursos.

# Corrida Ideal v1.0.1 — correção de build

Esta versão corrige o erro do GitHub Actions causado por um recurso XML antigo da v0.6.0 que permaneceu no repositório após o upload.

- A v1.0.1 continua sem AccessibilityService no AndroidManifest.
- O arquivo `res/xml/accessibility_service_config.xml` agora é apenas um stub de compatibilidade válido e não é usado pelo app.
- Mantém a arquitetura da v1.0.0: painel flutuante + MediaProjection separado, acionado manualmente.
- Mantém gasolina padrão R$ 6,88/L, consumo base 12,6 km/L, meta de R$ 1,25/km após combustível e R$ 35/h após combustível.

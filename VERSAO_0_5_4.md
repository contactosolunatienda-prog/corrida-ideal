# Corrida Ideal v0.5.4

Correção específica para o problema observado no Android 15/Motorola:

- não força mais **Tudo na tela**;
- no Android 14+ volta a permitir **Um único app**;
- para este uso, escolher **Um único app > Uber**;
- o botão flutuante **REATIVAR** agora realmente abre novamente a autorização;
- evita abrir várias telas de autorização com toques repetidos;
- não tenta abrir a Uber automaticamente depois da autorização;
- mantém bolha, OCR, voz curta e classificação BOA / RAZOÁVEL / RUIM.

A v0.5.3 mostrava REATIVAR quando o MediaProjection era encerrado, mas o toque nesse estado não iniciava nova autorização. Isso foi corrigido.

# Corrida Ideal v0.4.2 — correção de estabilidade

Correções principais:
- serviços de bolha e captura não reiniciam sozinhos em segundo plano (`START_NOT_STICKY`), evitando loop de falha no Android recente;
- histórico/relatório não pode mais derrubar a leitura da oferta: falhas dessa camada ficam isoladas;
- fala por TTS aguarda o mecanismo de voz terminar de inicializar, evitando a primeira análise ficar muda;
- botão principal virou **INICIAR BOLHA + LEITURA 📸**, abrindo a autorização de captura quando necessário;
- mantém relatório do dia, histórico, OBD/GPS, ocultar bolha e retorno ao reabrir o app.

A autorização de captura de tela do Android continua sendo por sessão e pode precisar ser concedida novamente após encerrar a leitura ou reiniciar o app.

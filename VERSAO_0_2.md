# Corrida Ideal v0.2 — leitura de tela

Principais mudanças em relação à v0.1:

- bolha flutuante pequena `📸`, arrastável;
- toque simples na bolha = ler a oferta que está na tela;
- toque longo = abrir/fechar o painel de detalhes;
- OCR local com ML Kit: a imagem não é salva em arquivo;
- uma sessão de compartilhamento de tela pode permanecer ativa enquanto o motorista usa a Uber;
- modo AUTO experimental, desligado por padrão;
- a cor da bolha passa a refletir a análise: verde/amarela/vermelha;
- voz virou alternativa, não requisito para cadastrar a corrida;
- ao voltar da permissão "Exibir sobre outros apps", a bolha é iniciada automaticamente;
- mantém OBD2, GPS, consumo-alvo, média-alvo e alertas da v0.1.

## Fluxo recomendado

1. Abra Corrida Ideal e toque **ATIVAR BOLHA 📸**.
2. Abra a Uber Driver.
3. Quando a oferta aparecer, toque na bolha.
4. Na primeira vez, autorize o compartilhamento de tela. Em Android recente, selecione a Uber quando o sistema oferecer compartilhamento de um único app; caso contrário, escolha a tela inteira.
5. Volte à Uber. Com a sessão ativa, cada toque em 📸 lê a oferta e calcula a viabilidade.
6. Segure a bolha para abrir os detalhes e, se quiser, ligar **AUTO**.

O aplicativo não aceita nem recusa corridas automaticamente.

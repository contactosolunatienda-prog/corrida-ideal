# Corrida Ideal v0.5

Aplicativo auxiliar para motorista de app. Não usa login/senha da Uber e não aceita ou recusa corridas.

## Objetivo da v0.5
A prioridade é estabilidade. A leitura deixa de usar MediaProjection, que abria a tela de compartilhamento do Android e podia interromper a oferta. Agora o app usa um serviço de Acessibilidade limitado ao Uber Driver.

## Uso
1. Abra o Corrida Ideal.
2. Toque em **ATIVAR LEITURA AUTOMÁTICA**.
3. Em Acessibilidade, ative **Corrida Ideal** uma única vez.
4. Volte ao app e salve os parâmetros.
5. Abra o Uber Driver.
6. Ao aparecer uma oferta, o botão flutuante analisa automaticamente.
7. Se necessário, toque no botão para forçar nova leitura.

O botão usa apenas três resultados: **BOA**, **RAZOÁVEL** e **RUIM**. A voz fala apenas um desses três resultados.

## Privacidade
O serviço de Acessibilidade é configurado para o pacote `com.ubercab.driver`. O app não salva capturas de tela. Quando a leitura textual não basta, o OCR é processado no próprio aparelho.

## Relatório
O histórico continua registrando ofertas analisadas e corridas identificadas como aceitas/concluídas. O relatório mostra bruto, combustível estimado, líquido, km, R$/km e R$/hora.

## Observação
Faça a ativação de Acessibilidade antes de ficar online. Não use uma oferta real como momento de configurar permissões.

## v0.5.1 — estabilidade

A leitura automática não executa OCR pesado a cada evento da Uber. Primeiro usa somente o texto de Acessibilidade; se necessário, um toque em `ANALISAR` faz um único screenshot via Accessibility API, sem abrir janela de compartilhamento. A voz diz apenas Boa/Razoável/Ruim. O ícone e o botão flutuante também foram redesenhados.

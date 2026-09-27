# Corrida Ideal v1.0.2

Correção focada no aparelho de teste em que o seletor de compartilhamento aparecia, mas após tocar **Iniciar** a leitura não ficava ativa.

Mudanças:
- espera explícita pelo MediaProjection ficar pronto antes de fechar a Activity de consentimento;
- retorno automático à Uber depois da confirmação real de captura;
- status de erro visível no painel;
- proteção contra callback atrasado de sessão antiga;
- painel minimizável e encerrável;
- sem AccessibilityService.

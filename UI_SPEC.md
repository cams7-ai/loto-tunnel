# Especificação da interface entregue

Material 3 claro, fonte Android e idioma pt-BR. Fundo #F5F7FA, superfície #FFFFFF, principal #3949AB, texto #17213D, secundário #667085, bordas #DDE3EE. Sucesso #00866A/#E3F6EF, aviso #B85C00/#FFF4DE, erro #C62828/#FDECEC. Cartões com cantos 16 dp, margens/padding/seções 16 dp e elementos relacionados separados por 8 dp. Título principal headlineSmall (~24 sp), títulos de seção titleLarge (~22 sp), corpo padrão 16 sp, legendas bodySmall. Endereços e fingerprint usam fonte monoespaçada.

A Activity usa edge-to-edge com insets de status/navigation e teclado; o conteúdo tem rolagem vertical. Campos têm labels e alvos nativos Material; botões principais têm altura mínima 48 dp. Endereços e mensagens permitem quebra. O tamanho da fonte segue o sistema. Estados têm rótulos explícitos, além das cores. Ícones de navegação/opções têm descrições.

## Navegação e componentes

Navegação inferior: Conexão, Logs, Ajustes, com destino atual destacado. Cabeçalho LotoBot Tunnel. `Panel`, `Notice`, `ConfigField`, `Select`, renderização de estado e itens de histórico/eventos são os componentes comuns. As informações de perfil, rede, IPv6 e métricas usam o mesmo cartão Panel.

Conexão: estados Desconectado, Conectando, Conectado, Aguardando rede, Reconectando e Erro na mesma tela; cartão de perfil, destino/portas, rede e modo, IPv6 com rede/horário/erro/desatualização, Copiar e Atualizar, duração, ativos e acumulados por rede. A ação principal varia entre Conectar, Desconectar e Cancelar reconexão. Erro anterior é mostrado separadamente. Nenhuma métrica ilustrativa é inserida.

Logs: abas Uso do SOCKS5 e Túnel SSH, ativos, filtros de destino/texto, estado, rede e período, pausa visual, cópia e limpeza confirmada. Conexão mostra ID, destino/porta, estado/atividade, horários, duração, bytes e falha. Eventos mostram horário/mensagem. Estado vazio é explícito. A limpeza preserva ativos.

Ajustes: seletor Perfil SSH/Rede. Perfil inclui seleção/criação/exclusão, nome, host, usuário, porta, importação de chave, senha mascarada, escuta loopback e porta SOCKS5, timeout/keepalive/limite de respostas e salvar. Rede tem radio buttons nos três modos, rede em uso, explicação de interrupção/franquia e salvar. Segundo plano inclui opção de lembrete após reinicialização. Diálogos permitem conferir a impressão digital, confirmar exclusão/limpeza e exibir resultados/erros.

## Referências e diferenças intencionais

Referências originais em `docs/01-conexao.png` a `docs/06-aguardando-rede.png`, e quadro geral, foram preservadas e inspecionadas. Não são capturas do aplicativo nem fundos da UI.

Os cartões empilham verticalmente para suportar telas pequenas/fonte ampliada, em vez de duas colunas fixas. Copiar/Atualizar e ações de logs ficam em linhas separadas; filtros usam campos e menus Material. Existem campos adicionais para limites SSH e múltiplos perfis. A escuta 127.0.0.1 é apresentada como fixa, evitando exposição acidental. A senha é digitada antes da importação e não é recuperada para exibição. O lembrete de reboot explicita a retomada manual. Nome de rede mostra Wi-Fi/Dados móveis sem exigir permissões adicionais para SSID/operadora.

Capturas reais e seus cenários, quando disponíveis, são registrados em `capturas/README.md` e `docs/VALIDATION.md`. As imagens originais nunca são usadas como evidência de uma tela implementada.

Os filtros de logs começam recolhidos e são abertos por **Mostrar filtros**, para que destinos/eventos permaneçam visíveis nas telas menores. As ações de pausa/cópia/limpeza seguem os registros. Tipografia dos títulos usa peso semibold/bold e as cores de superfícies/indicadores são explicitamente definidas, evitando as superfícies roxas do tema Material padrão.

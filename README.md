# LotoBot Tunnel

Aplicativo Android nativo em Kotlin/Compose para disponibilizar um SOCKS5 reverso em `127.0.0.1:1080` na EC2. O SSH e os destinos SOCKS5 usam a mesma rede do celular, sem root, Termux, shell ou PTY. Android mínimo: **13 / API 33**. Interface em português, Material 3 claro.

## Primeiro teste no Android Studio

Se você ainda não conhece a IDE, siga o [guia passo a passo de teste local](docs/TESTE_LOCAL_ANDROID_STUDIO.md). Ele explica a abertura do projeto, configuração de Java/SDK, criação do emulador, execução pelo botão Run, testes automatizados e um túnel SSH manual no próprio computador.

## Compilar e instalar

Requisitos: JDK 17 ou superior, Android SDK Platform 37, Build Tools 36 e acesso aos repositórios Google/Maven Central. AGP 9.1.1 exige Gradle 9.3.1, incluído no wrapper. Configure `JAVA_HOME` e `ANDROID_HOME`, ou `sdk.dir` em `local.properties` (não versionar). O script de build não depende de configurações pessoais.

```bash
./scripts/build.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:architectureCheck
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

O APK debug serve para instalação direta/testes. Uma entrega de produção exige assinatura própria. Nenhuma chave de acesso real acompanha o aplicativo. Não publique uma chave de assinatura ou chave SSH junto do projeto.

## Configuração

1. Em **Ajustes → Perfil SSH**, informe host IPv6 da EC2, porta 22, usuário `ubuntu` e nome do perfil. Porta SOCKS5 padrão: 1080. A escuta permanece restrita a `127.0.0.1`.
2. Se a chave tiver senha, informe-a **antes** de tocar em **Importar chave privada**. Escolha o arquivo PEM/OpenSSH pelo seletor Android. O formato, a senha e a possibilidade de descriptografia são verificados pelo JSch antes do armazenamento. RSA, ECDSA e Ed25519 dependem dos formatos aceitos pela biblioteca; arquivos PPK/formatos incompatíveis são rejeitados com mensagem.
3. Salve o perfil. Chave e senha são cifradas com AES-GCM e chave não exportável do Android Keystore. Não são exibidas nem registradas. É possível criar, selecionar e excluir perfis; excluir também remove suas credenciais.
4. Em **Rede**, escolha Somente Wi-Fi (padrão), Somente dados móveis ou Automático.
5. Toque em **Conectar**. Compare a impressão digital SHA-256 com a obtida por um canal confiável na EC2. Aceite somente após a conferência. A primeira tentativa é encerrada para confirmação e uma nova é aberta ao aceitar. A confiança é persistida por host/porta. Mudanças de chave são bloqueadas; não existe botão para ignorá-las.

O aplicativo não descobre EC2 nem recebe credenciais AWS. Para obter o host, use no computador os comandos da seção 1.1 de [docs/prompt-261014.md](docs/prompt-261014.md), com seu perfil AWS já autenticado. Interrompa se o ID/endereço for vazio, `None` ou houver erro. O caminho `~/.ssh` é do computador; transfira a chave com segurança e importe pelo seletor, sem inserir o caminho Linux no Android.

## Como o SOCKS5 reverso funciona

O JSch solicita `tcpip-forward` para o loopback remoto com `want_reply=true` e espera a confirmação. O servidor encaminha cada conexão através de um canal `forwarded-tcpip`. Um `ForwardedTCPIPDaemon` no celular processa RFC 1928, abre o destino através da rede escolhida e transfere os dados. Há suporte a `CONNECT` por domínio, IPv4 e IPv6; DNS de `socks5h` acontece no celular. `BIND`, `UDP ASSOCIATE` e métodos de autenticação não suportados recebem respostas de erro.

O estado Conectado só aparece depois do aceite do encaminhamento. Porta ocupada/rejeitada é erro terminal; não há fallback para um proxy que já esteja aberto. O listener não precisa de Security Group na porta 1080. Detalhes e fontes estão em [ARCHITECTURE.md](ARCHITECTURE.md).

## Rede, IPv6 e mudanças

Somente redes Wi-Fi/móveis que o Android considera validadas, com INTERNET e sem VPN, são candidatas. O acesso efetivo ao host é verificado pela conexão SSH, não pela consulta de IP. Cada seleção mantém o mesmo `Network`: sockets SSH e SOCKS5 usam sua socket factory, e os nomes são resolvidos com `Network.getAllByName`. Não há vínculo global do processo nem fallback silencioso para outra rede.

No modo automático, Wi-Fi tem prioridade. Falhas temporárias impedem novas tentativas nessa rede por 30 s e permitem tentar a móvel. Ao retornar de dados móveis, o Wi-Fi precisa permanecer candidato por 15 s. Trocas fecham sessão, canais e sockets anteriores e interrompem conexões em andamento. Tentativas usam 1, 2, 4, 8, 16, 32 e no máximo 60 s. Autenticação, identidade do servidor e encaminhamento rejeitado não geram repetição infinita. Desconectar cancela a tentativa/reconexão e libera o serviço.

**Atualizar IPv6** funciona sem sessão SSH conectada. A consulta HTTPS direta a `https://api6.ipify.org` usa a mesma seleção de rede. Um IPv4 não é aceito como substituto. São exibidos endereço, rede, horário e desatualização/erro. O botão **Copiar IPv6** copia apenas o endereço atual. Endereços anteriores não são apresentados como pertencentes a uma nova rede. Falha no ipify não derruba o SSH nem comprova ausência de rota IPv6.

## Segundo plano e permissões

O túnel pertence a um coordenador único no serviço em primeiro plano. Fechar/recriar a Activity não o encerra. A notificação persistente mostra o estado e oferece Desconectar. O aplicativo solicita POST_NOTIFICATIONS ao conectar; negá-la não dispensa o serviço do requisito de notificação do Android. INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE e FOREGROUND_SERVICE/specialUse são declaradas, além de WAKE_LOCK e BOOT_COMPLETED.

Um wake lock limitado e renovado durante a conexão ajuda com a tela apagada, mas não garante sobrevivência a encerramento do processo, Doze, economia de bateria ou decisões do fabricante. Se o aparelho interromper sessões em testes, revise a configuração de bateria desse aplicativo. Não há pedido automático para excluir o aplicativo da economia. Após morte do processo, o estado inicia desconectado. A opção de retomada após reboot oferece uma notificação para abrir o aplicativo e conectar manualmente, sujeita à permissão de notificações; não tenta contornar restrições de início de FGS em segundo plano.

## Logs e métricas

Logs tem abas Uso do SOCKS5 e Túnel SSH. Há destino/texto, estado, rede e período (última hora, 24 h ou histórico), pausa apenas visual, cópia dos registros filtrados e limpeza com confirmação. Registros ativos são preservados na limpeza. Metadados são cifrados no armazenamento privado; não há captura de conteúdo, URLs completas, cabeçalhos ou descriptografia HTTPS.

**Enviados** são bytes de dados EC2 → destino; **Recebidos**, destino → EC2. Os acumulados da sessão são separados por Wi-Fi/dados móveis e zerados ao iniciar uma sessão manual nova. Não são o consumo exato da franquia: overhead SSH/TCP, DNS e consultas diretas de IP ficam de fora. Publicação/persistência dos contadores é agrupada em intervalos de 1 s. O histórico preserva até 1000 registros encerrados/ativos combinados, removendo encerrados mais antigos; ativos não são descartados e podem temporariamente superar esse limite. Eventos são limitados a 500. Falhas de persistência não interrompem o túnel. Registros ativos recuperados após morte do processo viram erro, sem simular uma sessão viva.

## EC2 e validação real

Não altere automaticamente um `sshd` já funcional. Confira sua configuração efetiva, blocos Match e restrições da chave: `AllowTcpForwarding remote` ou `yes`, `DisableForwarding no`, `PermitListen` permitindo `127.0.0.1:1080` e **GatewayPorts no**. `GatewayPorts yes` pode forçar exposição pública. O Security Group precisa permitir TCP 22 a partir das redes atuais do celular; acesso pelo computador não comprova acesso móvel. Não abra 1080 publicamente.

Se Chromium/LotoBot estiver em container, o loopback do container é outro namespace. Em Linux, uma configuração deliberada de rede host permite alcançar o listener no host. Avalie o isolamento antes de adotá-la; não resolva expondo o SOCKS5 à internet.

Encerre apenas o túnel Linux conhecido que ocupa a porta, ou use 1081 no perfil e nos comandos. Na EC2:

```bash
ss -lntp | grep ':1080'
curl --fail --silent --show-error --connect-timeout 15 --max-time 30 \
  --proxy socks5h://127.0.0.1:1080 https://api6.ipify.org
```

Consulte/atualize o IPv6 no celular em seguida e compare endereços normalizados:

```bash
python3 scripts/compare_ipv6.py 'IPV6_DO_CELULAR' 'IPV6_RETORNADO_NA_EC2'
```

Confirme listener restrito, resposta IPv6, igualdade, cópia no aplicativo e registro da requisição com rede/estados/duração/bytes. Repita em Wi-Fi e móvel e após troca de rede. Endereços temporários distintos podem demandar investigação da seleção IPv6 de origem; o aplicativo ainda não fixa um IPv6 de origem, portanto igualdade deve ser comprovada e não presumida. Nunca execute esses testes no portal Loterias Online da CAIXA.

## Testes e diagnóstico

- `:app:testDebugUnitTest`: protocolo/fragmentação, respostas de erro, IPv6, bytes, políticas e coordenador com fakes.
- `scripts/test-ssh-local.sh`: integração real com OpenSSH temporário em loopback e chaves descartáveis; requer `sshd` instalado e porta SSH 22222 livre; a porta SOCKS5 de teste é escolhida automaticamente. O script encerra somente o processo que criou.
- `:app:architectureCheck`: impede imports proibidos nas camadas internas.
- `:app:lintDebug`: Android Lint.

Consulte [docs/VALIDATION.md](docs/VALIDATION.md) para os resultados efetivamente executados e o que ainda precisa de celular/EC2. [UI_SPEC.md](UI_SPEC.md) documenta a interface.

Se não conecta: confira host/porta, acesso SSH da rede, usuário/chave/senha, rota IPv6 e identidade do host. Se SSH conecta mas SOCKS5 não: confira aceite/porta, `sshd`, listener no namespace correto, DNS/rota de destino e logs individuais. Se só ipify falha: o diagnóstico externo é independente; valide outro destino sem confundir os estados.

Em perdas abruptas de rede, o `sshd` pode conservar temporariamente o listener de uma sessão cujo TCP não recebeu o encerramento. Uma configuração deliberada de `ClientAliveInterval 30` e `ClientAliveCountMax 3` no servidor ajuda a liberar essas sessões (~90 s sem resposta). Não é aplicada automaticamente pelo aplicativo. Se o encaminhamento for recusado nesse intervalo, o aplicativo apresenta erro explícito; aguarde a liberação e conecte novamente. Nunca encerre um listener/processo sem identificar sua origem.

As versões e fontes oficiais das dependências estão em [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md). O teste instrumentado reproduzível usa `scripts/test-android-local.sh emulator-5554` com um emulador descartável já iniciado; exige `adb` no PATH e as portas SSH/HTTP locais 22222/22223 livres, apaga somente os dados desse aplicativo no emulador e gera chaves temporárias removidas ao concluir. Não o execute no seu celular pessoal.

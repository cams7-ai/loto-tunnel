# Testar o LotoBot Tunnel no Android Studio — passo a passo

Este guia foi escrito para quem ainda não conhece a IDE. Os comandos são para **Linux/Bash**, usando a pasta atual do projeto:

```text
/home/cams7/Dev/LotoBot/loto-tunnel
```

Você vai abrir o aplicativo num celular virtual, executar testes e, se desejar, conectar manualmente a um servidor SSH temporário no próprio computador. **Não precisa de EC2, credenciais AWS ou da sua chave de produção.**

A sequência recomendada é: **etapas 1–5 para conhecer a interface**, **etapa 6 para validar automaticamente** e **etapa 7 para praticar o túnel manualmente**. A etapa manual é opcional, mas permite observar a conexão enquanto você usa o aplicativo.

> O emulador usa a conectividade oferecida pelo computador. Esse teste local não comprova a saída pela operadora de um celular físico. O IPv6 externo também depende da conectividade do computador.

## 1. Abrir o projeto certo

1. Abra o **Android Studio**.
2. Na tela inicial, clique em **Open**. Se outro projeto já estiver aberto, use **File → Open**.
3. Selecione a pasta **loto-tunnel**, no caminho acima, e confirme.
4. Abra a pasta que contém `settings.gradle.kts`, `build.gradle.kts`, `gradlew` e a pasta `app`. Não selecione somente `app` ou `docs`.
5. Se a IDE pedir confirmação de confiança, confira que é este projeto e aceite para permitir a compilação.
6. Aguarde a sincronização do Gradle e a indexação. A primeira abertura pode baixar arquivos e levar alguns minutos.

**Gradle** é a ferramenta que baixa as bibliotecas e compila o aplicativo. **Sync** é a sincronização dessas configurações com a IDE.

Se aparecer uma sugestão para atualizar o plugin Android ou o Gradle, mantenha inicialmente as versões do projeto. Ele já usa **AGP 9.1.1 e Gradle 9.3.1**. Primeiro faça o teste funcionar.

### Onde ficam as ferramentas

| Ferramenta | Para que você vai usar | Como abrir |
| --- | --- | --- |
| Project | Encontrar arquivos e pastas | **View → Tool Windows → Project** |
| Terminal | Digitar os comandos deste guia | **View → Tool Windows → Terminal** |
| Build | Ver erros de sincronização e compilação | **View → Tool Windows → Build** |
| Device Manager | Criar/iniciar o celular virtual | **View → Tool Windows → Device Manager** |
| Running Devices | Ver e operar o emulador dentro da IDE | **View → Tool Windows → Running Devices** |
| Logcat | Ver falhas e mensagens Android | **View → Tool Windows → Logcat** |

Os nomes podem variar um pouco entre versões. Se não encontrar uma ação, pressione **Ctrl+Shift+A**, digite seu nome, por exemplo `Device Manager`, e escolha a ação correspondente.

Para ver `docs/` e `scripts/`, pode ser mais fácil trocar o seletor no topo do painel Project de **Android** para **Project**. Dê dois cliques neste arquivo para reabrir o guia durante o teste.

## 2. Conferir Java e Android SDK

### 2.1. Java usado pelos botões da IDE

1. Abra **File → Settings**.
2. Vá a **Build, Execution, Deployment → Build Tools → Gradle**.
3. Encontre **Gradle JDK**, ou a configuração equivalente de JVM/Java nas versões mais novas.
4. Selecione um JDK **17 ou superior**. Para repetir o ambiente já usado neste computador, você pode selecionar/adicionar:

   ```text
   /home/cams7/.local/share/jdks/temurin-25
   ```

5. Caso esse caminho não apareça, use a opção **Add JDK** e selecione a pasta. Se preferir outro JDK, confira a versão e use o mesmo caminho nos comandos da próxima seção.
6. Clique em **Apply** e **OK**.

O caminho acima foi encontrado no ambiente em que o projeto foi validado. Em outro computador, será necessário adaptar o caminho. O projeto não exige instalar especificamente Java 25; exige uma versão compatível, no mínimo 17.

### 2.2. Android SDK

1. Abra **Tools → SDK Manager**. Se o menu não aparecer, procure `SDK Manager` com **Ctrl+Shift+A**.
2. Anote o **Android SDK Location**. Neste computador, o projeto aponta para:

   ```text
   /home/cams7/Android/Sdk
   ```

3. Em **SDK Platforms**, confira a plataforma **API 37** utilizada para compilar o projeto. Se necessário, habilite **Show Package Details** para localizar os componentes.
4. Em **SDK Tools**, confira **Android SDK Platform-Tools**, **Android Emulator** e **Android SDK Build-Tools 36.0.0**. Use **Show Package Details** para escolher a versão dos Build Tools.
5. Instale os componentes ausentes com **Apply** e siga as telas de download/licença.
6. Execute **File → Sync Project with Gradle Files**, ou procure essa ação com **Ctrl+Shift+A**.

**A plataforma de compilação API 37 e o emulador Android 13/API 33 são coisas diferentes.** O projeto pode ser compilado com API 37 e instalado no Android 13, sua versão mínima suportada.

### 2.3. Preparar o Terminal da IDE

Abra **View → Tool Windows → Terminal**. Cole o bloco abaixo e pressione Enter:

```bash
cd /home/cams7/Dev/LotoBot/loto-tunnel
export JAVA_HOME="/home/cams7/.local/share/jdks/temurin-25"
export ANDROID_HOME="/home/cams7/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
java -version
adb version
./gradlew --version
```

Se você escolheu outros caminhos, ajuste `JAVA_HOME` e `ANDROID_HOME` antes de executar.

**Resultado esperado:** Java 17 ou superior, uma versão do Android Debug Bridge (`adb`) e Gradle **9.3.1**. Se aparecer Java 11, corrija `JAVA_HOME` antes de continuar.

Esses `export` valem somente para a sessão atual do terminal. Ao abrir outro terminal/aba, repita este bloco. Configurar **Gradle JDK** na IDE não garante que um comando digitado no terminal use o mesmo Java. [Referência oficial sobre Java na IDE e no terminal](https://developer.android.com/build/jdks).

## 3. Criar um celular virtual Android 13

Use um emulador dedicado a testes, por exemplo **LotoBot-Teste-API33**. O teste automatizado remove os dados do LotoBot Tunnel nesse emulador, incluindo perfis, credenciais importadas e histórico.

1. Abra **View → Tool Windows → Device Manager**.
2. Clique em **+ → Create Virtual Device**, ou **Create Device**.
3. Escolha um modelo de telefone, por exemplo **Pixel 4**, e clique em **Next**.
4. Escolha uma imagem **Android 13 / API 33 / Google APIs / x86_64**, para este computador Linux Intel/AMD.
5. Se não estiver instalada, clique no ícone de download e aguarde. A imagem ocupa vários GB depois de instalada.
6. Se a imagem não aparecer entre as recomendadas, procure nas outras abas/listas de imagens.
7. Avance, dê o nome **LotoBot-Teste-API33** e clique em **Finish**.
8. Na lista de dispositivos, clique no botão de iniciar, normalmente **▶**.
9. Aguarde a tela inicial do Android. O primeiro boot pode demorar mais.

Em computadores ARM, escolha uma imagem compatível com a arquitetura do computador; não copie a seleção x86_64 automaticamente.

O telefone pode aparecer em **Running Devices** dentro da IDE ou em uma janela separada. Ambas as formas funcionam. [Referência oficial de criação de dispositivos virtuais](https://developer.android.com/studio/run/managing-avds).

### Confirmar que o emulador está pronto

No Terminal já preparado, execute:

```bash
adb devices
```

Exemplo de resultado:

```text
List of devices attached
emulator-5554    device
```

`emulator-5554` é o **identificador** usado nos comandos. O seu pode ser diferente, como `emulator-5556`; nesse caso, substitua-o nos próximos passos.

Se estiver `offline`, aguarde o boot. Se a lista estiver vazia, confirme que você iniciou o dispositivo no Device Manager.

## 4. Compilar e abrir pelo botão Run

1. Na barra superior do Android Studio, selecione a configuração **app**.
2. No seletor de dispositivo ao lado, escolha **LotoBot-Teste-API33**.
3. Clique no triângulo verde **Run ▶**, ou use **Run → Run 'app'**.
4. Aguarde a compilação e a instalação. Acompanhe a janela **Build** se surgir algum erro.
5. O aplicativo deve abrir com o título **LotoBot Tunnel** e os destinos inferiores **Conexão**, **Logs** e **Ajustes**.

Se a configuração `app` não estiver disponível depois da sincronização, abra **Run → Edit Configurations**, adicione uma configuração **Android App** e selecione o módulo `app`. Use a Activity padrão de lançamento.

**Resultado esperado numa instalação nova:** estado **Desconectado**, host ainda sem configuração e nenhum registro de tráfego. Tocar em Conectar antes de configurar host/chave deve mostrar uma mensagem de validação, não um túnel conectado.

**Run** instala e abre o aplicativo. Ele não prepara sozinho o servidor SSH local nem cria um perfil. Para o túnel funcionar, use a etapa 6 ou 7.

## 5. Conhecer a interface antes do túnel

1. Em **Conexão**, examine os cartões de perfil, rede, IPv6 e métricas. Role verticalmente para encontrar todas as ações.
2. Toque em **Atualizar IPv6**. Isso pode funcionar mesmo com o SSH desconectado, desde que o emulador tenha Wi-Fi validado e acesso IPv6 ao serviço externo.
3. Se um IPv6 atual aparecer, toque em **Copiar IPv6**. O aplicativo confirma a cópia; o conteúdo copiado deve ser apenas o endereço.
4. Em **Logs**, alterne **Uso do SOCKS5** e **Túnel SSH**. **Mostrar filtros** expande os filtros. Numa instalação nova, os históricos podem estar vazios.
5. Em **Ajustes**, abra o seletor **Configuração: Perfil SSH** e escolha **Rede**. Observe os modos Somente Wi-Fi, Somente dados móveis e Automático.
6. Mantenha **Somente Wi-Fi** para o primeiro teste local.

Se o IPv6 estiver indisponível, registre a mensagem. Isso não significa, por si só, que o SSH local não funciona. O teste HTTP local da próxima etapa não depende de um IPv6 público.

## 6. Executar o teste local automatizado

Este é o caminho mais curto para verificar o túnel completo. O script prepara um OpenSSH local, cria uma chave descartável, configura o aplicativo para o teste, opera a interface e salva capturas.

> Use somente o emulador dedicado. O script limpa os dados desse aplicativo no emulador e atualiza os arquivos de `capturas/` com a nova execução. Não use um celular pessoal para esse teste automatizado.

### 6.1. Conferir os programas necessários

No Terminal preparado:

```bash
command -v python3
command -v ssh-keygen
command -v rg
ls -l /usr/sbin/sshd
```

Os quatro devem existir. `rg` é o programa **ripgrep**. Se estiverem ausentes num Ubuntu/Debian, você pode instalar os pacotes necessários pelo gerenciador do sistema:

```bash
sudo apt install openssh-server openssh-client python3 ripgrep
```

Esse comando só é necessário se faltar algum programa. O teste usa seu próprio servidor temporário na porta 22222; não requer editar `/etc/ssh/sshd_config` nem abrir portas para a internet. A instalação do pacote OpenSSH pode também iniciar o serviço SSH do sistema; ele é separado do servidor temporário do teste.

Confira se as portas locais de teste estão livres:

```bash
ss -lntp 'sport = :22222 or sport = :22223'
```

**Resultado esperado:** somente a linha de cabeçalho, sem listeners. Se existir algum, identifique sua origem antes de continuar. Não encerre processos desconhecidos.

### 6.2. Rodar o script

Com o emulador iniciado e o identificador confirmado:

```bash
./scripts/test-android-local.sh emulator-5554
```

Substitua `emulator-5554` pelo identificador mostrado no seu `adb devices`.

Enquanto o teste roda, deixe a IDE e o emulador abertos e **não interaja com a tela**. O teste toca em botões, desliga/liga Wi-Fi e muda temporariamente o tamanho da tela/fonte. Execute somente um script de integração por vez, pois ambos usam a porta SSH 22222.

O script já informa ao teste o usuário Linux correto e uma porta SOCKS5 remota livre. Não é necessário preencher os campos manualmente nesta etapa.

### 6.3. Interpretar o resultado

Procure no terminal:

```text
Starting 2 tests ...
Finished 2 tests ...
BUILD SUCCESSFUL
```

O texto exato pode variar, mas o resultado deve ter **2 testes, 0 falhas e 0 ignorados/skips**. O script também copia as capturas para o projeto.

Os testes verificam SSH/SOCKS5 real, tráfego HTTP local, consulta/cópia/atualização do IPv6 quando há resultado direto disponível, interface/rolagem, perda de Wi-Fi, cancelamento e armazenamento protegido. Quando a consulta direta retorna IPv6, o teste compara esse endereço com uma requisição HTTPS através do proxy.

**Importante:** se a consulta direta não retornar IPv6, o teste pode concluir outras verificações sem executar a igualdade de IPv6. Para comprovar essa parte, confira se `capturas/ipv6-validation.txt` foi produzido pela execução atual e contém `Normalized equality: PASS`.

Abra no painel **Project**:

- `capturas/01-conexao.png` a `capturas/06-aguardando-rede.png`;
- `capturas/07-tela-pequena-fonte-ampliada.png` e `08-acao-fonte-ampliada.png`;
- `capturas/ipv6-validation.txt`, quando disponível;
- `app/build/reports/androidTests/connected/debug/index.html`, para o relatório detalhado. Use **Open in Browser** ou abra o arquivo no navegador, se necessário.

Ao terminar, o script para os servidores temporários e apaga sua chave descartável. Portanto, o perfil usado pelo teste não serve para continuar conectado depois. A infraestrutura de testes também pode remover o aplicativo ao concluir; se isso acontecer, clique em **Run 'app'** para instalá-lo novamente.

Não execute a classe `AndroidFlowTest` isoladamente pelo triângulo do editor esperando uma validação completa: ela precisa da chave temporária e dos servidores preparados pelo script. Sem a chave, o teste de fluxo pode ser marcado como ignorado.

### 6.4. Rodar também a suíte JVM e o Lint

Quando o teste Android já tiver terminado, execute:

```bash
./scripts/test-ssh-local.sh
```

Esse script não precisa do emulador. Ele cria um OpenSSH temporário, roda os **14 testes JVM**, compila o APK, executa Android Lint e confere a arquitetura.

**Resultado esperado:** `BUILD SUCCESSFUL`, sem falhas nos testes. Avisos do Lint não são o mesmo que erros. O relatório fica em `app/build/reports/lint-results-debug.html`.

Para rodar apenas os testes comuns e verificações sem preparar o servidor local:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:architectureCheck --console=plain
```

Nesse último comando, o teste JVM de integração SSH pode ficar ignorado por não receber uma chave de fixture. Isso é esperado, mas não comprova o túnel; para a integração, use o script anterior.

## 7. Testar manualmente a conexão local

Faça esta etapa depois que os scripts automatizados tiverem terminado. Ela mantém o servidor disponível enquanto você navega no aplicativo.

### 7.1. Preparar o servidor no Terminal

Confira as portas usadas nesta etapa:

```bash
ss -lntp 'sport = :22222 or sport = :22223 or sport = :1081'
```

Continue somente quando essas portas estiverem livres. A porta SOCKS5 **1081** evita interferir num túnel Linux que já esteja usando 1080.

No mesmo Terminal preparado anteriormente, execute o bloco abaixo. Ele cria arquivos temporários próprios e não modifica o SSH do sistema:

```bash
LOTO_LOCAL_DIR="$(mktemp -d /tmp/loto-manual.XXXXXX)"
LOTO_LOCAL_USER="$(id -un)"
ssh-keygen -q -t ed25519 -N '' -f "$LOTO_LOCAL_DIR/host"
ssh-keygen -q -t rsa -b 2048 -m PEM -N '' -f "$LOTO_LOCAL_DIR/client.pem"
mkdir -p "$LOTO_LOCAL_DIR/web"
printf '%s\n' 'LOTOBOT: teste HTTP local funcionando' > "$LOTO_LOCAL_DIR/web/index.html"

cat > "$LOTO_LOCAL_DIR/sshd_config" <<CONFIG
ListenAddress 127.0.0.1
Port 22222
HostKey $LOTO_LOCAL_DIR/host
PidFile $LOTO_LOCAL_DIR/sshd.pid
AuthorizedKeysFile $LOTO_LOCAL_DIR/client.pem.pub
StrictModes no
UsePAM no
PasswordAuthentication no
KbdInteractiveAuthentication no
AllowUsers $LOTO_LOCAL_USER
AllowTcpForwarding remote
GatewayPorts no
PermitListen 127.0.0.1:1081
ClientAliveInterval 5
ClientAliveCountMax 2
CONFIG

/usr/sbin/sshd -D -e -f "$LOTO_LOCAL_DIR/sshd_config" > "$LOTO_LOCAL_DIR/sshd.log" 2>&1 &
LOTO_LOCAL_SSH_PID=$!
python3 -m http.server 22223 --bind 127.0.0.1 --directory "$LOTO_LOCAL_DIR/web" > "$LOTO_LOCAL_DIR/http.log" 2>&1 &
LOTO_LOCAL_HTTP_PID=$!

printf 'Usuário do perfil: %s\nArquivos de teste: %s\n' "$LOTO_LOCAL_USER" "$LOTO_LOCAL_DIR"
ssh-keygen -lf "$LOTO_LOCAL_DIR/host.pub"
```

Anote o **usuário do perfil** e a impressão digital **SHA256:** apresentada pelo último comando. Guarde esse Terminal aberto: as variáveis com caminhos e PIDs serão usadas para encerrar os processos depois.

Após alguns segundos, confira:

```bash
kill -0 "$LOTO_LOCAL_SSH_PID"
kill -0 "$LOTO_LOCAL_HTTP_PID"
ss -lntp 'sport = :22222 or sport = :22223'
```

`kill -0` apenas verifica a existência do processo; não o encerra. As duas portas devem aparecer em escuta **127.0.0.1**. Se algum comando falhar, leia os arquivos de diagnóstico e pare antes de configurar o aplicativo:

```bash
cat "$LOTO_LOCAL_DIR/sshd.log"
cat "$LOTO_LOCAL_DIR/http.log"
```

`StrictModes no` e os arquivos descartáveis acima pertencem somente ao servidor de teste. Não copie essa configuração inteira para a EC2 ou para o SSH do sistema.

### 7.2. Colocar a chave descartável no emulador

No mesmo Terminal:

```bash
adb -s emulator-5554 push "$LOTO_LOCAL_DIR/client.pem" /sdcard/Download/loto-teste-local.pem
```

Ajuste o identificador se necessário. Use somente essa chave de teste, não a sua chave da EC2.

### 7.3. Preencher o aplicativo

1. Clique em **Run 'app'** na IDE para instalar/abrir o aplicativo.
2. No emulador, entre em **Ajustes → Perfil SSH**. Se estiver na página Rede, altere o seletor de Configuração para Perfil SSH.
3. Preencha:

| Campo | Valor para este teste |
| --- | --- |
| Nome do perfil | `SSH local manual` |
| Host SSH | `10.0.2.2` |
| Porta SSH | `22222` |
| Usuário SSH | O usuário mostrado na etapa 7.1, por exemplo `cams7`; não use `ubuntu` automaticamente |
| Senha da chave | Vazia: a chave descartável foi criada sem senha |
| Escuta remota | `127.0.0.1`, já fixa no aplicativo |
| Porta SOCKS5 remota | `1081` |
| Timeout / keepalive / limite | Mantenha os padrões `15` / `30` / `3` |

4. Toque em **Importar chave privada**.
5. No seletor de arquivos do Android, abra **Downloads** e selecione **loto-teste-local.pem**. Se necessário, use o menu lateral do seletor para localizar Downloads.
6. Aguarde a confirmação e toque em **OK**. Role para baixo e toque em **Salvar perfil**.
7. Abra **Ajustes → Rede**, marque **Somente Wi-Fi** e toque em **Salvar configuração**.

No emulador, **10.0.2.2** é o endereço especial para alcançar o loopback do computador. Usar `127.0.0.1` como host SSH no aplicativo tentaria alcançar o próprio Android. [Referência oficial de rede do emulador](https://developer.android.com/studio/run/emulator-networking).

### 7.4. Conectar e conferir a identidade

1. Entre em **Conexão** e toque em **Conectar**.
2. Permita notificações se o Android perguntar, para acompanhar o serviço em segundo plano.
3. Na primeira conexão, compare a impressão digital exibida com a **SHA256:** obtida por `ssh-keygen` na etapa 7.1.
4. Se forem iguais, toque em **Confiar e conectar**.
5. Aguarde o estado **Conectado** e a indicação de rede **Wi-Fi**.
6. Em **Logs → Túnel SSH**, confira o evento de aceite da porta remota.

No Terminal do computador:

```bash
ss -lntp 'sport = :1081'
```

**Resultado esperado:** escuta em **127.0.0.1:1081**. A escuta SSH local em 22222 é outra porta, usada para transportar o túnel.

Se surgir erro de porta ocupada, não considere o teste conectado. Identifique o listener antes de repetir.

### 7.5. Fazer uma requisição pelo SOCKS5

No mesmo Terminal do computador:

```bash
curl --fail --silent --show-error \
  --connect-timeout 15 --max-time 30 \
  --proxy socks5h://127.0.0.1:1081 \
  http://10.0.2.2:22223/
```

**Resultado esperado:**

```text
LOTOBOT: teste HTTP local funcionando
```

O caminho desta requisição é: **curl no computador → listener remoto do SSH → Android → destino HTTP local**. O host `10.0.2.2` é interpretado no lado Android para alcançar o computador que hospeda o emulador.

Em **Logs → Uso do SOCKS5**, deve aparecer `10.0.2.2:22223`, com rede Wi-Fi, encerramento, duração e bytes enviados/recebidos. Espere cerca de 1 segundo para a publicação agrupada dos contadores. Requisições rápidas podem ter duração visual de zero segundos.

### 7.6. Comparar o IPv6 público

1. No aplicativo, toque em **Atualizar IPv6** e espere um endereço atual, sem aviso de desatualização.
2. No Terminal, execute próximo no tempo à consulta direta:

   ```bash
   curl --fail --silent --show-error \
     --connect-timeout 15 --max-time 30 \
     --proxy socks5h://127.0.0.1:1081 \
     https://api6.ipify.org
   ```

3. Compare esse resultado com o endereço da tela. Copie/transcreva os dois valores e substitua os textos abaixo pelos IPv6 reais:

   ```bash
   python3 scripts/compare_ipv6.py 'IPV6_DA_TELA' 'IPV6_DO_CURL'
   ```

4. O resultado deve indicar **IPv6 iguais**. Se forem diferentes, a validação não está aprovada; atualize as consultas e investigue rede/endereço de origem.
5. Confira `api6.ipify.org:443` no histórico SOCKS5. A consulta direta do botão Atualizar não deve criar um registro SOCKS5.
6. Teste **Copiar IPv6**. Se o emulador não compartilhar a área de transferência com o computador, você pode conferir a colagem num campo de texto do próprio Android e transcrever o endereço para o Terminal.

Se não houver IPv6 externo disponível, registre essa parte como **não validada**, mesmo que o HTTP local tenha funcionado.

### 7.7. Exercitar a interface e a perda de rede

- **Segundo plano:** toque no botão Home do Android e volte ao aplicativo. Confira se continua conectado e se a notificação oferece Desconectar.
- **Wi-Fi indisponível:** desligue o Wi-Fi pelas configurações rápidas do Android. Em modo Somente Wi-Fi, espere **Aguardando rede**; a rede móvel não deve ser usada silenciosamente. Religue o Wi-Fi e observe a retomada.
- **Listener após perda abrupta:** pode levar alguns segundos para o servidor de teste liberar uma sessão antiga. Se houver erro de encaminhamento, aguarde e toque em Conectar novamente; não aceite um falso estado conectado.
- **Logs:** use Mostrar filtros, filtre por destino/estado/rede/período, pause a atualização visual, gere outro curl e retome. O registro deve continuar durante a pausa. Teste copiar os registros filtrados e limpar o histórico.
- **Fonte ampliada:** abra as configurações do Android e procure por tamanho da fonte. Aumente e confira que consegue rolar até os botões sem sobreposição.
- **Desconexão manual:** toque em Desconectar/Cancelar reconexão, espere e confira que não reconecta sozinho. A porta 1081 deve deixar de aparecer no `ss` após a liberação pelo servidor.

As redes Wi-Fi e móvel do emulador não representam duas operadoras/saídas físicas independentes. Repita a validação em um celular real para comprovar esse cenário.

### 7.8. Encerrar os recursos de teste

Primeiro desconecte pelo aplicativo. No **mesmo Terminal da etapa 7.1**, encerre somente os processos cujos PIDs você guardou:

```bash
kill "$LOTO_LOCAL_SSH_PID" "$LOTO_LOCAL_HTTP_PID"
wait "$LOTO_LOCAL_SSH_PID" "$LOTO_LOCAL_HTTP_PID" 2>/dev/null
adb -s emulator-5554 shell rm -f /sdcard/Download/loto-teste-local.pem
```

O `wait` pode retornar um código diferente de zero porque os processos foram encerrados deliberadamente. Confira a liberação das portas:

```bash
ss -lntp 'sport = :22222 or sport = :22223 or sport = :1081'
```

Depois de confirmar que os processos de teste terminaram, remova apenas a pasta temporária criada pelo `mktemp`:

```bash
rm -rf "${LOTO_LOCAL_DIR:?}"
```

Exclua o perfil de teste em Ajustes se não for reutilizá-lo. Isso remove a chave importada do aplicativo. Para encerrar o emulador, use **Device Manager → menu do dispositivo → Stop**.

**Se repetir o teste manual com uma nova chave de servidor:** o aplicativo pode bloquear a mudança de identidade em `10.0.2.2:22222`, mesmo após excluir o perfil. Isso é a proteção de host funcionando. Você pode usar outro par host/porta de teste, por exemplo mudar `Port 22222` no arquivo temporário para `Port 22224` e ajustar a porta no perfil, conferindo novamente a nova impressão digital. Outra opção, somente no emulador descartável, é limpar os dados do aplicativo nas configurações Android; isso remove todos os seus perfis, credenciais e histórico naquele emulador.

## 8. Consultar erros no Android Studio

### Erros ao compilar

Abra **View → Tool Windows → Build** e selecione a compilação que falhou. Procure a primeira mensagem de erro concreta, não apenas `BUILD FAILED`. Corrija o problema e sincronize/execute novamente.

### Aplicativo fechou inesperadamente

1. Abra **View → Tool Windows → Logcat**.
2. Selecione o emulador e o processo/pacote **br.com.lotobot.tunnel**.
3. No campo de consulta, use:

   ```text
   package:br.com.lotobot.tunnel
   ```

4. Procure **FATAL EXCEPTION** ou mensagens **AndroidRuntime** próximas do momento da falha.

Os destinos SOCKS5 e eventos de sessão são mostrados principalmente nas abas **Logs do aplicativo**, não no Logcat. [Referência oficial de Logcat](https://developer.android.com/studio/debug/logcat).

### Problemas frequentes

| Sintoma | O que verificar |
| --- | --- |
| Gradle exige Java 17, mas encontrou 11 | Ajuste Gradle JDK na IDE e `JAVA_HOME` no Terminal; confira `java -version` e `./gradlew --version` |
| SDK não encontrado/API 37 ausente | Confira SDK Manager e `sdk.dir` em `local.properties` |
| Botão Run/configuração app ausente | Aguarde Sync; confira que abriu a raiz do projeto e criou uma configuração Android App para módulo app |
| `adb: command not found` | Repita o bloco de preparação do Terminal com `platform-tools` no PATH |
| Emulador `offline`/lista vazia | Aguarde o boot; reinicie pelo Device Manager se necessário |
| Emulador não inicia por aceleração/KVM | Leia o diagnóstico do Emulator/Device Manager e confira virtualização/KVM do Linux; não mude o código do app para resolver isso |
| Falha ao baixar dependências | Confira internet/proxy da IDE e que o Gradle não está em modo offline |
| `Permission denied` ao executar um script | Na raiz, use `chmod +x gradlew scripts/build.sh scripts/test-ssh-local.sh scripts/test-android-local.sh` |
| `Address already in use` | Verifique a porta com `ss`; conclua o outro teste ou identifique o listener. Não use `pkill sshd` nem encerre processos desconhecidos |
| Aguardando rede apesar de Wi-Fi ligado | Confira se AndroidWifi tem internet, por exemplo abrindo um site no navegador do emulador; conectado ao Wi-Fi não é o mesmo que rede validada |
| Conexão SSH recusada | Confira que o servidor temporário continua ativo, host 10.0.2.2 e porta 22222; consulte `sshd.log` |
| Autenticação recusada | Use o usuário de `id -un` e importe a chave descartável correta |
| Chave de servidor mudou | Confira a identidade; veja a orientação de repetição do teste manual. Não desative a verificação |
| Teste de fluxo ignorado/skipped | Execute `test-android-local.sh`, que prepara a chave e os servidores; ignorado não significa aprovado |
| Não consigo clicar enquanto o script roda | Aguarde terminar; a automação está operando a tela |
| IPv6 indisponível ou proxy diferente do direto | Registre a falha; confira conectividade IPv6 e repita consultas próximas no tempo. Não marque igualdade como validada |
| Teste falhou e faltaram capturas novas | O script só copia automaticamente após sucesso; veja primeiro o relatório Android e o Logcat |

## 9. Lista de conferência do teste manual

- [ ] Projeto abriu e sincronizou sem erro.
- [ ] Aplicativo compilou e abriu no Android 13.
- [ ] Perfil local foi salvo e a chave descartável foi importada pelo seletor.
- [ ] Impressão digital conferida com o servidor que você criou.
- [ ] Estado Conectado só apareceu depois do aceite do listener.
- [ ] Listener restrito a 127.0.0.1:1081.
- [ ] Requisição HTTP local funcionou pelo SOCKS5.
- [ ] A requisição apareceu nos logs com rede, estado e bytes.
- [ ] IPv6 direto e pelo proxy foram iguais, ou essa parte foi registrada como pendente.
- [ ] Copiar/Atualizar IPv6 funcionaram.
- [ ] Aguardando rede, retomada e desconexão manual foram observados.
- [ ] Filtros, pausa, cópia e limpeza dos logs foram exercitados.
- [ ] Tela com fonte ampliada manteve as ações acessíveis por rolagem.
- [ ] Recursos temporários foram encerrados e a chave descartável removida.

Os resultados já executados no desenvolvimento estão em [VALIDATION.md](VALIDATION.md). Para a configuração posterior na EC2 e no celular físico, continue pelo [README](../README.md). A documentação de aparência fica em [UI_SPEC.md](../UI_SPEC.md).

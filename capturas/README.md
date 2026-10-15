# Capturas reais do aplicativo

Estas imagens foram obtidas executando o APK no emulador oficial Android 13 / API 33, com um servidor OpenSSH real no computador e uma chave SSH descartável. Os dados não são simulações: o perfil local, os estados, os registros e o IPv6 foram observados na execução. A porta remota de teste é escolhida automaticamente para evitar confundir listeners existentes.

- `01-conexao.png`: sessão SSH aceita, por Wi-Fi do emulador, com IPv6 consultado via HTTPS.
- `02-perfil-ssh.png`: formulário real do perfil de teste, parcialmente visível na área de rolagem.
- `03-rede.png`: seleção de rede e avisos de interrupção/franquia.
- `04-uso-socks5.png`: registros das conexões reais ao destino HTTP local e a api6.ipify.org:443 pelo SOCKS5.
- `05-eventos-ssh.png`: eventos reais de abertura e aceite do listener.
- `06-aguardando-rede.png`: Wi-Fi do emulador desabilitado, em modo exclusivo Wi-Fi. A rede móvel não é usada como fallback nesse modo.
- `07-tela-pequena-fonte-ampliada.png`: tela 720×1280, densidade 320, fonte do sistema 150% (área lógica aproximada 360×640 dp).
- `08-acao-fonte-ampliada.png`: mesma configuração, após rolagem até Cancelar reconexão; a ação foi acionada e a desconexão confirmada pelo teste.

`ipv6-validation.txt` registra os endereços efetivamente observados diretamente e através do listener remoto; a igualdade foi comparada como valores IPv6. Isso comprova esse fluxo no emulador/OpenSSH local, sem comprovar acesso pela EC2 ou pela operadora do celular. O IPv6 do emulador corresponde à saída oferecida pelo computador que o hospeda.

As referências originais permanecem em `docs/` e não foram usadas como capturas. Os screenshots mostram o viewport, com rolagem vertical para o restante dos formulários/cartões; não são montagens de telas completas.

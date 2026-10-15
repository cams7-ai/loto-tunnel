# Recuperação da porta remota — 15/10/2026

- Implementado o botão **Liberar porta e reconectar** após recusa de encaminhamento remoto, com aviso de encerramento de todos os processos que usam a porta configurada.
- `scripts/test-ssh-local.sh`: **19 testes JVM, zero falhas e zero skips**, APK debug, Android Lint e verificação de arquitetura concluídos com `BUILD SUCCESSFUL`.
- Novos testes do coordenador verificam ação disponível apenas após a falha adequada, ausência de execução duplicada, cancelamento e falha de recuperação sem repetição automática.
- O teste OpenSSH real usa `ForceCommand` em um servidor descartável para validar o comando exato enviado pelo aplicativo e executar `fuser` somente sobre a porta temporária desse servidor, sem sudo. Comprova encerramento do listener anterior, criação do novo túnel e exibição de erro quando o comando retorna código diferente de zero.
- Não foram executados testes desta funcionalidade no celular físico/EC2 nem alterações de sudo no servidor real. A autorização sudo sem senha e a interação com o novo botão precisam ser verificadas nesse ambiente, conforme o [guia](TESTE_LOCAL_ANDROID_STUDIO.md#liberar-a-porta-pelo-celular).
- APK: `app/build/outputs/apk/debug/app-debug.apk`. A validação anterior abaixo descreve outra versão do APK e preserva seu registro histórico.

# Validação executada em 14/10/2026

A implementação interrompida foi retomada, completando transporte SSH, serviço/DI, interface, testes, documentação e APK. Este relatório distingue testes locais executados de validações que exigem o celular/EC2.

## Resultados

- APK debug compilado para Android mínimo 13 (API 33), alvo/compilação API 37. Caminho: `app/build/outputs/apk/debug/app-debug.apk` (~36 MB), SHA-256 `7333559a936a776fad93f345b92cf68bf20a7423d153fab612639228e0e3f7d0`.
- **14 testes JVM passaram, sem falhas ou skips**: protocolo SOCKS5 com leituras fragmentadas, métodos/comandos/endereço não suportados, domínio/IPv4/IPv6, DNS/rota/conexão recusada/timeout, contadores reais em sockets TCP locais, normalização/rejeição de IPv6, seleção de rede/histerese e reconexão limitada.
- Coordenador: conectar repetidamente sem duplicar sessão, desconectar sem reativar, cancelar backoff, perder/recuperar a rede permitida, alterar perfil durante handshake, Retry após confirmação e erro permanente sem repetição.
- Integração JVM com **OpenSSH real**, chaves descartáveis e loopback: primeira identidade exige confirmação; listener remoto efetivamente aceita SOCKS5; payloads chegam ao destino e retornam; segunda tentativa na mesma porta é rejeitada; fechamento termina canais e libera o listener.
- **Dois testes instrumentados passaram em Android 13/API 33, sem skips**. Um executou SSH real pelo `Network` Wi-Fi do emulador, tráfego por SOCKS5 remoto, consulta/cópia/atualização do IPv6, navegação após rolagem, permanência breve com tela apagada e ao deixar/reabrir a Activity, espera após desligar Wi-Fi, fonte 150% e cancelamento manual acessível por rolagem.
- O outro teste instrumentado validou Android Keystore/AES-GCM com round trip e IV diferente, exclusão e rejeição de chave inválida, confirmação/persistência/bloqueio de mudança de identidade do host, persistência/exclusão de perfis, totais separados por rede, 1000 registros/500 eventos, preservação de ativos na limpeza e recuperação de ativos como erro após reconstruir o histórico.
- Android Lint e verificação automatizada da direção de dependências foram executados. O Lint apresentou **0 erros e 11 avisos**; avisos de estilo e versões mais novas disponíveis permanecem documentados no relatório gerado em `app/build/reports/`.
- O comparador `scripts/compare_ipv6.py` foi executado com representações equivalentes e com dois endereços diferentes. Aceitou equivalência e recusou corretamente a diferença com código de saída 1.

## Igualdade de IPv6 realmente comprovada

O teste instrumentado abriu um cliente na porta SOCKS5 do servidor OpenSSH local, enviou `CONNECT api6.ipify.org:443`, realizou TLS com verificação do nome do servidor e consultou o serviço por HTTPS. Comparou o resultado normalizado com a consulta direta feita pelo aplicativo na seleção Wi-Fi.

Os dois endereços foram iguais. Valores efetivamente observados: [capturas/ipv6-validation.txt](../capturas/ipv6-validation.txt). O pedido aparece no histórico como `api6.ipify.org:443`, com rede, horários, estado final e bytes. A consulta direta é separada e não aparece como uso SOCKS5.

Esse teste usa o **emulador e um servidor OpenSSH local**, cuja saída do emulador depende do computador hospedeiro. Não foi realizado na EC2 e não comprova a internet residencial/operadora do celular físico.

## Capturas e inspeção visual

Oito PNGs reais em [capturas/](../capturas/README.md), incluindo os seis nomes solicitados e dois cenários de fonte ampliada. Referências originais em `docs/` foram preservadas. As capturas foram inspecionadas: texto/endereços quebram dentro dos cartões; navegação e cartões seguem tema claro; conteúdo extenso usa rolagem vertical; ação de cancelar foi alcançada e acionada em tela lógica 360×640 dp com fonte 150%. Diferenças de empilhamento, campos adicionais e filtros recolhidos estão em `UI_SPEC.md`.

Corridas e problemas identificados durante testes foram corrigidos: confirmar o host antes de a tentativa inicial terminar; rolagem preservada incorretamente ao trocar destino da navegação. Falhas intermediárias de automação não são tratadas como aprovação; as execuções posteriores passaram. Testes usam porta SOCKS5 livre escolhida automaticamente para evitar reutilização de listeners de outra execução.

## Ainda precisa do celular e da EC2

| Cenário | Situação |
| --- | --- |
| SSH à EC2 por IPv6 nas redes reais | Não executado |
| Comparação IPv6 celular físico ↔ proxy na EC2, em Wi-Fi e chip | Não executada; usar seção de validação do README |
| Somente Wi-Fi/Móvel/Automático em redes físicas e fallback/retorno | Política testada com fakes; somente Wi-Fi executado no emulador |
| Tela apagada por longos períodos, Doze e fabricante | Não executado; teste no emulador foi breve (2 s) |
| Mudança de Wi-Fi, rede física sem internet/sem IPv6, EC2 indisponível | Regras/falhas testadas localmente; cenários físicos pendentes |
| Importação pelo seletor Android de sua chave real, inclusive senha | Implementada; teste Android usou chave descartável por fixture, sem operar o seletor |
| Filtros/pausa/cópia/limpeza dos logs por interação visual completa | Implementados; persistência/limpeza testadas no adaptador; interação completa ainda manual |
| SOCKS5 simultâneo sob carga e franquia/bateria de longo prazo | Não executado |
| Reiniciar celular e receber lembrete, Android 14–17 e aparelhos de fabricante | Implementado/documentado; não executado |

Nenhum teste foi realizado no portal Loterias Online da CAIXA, nenhum servidor EC2 foi alterado, e nenhuma credencial de produção foi incluída. Chaves dos testes são temporárias e são removidas pelos scripts; não entram no APK principal.

## Reprodução

Use `JAVA_HOME` de um JDK ≥17 e Android SDK configurado. `scripts/test-ssh-local.sh` cria seu próprio OpenSSH temporário e executa a suíte JVM; requer porta SSH 22222 livre. `scripts/test-android-local.sh emulator-5554` requer emulador descartável iniciado, `adb` no PATH e portas 22222/22223 livres. O script Android oferece teste real e captura; os ativos SSH sobreviventes após perda abrupta de rede expiram pelos keepalives do servidor de fixture. Scripts não encerram processos desconhecidos.

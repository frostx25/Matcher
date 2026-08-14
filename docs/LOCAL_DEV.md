# Ambiente local do Matcher

## Perfil adotado nesta máquina

O fluxo principal usa um aparelho Android físico conectado por USB. Não é necessário criar ou manter emulador nesta máquina.

- Windows 11.
- Android Studio instalado em `C:\Program Files\Android\Android Studio`.
- JDK 17 fornecido pelo Android Studio.
- Android SDK em `C:\Users\leeoc\AppData\Local\Android\Sdk`.
- Gradle Wrapper do próprio projeto.
- Supabase remoto exclusivo de desenvolvimento; Docker fica opcional e é ligado apenas para migrations/pgTAP.

Com aproximadamente 24 GB de RAM, é possível validar o backend localmente com Docker sem precisar manter um emulador Android aberto.

## Configuração Android

O `local.properties` deve conter o caminho do SDK e apenas os dois valores públicos do projeto Supabase de desenvolvimento:

```properties
sdk.dir=C\:\\Users\\leeoc\\AppData\\Local\\Android\\Sdk
SUPABASE_URL=https\://<PROJECT_REF>.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_<PUBLIC_KEY>
```

Esse arquivo é ignorado pelo Git. Nunca adicionar senha do banco, chave secreta ou `service_role`; esses valores não pertencem ao APK.

O projeto remoto de desenvolvimento usa um código OTP numérico de seis dígitos, expiração de 3.600 segundos e intervalo mínimo local de 60 segundos antes de reenviar. O template de e-mail deve exibir `{{ .Token }}`; ao receber o sexto dígito, o Android valida o código automaticamente com o Supabase Auth, confirma ou cria a conta e não usa deep link. Se comprimento, expiração ou limites forem alterados no provedor, o contrato e as constantes correspondentes do Android devem ser atualizados juntos.

Envio, reenvio e validação são `single-flight`: toques rápidos não criam chamadas concorrentes. O cliente limita cada operação de autenticação a 15 segundos. Um timeout de solicitação significa entrega desconhecida, pois o e-mail ainda pode chegar; o app não reenvia automaticamente, abre a entrada do código sem afirmar que o envio foi confirmado e mantém o cooldown por e-mail normalizado. Respostas `429` e `504` recebem mensagens próprias e não são apresentadas como simples falta de conexão.

O onboarding coleta somente o ano de nascimento, a autodeclaração 18+ e o aceite dos Termos/Política. O Android envia `birth_year` à RPC `complete_onboarding`; data, mês e dia de nascimento não são solicitados nem armazenados. Quando o servidor aceita esses dados, conta e perfil ficam ativos e utilizáveis como não verificados.

A experiência hospedada do Didit não faz parte do onboarding obrigatório. Depois, a pessoa pode iniciar **Verificar 18+** na aba Perfil e o Android abre uma Custom Tab. Documento brasileiro, captura ao vivo para prova de vida passiva e correspondência facial são sempre exigidos para conceder o selo; o Matcher não solicita permissão `CAMERA` nem copia a mídia. Não iniciar, cancelar, falhar ou ficar em revisão mantém a conta ativa como não verificada e não bloqueia descoberta/chat.

Fotos de perfil seguem um fluxo separado do Didit. Nos fakes e no backend de desenvolvimento, cada nova versão começa privada em `pending`; terceiros recebem placeholder cinza até `approved`, e decisões `adult` ou `abusive` também mantêm a mídia privada. Uma versão nova não substitui a versão aprovada atual antes da própria aprovação. Fixtures configuram esses estados diretamente para testes e não representam nem prometem classificação automática.

O projeto deve manter **Custom SMTP** habilitado com o Resend e o template **Magic link or OTP** mostrando `{{ .Token }}`. A senha SMTP/API key existe apenas nos painéis dos provedores; nunca é salva no repositório, `local.properties`, APK, log ou documentação. Antes do teste físico, confirmar no Dashboard que o SMTP continua habilitado, o remetente é aceito pelo Resend e o template contém o token numérico.

## Build e testes locais

No PowerShell, a partir da raiz do repositório:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --no-daemon '-Pkotlin.incremental=false'
.\gradlew.bat :app:lintDebug :app:assembleDebug --no-daemon '-Pkotlin.incremental=false'
```

O APK é gerado em `app\build\outputs\apk\debug\app-debug.apk`.

## Aparelho físico

1. Ativar Opções do desenvolvedor e Depuração USB no aparelho.
2. Conectar por USB e aceitar a chave RSA exibida no Android.
3. Confirmar e instalar:

```powershell
$adb="C:\Users\leeoc\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb devices
& $adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Os testes Compose instrumentados usam explicitamente o backend fake para permanecerem determinísticos:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon '-Pkotlin.incremental=false'
```

Ao abrir normalmente o APK instalado, o app usa o backend Supabase remoto configurado. Para completar o login, informar o e-mail, consultar a caixa de entrada em qualquer aparelho e digitar no Matcher o código recebido.

## Backend remoto de desenvolvimento

- Projeto: `Matcher Dev`.
- Project ref: `gevdssaambgivxiqilad`.
- Região: São Paulo (`sa-east-1`).
- Migrations, seed e testes reproduzíveis ficam em `supabase/`.
- O seed contém somente identidades sintéticas com e-mails no domínio reservado `.invalid` e não deve ser aplicado em produção.

A CLI local está disponível em `work\tools\supabase-cli\supabase.exe`. Vincular a CLI é opcional e exige autenticação local; a senha do banco não deve ser salva no repositório.

Em uma máquina com Docker suficiente, o backend também pode ser validado localmente:

```powershell
supabase start
supabase db reset
supabase test db
supabase db lint --local
```

Nesta máquina, o projeto remoto isolado é o modo padrão. Nunca executar `db reset --linked` contra um projeto que contenha dados importantes.

## Selo 18+ hospedado e opcional

Antes do deploy, crie no Didit Business Console um workflow por ambiente com esta ordem e publique uma versão imutável:

1. **ID Verification:** aceitar somente documentos brasileiros suportados, validar autenticidade e aplicar idade mínima de 18 anos.
2. **Passive Liveness:** exigir captura ao vivo e confirmar `method = PASSIVE` na decisão.
3. **Face Match:** exigir correspondência 1:1 entre o retrato do documento e a captura ao vivo.

Configure a retenção do workflow em **um mês**, o mínimo adotado. Copie o identificador e a versão que aparecem depois da publicação; rascunho, versão diferente, controle ausente ou estado `In Review` não podem conceder o selo.

As credenciais do Didit pertencem exclusivamente às Edge Functions. Elas nunca devem entrar em `local.properties`, `BuildConfig`, APK, log ou commit. Crie localmente `supabase\functions\.env.local` a partir do exemplo versionado e preencha:

```dotenv
DIDIT_API_KEY=<segredo-do-mesmo-ambiente>
DIDIT_WORKFLOW_ID=<id-do-workflow-publicado>
DIDIT_WORKFLOW_VERSION=<versao-publicada>
DIDIT_WEBHOOK_SECRET=<segredo-do-webhook>
DIDIT_ENVIRONMENT=sandbox
```

`DIDIT_ENVIRONMENT` aceita somente `sandbox` ou `live`, exatamente como a API Didit devolve. Chave, workflow, versão e webhook precisam pertencer ao mesmo ambiente; sandbox nunca concede selo no ambiente live. O webhook Didit v3 deve apontar para `age-verification-webhook`, mas sua notificação serve apenas como gatilho para a consulta servidor-servidor da decisão.

Cada criação opcional usa o workflow KYC publicado; `session_kind` não é enviado e a resposta inicial v3 pode omiti-lo, mas a decisão autoritativa deve confirmá-lo como `user`. `vendor_data` é um pseudônimo opaco estável por usuário, sem ID de autenticação, identificador direto ou dado de perfil; a referência da tentativa é opaca, única por criação e armazenada separadamente. Se o Didit devolver `In Review`, o backend mantém revisão pendente na mesma sessão e não cria nem reabre outra até a decisão final, sem alterar o acesso da conta.

Depois de alinhar a implementação ao contrato opcional, as Edge Functions são publicadas com:

```powershell
$supabase="..\tools\supabase-cli\supabase.exe"
& $supabase secrets set --env-file .\supabase\functions\.env.local
& $supabase functions deploy age-verification-return
& $supabase functions deploy age-verification-webhook
& $supabase functions deploy age-verification-session
```

A migration histórica `20260731170000_age_assurance_gate.sql` representa o contrato antigo, no qual o Didit bloqueava o acesso. Ela continua na cadeia imutável e deve ser aplicada em ordem; `20260731190000_soft_age_gate_profile_photos.sql` a substitui imediatamente, recupera contas elegíveis e torna o Didit opcional. Nunca aplique apenas a migration antiga em um ambiente novo. O retorno `vibeali://age-verification/...` apenas reabre o Android e força uma consulta; ele nunca comprova o selo.

Antes de executar esses comandos, confirme que as Edge Functions consomem as cinco variáveis `DIDIT_*`, validam workflow/versão, `session_kind = user`, pseudônimo/referência de tentativa e liveness `PASSIVE`, bloqueiam somente outra sessão Didit durante `In Review` e não persistem nem registram a resposta bruta. Falha ou revisão não pode desativar a conta, e suspensão/moderação devem prevalecer sobre o selo. Qualquer divergência bloqueia o deploy até a implementação ser alinhada ao [contrato do selo opcional](age-assurance.md).

Na data deste guia, o Didit divulga 500 verificações gratuitas mensais para cada recurso central. Este fluxo consome ID Verification, Passive Liveness e Face Match; 500 fluxos completos é somente o máximo teórico sem repetições. Confira painel, documentação e termos vigentes antes do deploy e durante a operação, pois franquia e preço podem mudar. Não prometa gratuidade nem habilite cobrança excedente sem aprovação explícita.

## Perfis de execução

- `fast`: testes unitários e backend fake, sem aparelho necessário.
- `device`: Android Studio, aparelho físico e Supabase remoto de desenvolvimento; é o perfil principal.
- `full`: Docker, stack Supabase local e testes completos; reservado para CI ou máquina com mais memória.

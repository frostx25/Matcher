# Push e moderação automática de fotos do chat

Estado em 04/08/2026: o schema está aplicado no `Matcher Dev`, as Edge Functions
`notification-worker` e `chat-media-moderation` estão publicadas, o segredo interno
dos workers está configurado e chamadas públicas retornam `401`. As chamadas
autorizadas chegam ao código e retornam `503` de forma sanitizada enquanto as
credenciais dos provedores não estiverem cadastradas.

## Notificações privadas

- O Android registra o Firebase Installation ID (FID) somente depois de existir
  sessão ativa e remove o registro ao sair da conta.
- O banco guarda no máximo cinco instalações ativas por conta no schema `private`.
- Cada item da outbox gera uma entrega por instalação com lease, até dez tentativas
  e backoff exponencial limitado a seis horas.
- FID definitivamente inválido é desativado. Falha transitória ou de autenticação
  volta para a fila sem confirmar a outbox.
- O payload contém somente `Matcher`, `Nova mensagem` e o UUID opaco da conversa.
  Texto, remetente, foto, URL e caminho de Storage nunca entram na notificação.
- No Android 13 ou superior a permissão de notificação é solicitada uma única vez,
  depois da ativação da conta. Negar a permissão não impede o uso do aplicativo.

## Triagem automática de fotos do chat

- A foto permanece `pending` e privada enquanto não houver decisão.
- O worker baixa no máximo 5 MB do bucket privado `chat-media`, chama somente o
  recurso SafeSearch do Google Vision e não persiste resposta bruta, score, base64
  ou bytes da imagem.
- `LIKELY` ou `VERY_LIKELY` para violência resulta em `abusive`.
- `LIKELY` ou `VERY_LIKELY` para adulto ou conteúdo sugestivo resulta em `adult`.
- A aprovação automática exige `UNLIKELY` ou `VERY_UNLIKELY` nos três controles.
- `POSSIBLE`, `UNKNOWN`, campo ausente ou resposta inesperada mantém a foto privada
  em `review` para decisão humana.
- Erros de rede/provedor usam lease e backoff. Dez falhas encerram a automação em
  revisão, nunca em aprovação.

Essa classificação é uma triagem de conteúdo, não prova idade, consentimento,
identidade nem legalidade. Denúncia e revisão humana continuam necessárias.

## Configuração que ainda falta

Criar um projeto Firebase/Google Cloud separado para o ambiente de desenvolvimento:

1. Registrar o app Android `com.matcher.app` no Firebase e habilitar Firebase Cloud
   Messaging HTTP v1.
2. Preencher apenas os quatro valores públicos em `local.properties`:
   `FIREBASE_API_KEY`, `FIREBASE_APPLICATION_ID`, `FIREBASE_PROJECT_ID` e
   `FIREBASE_SENDER_ID`.
3. Criar uma service account restrita ao envio FCM e cadastrar o JSON completo no
   secret hospedado `FIREBASE_SERVICE_ACCOUNT_JSON`.
4. Habilitar Cloud Vision SafeSearch, criar uma chave de API restrita à API e ao
   projeto e cadastrá-la como `GOOGLE_CLOUD_VISION_API_KEY`.
5. Republicar as duas funções e instalar um novo APK para que o aparelho registre
   seu FID.
6. Somente depois dos testes manuais, configurar um agendamento de um minuto para
   cada worker. O mesmo `WORKER_SHARED_SECRET` precisa existir nas Edge Functions e
   no cofre usado pelo agendador; nunca colocar seu valor no SQL versionado.

Os valores públicos do Firebase podem estar no APK. Service account, chave do
Vision e segredo do worker são exclusivamente de servidor e nunca devem entrar em
`local.properties`, `.env` versionado, logs, issues ou GitHub Actions sem secret.

## Implantação e verificação

Com Supabase CLI autenticado e vinculado ao projeto correto:

```powershell
supabase functions deploy notification-worker --no-verify-jwt
supabase functions deploy chat-media-moderation --no-verify-jwt
```

Antes de ativar o agendamento:

- chamada sem `Authorization` deve retornar `401`;
- chamada com segredo e provedor ausente deve retornar `503` com contagens zeradas;
- push real deve exibir somente `Matcher` e `Nova mensagem`;
- foto claramente segura deve sair de `pending` para `approved`;
- foto adulta, violenta e inconclusiva deve seguir respectivamente para `adult`,
  `abusive` e `review`;
- nenhum log pode conter FID, segredo, texto, URL, caminho ou bytes.

Arquivos principais:

- `supabase/migrations/20260804170000_push_delivery_and_chat_media_automation.sql`
- `supabase/tests/database/push_delivery_and_chat_media_automation.test.sql`
- `supabase/functions/notification-worker/index.ts`
- `supabase/functions/chat-media-moderation/index.ts`
- `app/src/main/java/com/matcher/app/data/push/FirebasePushGateway.kt`

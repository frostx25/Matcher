# Push e moderação da foto de perfil

Estado em 04/08/2026: `notification-worker` e `profile-photo-moderation` estão
publicados no `Matcher Dev`, com JWT legado desligado, autenticação pelo secret
`WORKER_SHARED_SECRET` e execução a cada minuto por `pg_cron`/`pg_net`. O mesmo
bearer fica criptografado no Vault e nunca entra no SQL versionado.
O worker de foto usa `OPENAI_API_KEY` exclusivamente no servidor e chama somente
o endpoint gratuito `/v1/moderations` com `omni-moderation-latest`.

## Notificações privadas

- O Android registra o Firebase Installation ID somente depois de existir sessão
  ativa e remove o registro ao sair da conta.
- Cada entrega usa lease, até dez tentativas e backoff. Instalação recusada de forma
  permanente é desativada.
- O payload contém apenas `Matcher`, `Nova mensagem` e o UUID opaco da conversa.
  Texto, remetente, foto, URL e caminho de Storage nunca entram na notificação.
- O canal Android `vibeali_messages` usa alta prioridade, som padrão e ícone pequeno
  monocromático. O pop-up detalhado foi validado no Samsung conectado.

## Limite da moderação automática

- Existe somente um espaço de foto pública por perfil. Uma nova candidata fica
  privada e não substitui a imagem aprovada anterior antes de ser aprovada.
- Somente candidatas do bucket privado `profile-photos` podem ser reivindicadas pelo
  worker e enviadas à OpenAI Moderation API, em memória e sem URL pública.
- `sexual` classifica como adulta. `sexual/minors`, `violence` ou
  `violence/graphic` classificam como abusiva. Outra categoria sinalizada segue
  privada para revisão humana; resposta inválida ou indisponível entra em retry.
- Uma resposta válida sem categoria sinalizada aprova a candidata. A classificação
  não comprova idade e nunca concede o selo 18+.
- Fotos do chat e imagens do álbum privado não são enviadas ao provedor e não dependem
  de aprovação automática. Permanecem privadas aos participantes autorizados e
  sujeitas a denúncia, bloqueio, suspensão e remoção posterior.
- Resposta bruta, scores, base64, bytes, FID e segredo não são persistidos em logs.

## Implantação

```powershell
supabase functions deploy notification-worker --no-verify-jwt
supabase functions deploy profile-photo-moderation --no-verify-jwt
supabase db push
supabase test db --linked
```

Arquivos principais:

- `supabase/migrations/20260804180000_profile_photo_only_automation.sql`
- `supabase/migrations/20260804190000_schedule_private_workers.sql`
- `supabase/migrations/20260804210000_openai_profile_photo_moderation.sql`
- `supabase/tests/database/push_delivery_and_chat_media_automation.test.sql`
- `supabase/functions/notification-worker/index.ts`
- `supabase/functions/profile-photo-moderation/index.ts`
- `supabase/functions/profile-photo-moderation/profilePhotoModeration.ts`
- `app/src/main/java/com/matcher/app/data/push/FirebasePushGateway.kt`

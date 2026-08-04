# Push e moderação da foto de perfil

Estado em 04/08/2026: `notification-worker` e `profile-photo-moderation` estão
publicados no `Matcher Dev`, com JWT legado desligado, autenticação pelo secret
`WORKER_SHARED_SECRET` e execução a cada minuto por `pg_cron`/`pg_net`. O mesmo
bearer fica criptografado no Vault e nunca entra no SQL versionado.

## Notificações privadas

- O Android registra o Firebase Installation ID somente depois de existir sessão
  ativa e remove o registro ao sair da conta.
- Cada entrega usa lease, até dez tentativas e backoff. Instalação recusada de forma
  permanente é desativada.
- O payload contém apenas `Matcher`, `Nova mensagem` e o UUID opaco da conversa.
  Texto, remetente, foto, URL e caminho de Storage nunca entram na notificação.
- O canal Android `matcher_messages` usa alta prioridade, som padrão e ícone pequeno
  monocromático. O pop-up detalhado foi validado no Samsung conectado.

## Limite da moderação automática

- Existe somente um espaço de foto pública por perfil. Uma nova candidata fica
  privada e não substitui a imagem aprovada anterior antes de ser aprovada.
- Somente candidatas do bucket privado `profile-photos` podem ser reivindicadas pelo
  worker e enviadas ao Google Vision SafeSearch, sem URL pública.
- `LIKELY`/`VERY_LIKELY` para violência bloqueia como abusiva; esses níveis para
  adulto ou sugestivo bloqueiam como adulta. A aprovação automática exige
  `UNLIKELY`/`VERY_UNLIKELY` nos três controles.
- Resultado `POSSIBLE`, `UNKNOWN`, incompleto ou indisponível mantém a candidata
  privada para revisão ou retry cauteloso.
- Fotos do chat e imagens do álbum privado não são enviadas ao Vision e não dependem
  de aprovação automática. Permanecem privadas aos participantes autorizados e
  sujeitas a denúncia, bloqueio, suspensão e remoção posterior.
- Resposta bruta, score, base64, bytes, FID e segredo não são persistidos em logs.

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
- `supabase/tests/database/push_delivery_and_chat_media_automation.test.sql`
- `supabase/functions/notification-worker/index.ts`
- `supabase/functions/profile-photo-moderation/index.ts`
- `app/src/main/java/com/matcher/app/data/push/FirebasePushGateway.kt`

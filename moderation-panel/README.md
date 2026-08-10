# Matcher — central de segurança

Painel web interno para revisão humana da foto pública de perfil, denúncias, evidências privadas vinculadas, sanções de conta, auditoria e gestão da equipe. Ele usa autenticação OTP do Supabase, RPCs protegidas por RLS e a Edge Function `moderation-profile-photos` para decisões e prévias privadas.

## Desenvolvimento local

1. Copie `.env.example` para `.env.local`.
2. Preencha `VITE_SUPABASE_URL` e `VITE_SUPABASE_ANON_KEY` com valores publicáveis. Nunca use `service_role` no painel.
3. Configure `MODERATION_ALLOWED_ORIGINS` na Edge Function com a origem exata do painel, por exemplo `http://localhost:5173` durante desenvolvimento.
4. Execute `pnpm install` e `pnpm dev`.

Somente contas ativas presentes em `private.moderation_staff` conseguem acessar a central. Revisores decidem conteúdo e casos; administradores também podem sancionar contas e administrar a equipe. As prévias privadas são emitidas pelo backend por 60 segundos e não são persistidas pelo painel.

## Validação

```sh
pnpm test
pnpm run build
```

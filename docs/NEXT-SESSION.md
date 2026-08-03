# Próxima sessão

Checkpoint de 03/08/2026. O código inclui autenticação por OTP, onboarding por ano, verificação documental opcional, foto pública moderada, conversa direta, identidade/preferência de gênero e álbum privado com concessão individual.

## Publicado no Matcher Dev

- As migrations `20260731210000_private_albums_gender_preferences.sql`, `20260803100000_private_album_security_hardening.sql` e `20260803110000_enable_pgtap_validation.sql` estão aplicadas no projeto remoto de desenvolvimento.
- As Edge Functions `private-album-media`, `private-album-delete` e `private-album-cleanup` estão publicadas e ativas com autenticação JWT.
- As correções de concorrência, retenção, cleanup, paginação, isolamento entre sessões e validação de imagens estão implementadas.

## Validações deste checkpoint

- Banco local e remoto: 314 testes pgTAP aprovados em 7 arquivos.
- Schemas `public` e `private`: lint local e remoto sem erros.
- Edge Functions: 64 testes Deno aprovados; format, lint e type-check aprovados.
- Android: 56 testes unitários, lint, compilação dos testes instrumentados e APK debug aprovados.
- Harness: 73 cenários YAML válidos e com identificadores únicos.

## Próximos passos

- Conectar o Samsung com depuração USB autorizada, executar `connectedDebugAndroidTest` e instalar o APK final.
- Validar OTP, onboarding, filtro, compartilhamento, revogação, bloqueio e denúncia com duas contas sintéticas.
- Depois da validação funcional, planejar a promoção controlada das migrations e Edge Functions para produção; este checkpoint cobre apenas o projeto `Matcher Dev`.

# Próxima sessão

Checkpoint de 31/07/2026. O código local inclui autenticação por OTP, onboarding por ano, verificação documental opcional, foto pública moderada, conversa direta, identidade/preferência de gênero e álbum privado com concessão individual.

## Antes do deploy do álbum e do filtro de gênero

1. Serializar `block × grant` com lock canônico do par e impedir que um acesso denunciado seja reativado pelo titular.
2. Serializar `upload × delete` pelo caminho reservado para impedir objeto órfão em corrida.
3. Remover a leitura ampla de `public.profiles` e servir o próprio perfil por RPC; descoberta deve continuar exclusivamente pela RPC filtrada e paginada.
4. Cancelar e invalidar carregamentos de álbum quando houver voltar, logout ou troca de sessão; uma resposta antiga nunca pode recolocar bytes na interface.
5. Ao receber `DISCOVERY_CURSOR_STALE`, substituir a lista pela nova primeira página em vez de mesclar resultados do filtro anterior.
6. Validar dimensões/pixels reais da mídia privada no servidor e fazer decode amostrado no Android para evitar imagem-bomba/OOM.
7. Manter temporariamente o overload legado de onboarding com defaults seguros para APKs já instalados.
8. Adicionar backoff/lease à fila de cleanup e definir retenção restrita da evidência de denúncias antes da remoção física.

## Publicação pendente

- Não aplicar ainda a migration `20260731210000_private_albums_gender_preferences.sql` no Supabase remoto.
- Depois das correções, publicar `private-album-media`, `private-album-delete` e `private-album-cleanup`, aplicar a migration e repetir os testes contra o projeto de desenvolvimento.
- Rodar os testes instrumentados no Samsung conectado, instalar o APK final e validar OTP, onboarding, filtro, compartilhamento, revogação, bloqueio e denúncia com duas contas sintéticas.
- Revogar todo token temporário usado no deploy e confirmar que nenhum segredo foi versionado.

## Validações deste checkpoint

- Banco local recriado do zero: 225 testes pgTAP aprovados.
- Edge Functions: 47 testes Deno aprovados; format, lint e type-check aprovados.
- Android: testes unitários e compilações Kotlin aprovados antes deste checkpoint.

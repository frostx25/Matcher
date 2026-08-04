# Matcher — continuidade em um novo chat

Atualizado em: 2026-08-04

## Como retomar

Projeto local:

`C:\Users\leeoc\Documents\Codex\2026-07-31\12345678910101\work\Matcher`

Repositório GitHub:

`https://github.com/frostx25/Matcher`

Texto sugerido para o novo chat:

> Abra o projeto em `C:\Users\leeoc\Documents\Codex\2026-07-31\12345678910101\work\Matcher`. Leia `CONTINUE-HERE.md` e `docs/NEXT-SESSION.md` antes de agir. Preserve todas as mudanças locais; não use reset, checkout ou qualquer comando que descarte arquivos. Continue da árvore de trabalho atual.

## Estado do Git

- Branch: `main`
- Commit atual: `ab22a08`
- Há mudanças locais ainda **não commitadas e não enviadas ao GitHub**.
- Não apagar esta pasta antes de fazer commit e push, pois excluir apenas o chat não salva essas alterações remotamente.

Arquivos modificados:

- `app/src/androidTest/java/com/matcher/app/PrivateAlbumScreensTest.kt`
- `app/src/main/java/com/matcher/app/data/remote/SupabasePrivateAlbumGateway.kt`
- `app/src/main/java/com/matcher/app/ui/PrivateAlbumScreens.kt`
- `app/src/main/java/com/matcher/app/ui/MatcherApp.kt`
- `app/src/main/java/com/matcher/app/ui/RemoteMatcherApp.kt`
- `app/src/main/java/com/matcher/app/ui/RemoteMatcherViewModel.kt`
- `app/src/test/java/com/matcher/app/data/remote/PrivateAlbumUploadCleanupTest.kt`
- `app/src/test/java/com/matcher/app/ui/RemoteMatcherViewModelTest.kt`
- `docs/NEXT-SESSION.md`
- `docs/SPEC-MVP.md`
- `harness/README.md`
- `harness/scenarios/discovery.yml`
- `harness/scenarios/private-album.yml`
- `supabase/README.md`

Arquivo novo:

- `docs/UX-REFERENCE-ALBUMS.md`
- `app/src/androidTest/java/com/matcher/app/RemoteDiscoveryScreenTest.kt`
- `app/src/test/java/com/matcher/app/ui/RemoteDiscoveryLayoutTest.kt`

## O que foi implementado

### Álbuns privados

- Tela do álbum em grade de três colunas.
- Primeiro bloco da grade para adicionar foto, respeitando o limite de 10.
- Resumo com quantidade de fotos e pessoas com acesso.
- Menu com `Gerenciar compartilhamento` e `Excluir álbum`.
- Tela própria de compartilhamento.
- Seleção múltipla de acessos ativos e ação fixa `Parar de compartilhar (n)`.
- Ação explícita `Liberar` para pessoas sem acesso.
- Revogação múltipla no ViewModel com recarga do estado oficial mesmo se uma revogação intermediária falhar.
- Perfil atualizado para mostrar `Meus álbuns`, contagens e acesso ao álbum.
- O backend ainda permite somente um álbum privado por conta. A interface não simula suporte a vários álbuns.

### Referência funcional do Grindr

O aplicativo Grindr instalado no Samsung foi inspecionado somente para referência de navegação e regras de interface. Não foram abertas conversas, liberados álbuns nem enviados dados. As conclusões estão em `docs/UX-REFERENCE-ALBUMS.md`.

Mantivemos a identidade visual própria do Matcher em rosa, ameixa e preto. Não copiar marca, textos ou ativos visuais do Grindr.

### Tela inicial de descoberta

- Cabeçalho compacto `Perto` com avatar da conta, contexto de localização aproximada, quota e filtro.
- O avatar do topo abre diretamente a aba Perfil.
- Preferência de descoberta aparece apenas como resumo privado do próprio usuário.
- Perfis aparecem em uma grade de três colunas, com nome, idade, faixa de distância e intenção sobre a miniatura pública autorizada.
- Em telas largas, a grade aumenta progressivamente para 4, 5 ou 6 colunas; o retrato do telefone permanece com três.
- O componente é compartilhado pelo backend remoto e pelo modo de demonstração.
- Novo teste Compose confirma as três colunas e as ações do cabeçalho.

### Backend e upload

- Corrigida a idempotência da reserva/finalização de upload em `SupabasePrivateAlbumGateway.kt`.
- Adicionados testes de limpeza e repetição segura do upload.
- Migração `20260803130000_private_album_upload_reservation_leases.sql` aplicada somente no ambiente de desenvolvimento.
- Função Edge `private-album-media` mais recente implantada no ambiente de desenvolvimento.
- Smoke test real no Samsung: foto sintética enviada, exibida e depois removida; o álbum voltou de 5 para 4 itens.

## Supabase

- Ambiente atual: `Matcher Dev`
- Project ref: `gevdssaambgivxiqilad`
- Região: `sa-east-1`
- Produção não foi alterada.
- Não recriar migrações nem implantar funções sem primeiro conferir o estado remoto atual.
- Não colocar chaves, tokens, links mágicos ou credenciais em arquivos versionados.

## Validação concluída

Comando executado com sucesso:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --console=plain
```

Resultados:

- Build concluído com sucesso.
- 89 testes unitários aprovados, sem falhas ou testes ignorados.
- 23 testes instrumentados compilados; os 5 testes de smoke do protótipo e o novo teste da descoberta foram executados no Samsung sem falha.
- Lint: 0 erros e 7 avisos relacionados apenas a versões/API alvo.
- Cenários YAML do harness validados.
- `git diff --check` aprovado, com apenas avisos de conversão CRLF.
- APK instalado e aberto no Samsung `SM-A315G`, Android 12/API 31, serial ADB `RQ8R1075VFJ`.
- Não foi observada `FATAL EXCEPTION`.

APK atual:

`app\build\outputs\apk\debug\app-debug.apk`

SHA-256:

`73020AA474E22A08ED4F30F29FBBB308AFA06ADA91A695F56B1FDE7AE7B32DD4`

## Próximos passos recomendados

1. Revisar as mudanças locais, criar um commit e enviar para o GitHub antes de apagar a pasta local.
2. Decidir se o Matcher terá vários álbuns nomeados. Isso exige migração, alteração das APIs, políticas e testes; hoje existe no máximo um álbum por conta.
3. Adicionar, se desejado, atalho de liberação do álbum dentro da conversa, reordenação/capa do álbum e busca de destinatários.
4. Capturar um teste autenticado da função Edge com resposta 200 e conferir os cabeçalhos privados de cache.
5. Preparar um projeto Supabase separado para produção, com segredos, builds, dados, backups, alertas, limites e processos de LGPD separados do desenvolvimento.

## Documentos importantes

- `docs/NEXT-SESSION.md` — histórico técnico detalhado e estado recente.
- `docs/UX-REFERENCE-ALBUMS.md` — referência funcional dos álbuns.
- `docs/SPEC-MVP.md` — regras de negócio e critérios de aceite.
- `supabase/README.md` — operação do backend e implantações.

Este arquivo não contém segredos nem dados pessoais.

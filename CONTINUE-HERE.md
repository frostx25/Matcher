# Matcher — continuidade em um novo chat

Atualizado em: 2026-08-10

## Como retomar

Projeto local:

`C:\Users\leeoc\Documents\Codex\2026-07-31\12345678910101\work\Matcher`

Repositório GitHub:

`https://github.com/frostx25/Matcher`

Texto sugerido para o novo chat:

> Abra o projeto em `C:\Users\leeoc\Documents\Codex\2026-07-31\12345678910101\work\Matcher`. Leia `CONTINUE-HERE.md` e `docs/NEXT-SESSION.md` antes de agir. Preserve todas as mudanças locais; não use reset, checkout ou qualquer comando que descarte arquivos. Continue da árvore de trabalho atual.

## Estado do Git

- Branch: `main`
- Checkpoint de descoberta/álbuns já enviado: `d9b8f2b` (`feat: refine discovery and private albums`).
- Checkpoint do perfil público já enviado: `245a3ae` (`feat: redesign public profile actions`).
- A etapa seguinte redesenha a conversa ativa e deve aparecer no commit mais recente (`git log -1 --oneline`).
- A árvore de trabalho deve estar limpa ao concluir este checkpoint.

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

### Perfil público

- Hero alto com mídia pública autorizada, nome, idade, distância aproximada e intenção.
- Ações persistentes na base para `Álbum` e `Conversar`, sem exigir aceite prévio para iniciar a conversa.
- O menu de álbum separa claramente `Abrir álbum recebido` de `Liberar/Revogar meu álbum`.
- Bloqueio e denúncia permanecem no menu de segurança no topo.
- Cartões explicam o estado do álbum privado e reforçam que a distância exibida é aproximada.
- O perfil foi validado no Samsung com a conta remota, sem mudar acessos, enviar mensagem, bloquear ou denunciar.

### Conversa ativa

- Cabeçalho compacto com faixa de identidade, avatar/foto pública autorizada, distância aproximada, álbum e menu de segurança.
- Tocar na identidade abre o perfil e voltar retorna à conversa.
- O menu de álbum separa `Abrir álbum recebido` de `Liberar/Revogar meu álbum`; sem ação disponível, fica desabilitado e nenhuma miniatura privada aparece no chat.
- Bloqueio e denúncia ficam agrupados no menu superior, sempre disponíveis e independentes de assinatura.
- O compositor permanece fixo com o teclado, rejeita mensagem vazia, preserva o rascunho após falha e limpa somente depois da confirmação do repositório.
- O modo remoto atualiza os acessos de álbum ao entrar no chat e continua usando as operações autoritativas existentes.
- O compositor agora separa `Selecionar foto` de `Liberar/Revogar meu álbum`, como duas intenções diferentes; abrir álbum recebido permanece contextual e nenhuma miniatura privada do álbum aparece na conversa.
- Fotos de conversa usam upload privado de até 5 MB, chave idempotente, moderação pendente e retry manual sem duplicação. Somente uma foto `approved` pode ser aberta pelos participantes ativos.
- Mensagens exibem `enviando`, `enviada`, `entregue`, `lida` ou `falhou`; a lista mostra não lidas e a conversa permite silenciar notificações.
- O menu de segurança aceita denúncia do perfil ou de uma mensagem/foto específica, além do bloqueio já existente.

### Lista e primeira mensagem

- A lista usa cartões compactos com foto pública autorizada, última mensagem, faixa aproximada e estado de conversa direta.
- O estado vazio explica como iniciar contato e oferece `Descobrir pessoas`, que retorna diretamente para `Perto`.
- A primeira mensagem usa um diálogo responsivo com identidade do destinatário, quota restante e consequência explícita do envio sem match ou aceite.
- O botão `Enviar primeira mensagem` ocupa toda a largura, fica desabilitado sem texto e permanece visível com o teclado aberto.

### Backend e upload

- Corrigida a idempotência da reserva/finalização de upload em `SupabasePrivateAlbumGateway.kt`.
- Adicionados testes de limpeza e repetição segura do upload.
- Migração `20260803130000_private_album_upload_reservation_leases.sql` aplicada somente no ambiente de desenvolvimento.
- Função Edge `private-album-media` mais recente implantada no ambiente de desenvolvimento.
- Smoke test real no Samsung: foto sintética enviada, exibida e depois removida; o álbum voltou de 5 para 4 itens.
- Migração `20260804150000_chat_media_delivery_safety.sql` aplicada no `Matcher Dev`, com 25/25 asserções hospedadas.
- Migração `20260804160000_account_deletion_request.sql` aplicada no `Matcher Dev`, com 11/11 asserções hospedadas.
- A exclusão de conta está disponível no Perfil, torna a conta indisponível imediatamente e agenda a limpeza física em fila privada.
- A outbox de push usa somente `Matcher`/`Nova mensagem`; o worker FCM real ainda depende de criar e configurar o projeto Firebase e suas credenciais fora do APK.
- A migration `20260804170000_push_delivery_and_chat_media_automation.sql` foi aplicada e registrada no `Matcher Dev`; depois, `20260804180000_profile_photo_only_automation.sql` restringiu a triagem automática à única foto pública de perfil.
- `notification-worker` e `profile-photo-moderation` estão publicados com autenticação própria; o worker antigo do chat foi removido e fotos de chat/álbum não são enviadas ao provedor automático.
- A migration `20260804190000_schedule_private_workers.sql` está aplicada: os dois workers rodam a cada minuto, usando bearer criptografado no Vault, e responderam HTTP 200 no primeiro ciclo.
- A migration `20260804200000_profile_photo_storage_upload_protocol.sql` está aplicada e registrada no `Matcher Dev`. Ela corrige a pré-checagem do upload da foto de perfil para aceitar `contentLength`; o pgTAP hospedado passou com 40/40 e um avatar sintético entrou como foto privada em análise no Samsung.
- A migration `20260804210000_openai_profile_photo_moderation.sql` está aplicada e registrada. A moderação foi migrada para o endpoint gratuito `omni-moderation-latest`, o worker foi republicado e a candidata real terminou `approved/completed`, sem erro e com avatar público. A suíte hospedada atualizada concluiu 38/38.
- O secret `OPENAI_API_KEY` foi rotacionado e salvo diretamente no Supabase; não existe cópia no repositório nem no APK. A chave publicada anteriormente no chat não foi usada.
- O secret `WORKER_SHARED_SECRET` foi gerado e salvo somente no Supabase. Não existe cópia no repositório.
- O Android usa a API atual de Firebase Installation ID, registra somente contas ativas, remove o registro no logout e exibe notificação neutra com canal de alta prioridade e ícone próprio. O push real foi validado no Samsung.

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
- 93 testes unitários aprovados, sem falhas ou testes ignorados.
- 32 testes instrumentados compilados. Os 4 testes da conversa ativa passaram no Samsung, incluindo a separação entre `Selecionar foto`, álbum e silenciamento.
- Lint: 0 erros e 11 avisos relacionados apenas a versões, API alvo e sugestões de KTX.
- Cenários YAML do harness validados.
- `git diff --check` aprovado, com apenas avisos de conversão CRLF.
- APK instalado e aberto no Samsung `SM-A315G`, Android 12/API 31, serial ADB `RQ8R1075VFJ`.
- Não foi observada `FATAL EXCEPTION`.

APK atual:

`app\build\outputs\apk\debug\app-debug.apk`

SHA-256:

`0A153071F8A8639318FA2562F9B332CACE88B3CB1661D1D133E635D5D550DB33`

A pessoa entrou novamente por e-mail/OTP. O APK foi reinstalado com `-r`, preservou a sessão e a lista vazia, o retorno para `Perto` e o diálogo da primeira mensagem foram validados com o backend real. Um texto sintético foi digitado somente para conferir o teclado e cancelado sem envio.

## Próximos passos recomendados

1. Criar o ambiente `Matcher Prod` separado do desenvolvimento, com Supabase, segredos, builds, backups, alertas, limites e processos de LGPD próprios.
2. Definir a função da VM: workers e rotinas operacionais, monitoramento, backups e serviços contínuos; manter banco, Auth, Storage e Realtime no Supabase nesta fase.
3. Automatizar validação e implantação de migrations, Edge Functions, Android e painel sem colocar segredos no repositório.
4. Ampliar os testes do painel para autenticação, filas, evidências, decisões, sanções, auditoria e expiração de sessão.
5. Implementar observabilidade de retries, 429, falhas de push/moderação e crescimento das filas sem registrar conteúdo privado.
6. Preparar Termos, Política de Privacidade, Política de Conteúdo, processo de apelação e operação de incidentes antes de abrir o aplicativo ao público.
7. Decidir futuramente se o Matcher terá vários álbuns nomeados; hoje existe no máximo um álbum por conta.

## Checkpoint de moderação e denúncias — 10/08/2026

- Central de segurança publicada em `https://matcher-moderation-panel.vercel.app/`, com OTP, visão geral, revisão de fotos, denúncias, contas, histórico e equipe.
- Fila humana implementada para candidatas de foto pública encaminhadas a `review`; prévias privadas expiram em 60 segundos.
- Denúncia de álbum pode apontar o álbum inteiro ou somente uma foto, preservando apenas a evidência vinculada.
- O Android envia explicitamente o bearer atual nas Edge Functions de leitura e remoção de álbum privado.
- O estado da foto pública distingue análise automática, revisão humana, aprovação e bloqueios, preservando uma foto anterior aprovada.
- Smoke real concluído: evidência privada carregada, álbum sintético removido, caso resolvido, fila zerada e ações registradas na auditoria.
- Validação local: 96 testes unitários, lint, APK e compilação de testes instrumentados aprovados; 33/33 testes instrumentados passaram no Samsung SM-A315G sem `FATAL EXCEPTION`.
- Painel: 2/2 testes e build de produção aprovados.
- Os artefatos temporários de ADB e as imagens sintéticas usadas no smoke manual foram removidos antes do commit.

## Documentos importantes

- `docs/NEXT-SESSION.md` — histórico técnico detalhado e estado recente.
- `docs/UX-REFERENCE-ALBUMS.md` — referência funcional dos álbuns.
- `docs/SPEC-MVP.md` — regras de negócio e critérios de aceite.
- `supabase/README.md` — operação do backend e implantações.
- `docs/PUSH-AND-CHAT-MODERATION.md` — arquitetura, segurança e ativação dos dois workers.

Este arquivo não contém segredos nem dados pessoais.

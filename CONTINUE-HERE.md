# Matcher — continuidade em um novo chat

Atualizado em: 2026-08-11

## Checkpoint de 13/08/2026 — monetização, performance e operação

- Catálogo local Free/Extra/Pro/Ilimitado, tela de planos e aba fixa no extremo direito da navegação; compras permanecem “Em breve” até existir Play Console e validação backend.
- Carregamento autenticado paraleliza identidade, descoberta, favoritos, privacidade e chat; retorno em menos de 15 segundos evita recarga completa duplicada.
- Samsung: debug mediu 3,5–5,5 s; variante benchmark minificada abriu a Activity em aproximadamente 495 ms.
- VM continua sem PostgreSQL. Caddy ganhou healthcheck e timer systemd de cinco minutos; site e painel respondem 200.
- Rotina de backup lógico criptografado foi instalada sem credenciais e permanece desativada até existir configuração segura. Ela não cobre bytes do Storage.
- Workflows de CI verificam Android, painel e Supabase; deploy da VM é manual e depende de secrets protegidos no GitHub.

## Checkpoint de 11/08/2026 — fechamento das prioridades 1 a 6

- Denúncia superior da conversa agora é identificada corretamente como denúncia da conversa; denúncias específicas de mensagem/foto continuam vinculadas ao conteúdo autorizado.
- Álbuns privados e moderação foram revalidados junto das regras de concessão, revogação, bloqueio, denúncia, retenção e limpeza.
- Conversas agora podem ser excluídas somente para o próprio usuário. O histórico permanece para o outro participante e uma nova mensagem restaura a conversa.
- O compositor pode compartilhar, após confirmação, somente a região aproximada já publicada no perfil; coordenadas e localização em segundo plano não são usadas.
- Descoberta foi auditada com paginação, filtros, presença aproximada, favoritos, ocultações e bloqueios autoritativos no servidor.
- Perfis agora aceitam até oito interesses de um catálogo controlado e os entregam apenas a solicitantes autenticados já autorizados a descobrir o perfil.
- Migrations `20260811150000_private_conversation_deletion.sql` e `20260811160000_profile_interests.sql` aplicadas no Matcher Dev; chaves existentes foram preservadas.
- Validação: 18 suítes pgTAP com 539 asserções; build, testes unitários, lint e compilação instrumentalizada aprovados; 10 testes Compose da conversa passaram no AVD API 35.
- O APK final foi instalado no emulador. O runner instrumentalizado removeu os dados do app, portanto o emulador ficou novamente na tela de login.

## Checkpoint de 11/08/2026 — privacidade e experiência madura

- Central de privacidade no perfil: atividade, perfis ocultos/bloqueados, restaurar e desbloquear.
- Conversas podem ser arquivadas e separadas entre ativas e arquivadas.
- Indicador privado e temporário de digitação, respostas, reações e abertura de conversa por notificação.
- Edição de nome/bio/intenção e exportação dos dados da conta para JSON escolhido pelo usuário.
- Busca avançada por nome/intenção, idade, verificação e foto, sempre respeitando preferências, ocultações e bloqueios no servidor.
- Sanção ativa pode ser consultada e contestada; o backend limita mensagens repetidas e volume abusivo.
- Migrations aplicadas no Matcher Dev até `20260811140000_discovery_advanced_search.sql`.
- Build, testes unitários e lint aprovados. Onze testes Compose passaram no Samsung SM-A315G e o APK abriu preservando a sessão.
- O pgTAP de privacidade está escrito, mas a execução pela CLI ficou pendente porque o runner tentou usar o Docker Desktop, que estava desligado.

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

## Checkpoint de produção, planos e legal — 14/08/2026

- Catálogo Free/Extra/Pro/Ilimitado aplicado no `Matcher Dev`; `get_my_subscription_plan()` fornece snapshot somente leitura e a cota de favoritos é validada no servidor.
- Sem Google Play configurado, compra permanece desativada e nenhum cliente consegue conceder entitlement.
- Android expõe no Perfil links oficiais para Privacidade, Termos e Regras da Comunidade; APK, testes unitários e lint passaram, e 44/44 testes instrumentados foram aprovados no Samsung.
- Documentos públicos foram atualizados com o contato `leeocali@hotmail.com` e publicados em `vibeali.shop`.
- Monitoramento systemd da VM está ativo a cada cinco minutos para site e painel. Backup lógico permanece deliberadamente sem credenciais e não deve ser ativado até existir um projeto `VibeAli Prod` separado e destino externo criptografado.
- Samsung SM-A315G: cold start debug autenticado de 5.619 ms; após estabilização, 7,25% de frames lentos. Build minificado continua sendo a referência para publicação.

## Checkpoint de identidade VibeAli — 11/08/2026

- Nome público oficial alterado de Matcher para `VibeAli`; domínio oficial: `vibeali.shop`.
- Nome, ícone Android, ícone de notificação, textos do aplicativo, retorno de verificação etária, notificações push e painel de moderação foram atualizados.
- Guia e fonte visual: `docs/brand/BRAND.md` e `docs/brand/vibeali-logo-source.png`.
- Identificadores técnicos (`com.matcher.app`, `matcher://` e canal `matcher_messages`) foram mantidos para preservar instalações, Firebase e integrações existentes.
- Migration `20260811170000_vibeali_notification_brand.sql` aplicada no `Matcher Dev` e funções `notification-worker` e `age-verification-return` republicadas.
- Painel publicado em produção e associado a `https://matcher-moderation-panel.vercel.app/`.
- APK compilado, reinstalado e aberto no emulador `emulator-5554`; a tela de login mostra `VibeAli`.
- Android: build, testes unitários, lint e compilação dos testes instrumentados aprovados. Painel: 2/2 testes e build aprovados.
- Banco: 539/539 asserções passaram após reset limpo. A asserção de consolidação do outbox foi isolada à conversa criada pelo teste, sem ser contaminada por mensagens legítimas do `seed.sql`.
- Emulador Android API 35: 42/42 testes instrumentados passaram, sem falhas.
- `vibeali.shop` já delega DNS ao Cloudflare (`evelyn`/`louis`), mas ainda não possui registro A/CNAME; não alterar a Site URL do Supabase até o domínio responder por HTTPS.

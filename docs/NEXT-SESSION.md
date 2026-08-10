# Próxima sessão

## Checkpoint de 10/08/2026 — central de segurança

- A central web publicada está autenticando por OTP e oferece visão geral, fila humana de fotos, denúncias, busca e sanções de contas, auditoria e equipe com separação entre revisor e administrador.
- As migrations `20260805090000_owner_profile_photo_review_state.sql`, `20260805100000_profile_photo_human_review_queue.sql` e `20260805120000_moderation_security_console.sql` compõem este checkpoint.
- A Edge Function `moderation-profile-photos` atende decisões e prévias privadas; listagens somente leitura usam RPCs protegidas pelo banco.
- O Android permite denunciar todo o álbum privado ou selecionar uma foto específica. O bearer da sessão é enviado explicitamente nas funções de mídia e remoção privadas.
- O smoke autenticado removeu o álbum sintético denunciado e resolveu o caso. A visão geral terminou com zero denúncias e zero álbuns denunciados; auditoria registrou `remove album` e `resolve case`.
- Validação concluída: 96/96 testes unitários, 33/33 instrumentados no Samsung, lint e APK aprovados, painel com 2/2 testes e build aprovado, sem erro de console ou `FATAL EXCEPTION`.
- Próxima fase: separar produção, configurar CI/CD e observabilidade, definir os serviços contínuos da VM e preparar a operação jurídica/moderação para lançamento fechado.

Checkpoint de 04/08/2026. O código inclui autenticação por OTP, onboarding por ano, verificação documental opcional, foto pública moderada, conversa direta, identidade/preferência de gênero e álbum privado com concessão individual.

## Publicado no Matcher Dev

- As migrations até `20260803130000_private_album_upload_reservation_leases.sql` estão aplicadas somente no projeto remoto de desenvolvimento. A migration de leases/reaper passou com 47/47 asserções hospedadas.
- As Edge Functions `private-album-media`, `private-album-delete` e `private-album-cleanup` estão publicadas. `private-album-media` foi republicada com suporte às chaves hospedadas atuais e distinção entre credencial inválida e indisponibilidade do Auth.
- O smoke test remoto sem sessão atravessou a função e retornou `401 AUTH_REQUIRED` com headers privados `no-store`. No Samsung, o APK deste checkpoint concluiu upload autenticado, recarregou a prévia privada e removeu somente o item sintético de teste; o álbum retornou de 5/10 para os 4/10 itens originais.
- Nenhuma alteração deste checkpoint foi aplicada a produção.

## Validações deste checkpoint

- Banco: 370 asserções pgTAP passaram localmente; a suíte nova de leases/reaper também passou no `Matcher Dev` com 47/47.
- Edge `private-album-media`: 25/25 testes Deno, `fmt --check` e type-check aprovados antes da publicação.
- Android: 89/89 testes unitários, `lintDebug`, `assembleDebug` e os 31 testes instrumentados compilados por `compileDebugAndroidTestKotlin` foram aprovados. O lint terminou com 0 erros e 7 avisos apenas de versões disponíveis/target API.
- O upload Android repete somente a reserva uma vez após resposta indeterminada, sempre com a mesma `idempotency_key`; depois do Storage, uma finalização indeterminada é repetida uma vez com o mesmo `item_id`, sem reenviar bytes. Uma resposta defensiva `available` não chama Storage, finalização nem cleanup.
- O gerenciamento do álbum foi reorganizado após uma inspeção funcional em modo somente leitura: entrada `Meus álbuns`, grade de três colunas, cartão `Adicionar`, resumo de acessos, menu `⋮`, tela dedicada de compartilhamento e seleção múltipla para revogação. O Matcher preserva identidade visual e textos próprios; o mapa está em `docs/UX-REFERENCE-ALBUMS.md`.
- A tela inicial de descoberta também foi reorganizada: cabeçalho compacto `Perto` com avatar que abre o Perfil, quota e filtro; resumo da preferência privada; e grade densa com nome, idade, faixa aproximada e intenção sobre a miniatura autorizada. Ela usa três colunas no telefone em retrato e adapta para 4/5/6 conforme a largura disponível. O modo remoto e o protótipo local usam o mesmo componente visual.
- O perfil público agora usa um hero alto com a mídia pública autorizada, nome/idade, distância aproximada e intenção. As ações `Álbum` e `Conversar` ficam persistentes na base; o menu separa abrir o álbum recebido de liberar/revogar o próprio álbum. Bloqueio e denúncia ficam no menu superior de segurança.
- Dois novos testes Compose cobrem a separação das operações de álbum, o estado desabilitado e o acesso às ações de segurança. Eles foram compilados, mas não executados no aparelho para não limpar novamente a sessão remota já autenticada.
- A conversa ativa foi redesenhada com faixa de identidade do perfil, foto pública autorizada, distância aproximada, compositor fixo, álbum contextual e menu superior de segurança. O atalho separa álbum recebido de liberar/revogar o próprio e nunca exibe prévia privada no chat.
- Três novos testes Compose cobrem álbum e segurança, mensagem vazia, preservação do rascunho em falha e limpeza após sucesso. Os três passaram no Samsung.
- A lista de conversas agora mostra somente conversas ativas em cartões compactos; o vazio orienta a voltar a `Perto`. O diálogo da primeira mensagem identifica destinatário, quota e abertura direta, com CTA integral e comportamento responsivo ao teclado.
- Três testes Compose adicionais cobrem estado vazio/retorno à descoberta, cartão ativo e primeira mensagem. Eles foram compilados, mas não executados no aparelho para evitar limpar novamente a sessão autenticada.
- APK debug gerado em `app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `83741EE9FD19111D9130BD58EF8650ED7074C38505BAB576BC8D95C90B15DBD1`.
- O APK foi instalado e validado no Samsung SM-A315G (Android 12/API 31): abriu sem crash, preservou a sessão, enviou uma imagem JPEG sintética, exibiu a quinta prévia e removeu exatamente esse item. Não houve `FATAL EXCEPTION`; os únicos avisos observados foram os já conhecidos do renderizador/driver do aparelho.
- A nova navegação de álbuns também foi aberta no Samsung sem alterar fotos ou acessos. O YAML do harness versão 9 passou no lint e inclui o cenário de revogação em lote.
- A nova descoberta foi validada visualmente no Samsung em modo de demonstração, sem dados reais. O teste Compose `RemoteDiscoveryScreenTest` confirmou três cartões na mesma linha, abertura do Perfil pelo avatar e acesso aos filtros.
- Os 5 testes de `MatcherSmokeTest` e o novo teste de descoberta passaram no Samsung. A execução instrumentada deixou o app remoto na tela de autenticação; é necessário entrar novamente por e-mail/OTP para validar a grade com a conta remota.
- Depois do novo login, a grade remota foi validada com a conta real no Samsung: três colunas legíveis no retrato, somente placeholders/mídia autorizada, avatar abrindo `Seu perfil`, filtro abrindo e fechando sem salvar alterações e nenhuma distância exata. O APK final foi reinstalado com `-r`, preservou a sessão e ficou aberto na descoberta.
- O novo perfil público também foi aberto com a conta remota no Samsung. Foram conferidos visualmente o hero, os cartões de privacidade, a barra fixa, `Liberar meu álbum`, `Bloquear perfil` e `Denunciar perfil`; nenhum desses comandos com efeito persistente foi confirmado.
- A conversa foi inspecionada no Samsung em modo demonstrativo com dados sintéticos: cabeçalho, mensagem, compositor, álbum desabilitado e menu de segurança renderizaram corretamente. Nenhuma mensagem, concessão, denúncia ou bloqueio foi confirmado.
- Depois de novo login por e-mail/OTP, o APK foi instalado com `-r` e preservou a sessão. A lista vazia, o retorno para `Perto` e o diálogo da primeira mensagem foram conferidos no backend real; o texto sintético usado para validar o teclado foi cancelado e nenhuma mensagem foi enviada.

## Próximos passos

- Implementar o envio de fotos moderadas na conversa, previsto no produto mas ainda ausente do adapter Android atual.
- Adicionar estados de entrega/erro e indicadores de não lidas sem enviar conteúdo de mensagem para telemetria.
- Capturar em uma ferramenta de rede um download autenticado pela Edge Function para registrar explicitamente `200`, tipo de imagem e `Cache-Control: private, no-store`; a prévia autenticada já passou no aplicativo.
- Decidir se a próxima fase inclui múltiplos álbuns nomeados; isso exige migration e novos contratos, pois o servidor atual impõe corretamente um único álbum ativo por titular.
- Antes de produção, criar um projeto `Matcher Prod` separado, separar builds/segredos, automatizar migrations/functions, agendar o cleanup, implementar exclusão de conta e operação de moderação, e configurar backup/restore, limites e alertas.

## Checkpoint adicional — push e foto única de perfil (04/08/2026)

- A migration `20260804170000_push_delivery_and_chat_media_automation.sql` está aplicada e registrada no `Matcher Dev`; 34/34 asserções pgTAP passaram no remoto.
- `notification-worker` e `profile-photo-moderation` estão publicados com JWT legado desligado e `WORKER_SHARED_SECRET` rotacionado diretamente no Supabase. O antigo worker de fotos do chat foi removido.
- O Android foi migrado para Firebase Installation ID, compilou sem APIs de token depreciadas, e o APK foi reinstalado com `-r` no Samsung sem apagar a sessão. Não houve `FATAL EXCEPTION`.
- O conjunto atual de funções concluiu 82/82 testes Deno; o parser da OpenAI cobre aprovação, adulto, abusivo, revisão e resposta inconsistente, e o worker passou no type-check.
- Firebase continua configurado para push. Os dois workers permanecem agendados a cada minuto por `pg_cron`/`pg_net`, com o bearer no Vault; o push real apareceu no Samsung.
- A migration `20260804210000_openai_profile_photo_moderation.sql` está aplicada e registrada no `Matcher Dev`. O worker de foto usa agora o endpoint gratuito `/v1/moderations` com `omni-moderation-latest`, e `OPENAI_API_KEY` existe somente nos secrets hospedados.
- A suíte hospedada de push/moderação passou com 38/38. O avatar real que estava preso em retry terminou `approved/completed`, sem código de erro, e foi promovido a avatar público.
- A ativação segura e os critérios de smoke estão em `docs/PUSH-AND-CHAT-MODERATION.md`.

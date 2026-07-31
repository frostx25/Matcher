# Backend Supabase do Matcher

Esta pasta contém a fundação reproduzível do backend de desenvolvimento. Ela não contém URL, chave, senha ou dado de produção.

## Contrato de conversa direta

O cliente não grava diretamente em `conversations`, `conversation_openings`, `messages`, `blocks` ou `reports`.

- `complete_onboarding(...)`: valida sessão, autodeclaração 18+ e aceite versionado, ativa a conta e publica o perfil sem conceder selo documental.
- `get_age_verification_status()`: devolve ao Android somente o estágio mínimo e autoritativo da conta.
- `age-verification-session`: cria no servidor uma sessão Didit `user` no workflow publicado, com `vendor_data` pseudônimo estável por usuário e referência única de tentativa separada; nenhuma chave do provedor chega ao APK.
- `age-verification-webhook`: valida a assinatura Didit, usa a notificação apenas como gatilho, consulta a decisão diretamente no provedor e chama a finalização reservada ao `service_role` somente após validar todos os controles.
- `start_conversation(recipient_id, first_message)`: cria uma conversa ativa e a primeira mensagem na mesma transação. Se o par já existir, reutiliza a conversa sem consumir outra abertura.
- `send_message(conversation_id, message_body)`: envia em uma conversa ativa sem consumir quota.
- `get_chat_quota()`: retorna limite, uso, saldo e próxima renovação da janela móvel de 24 horas.
- `block_user(blocked_user_id)`: interrompe o contato nos dois sentidos e oculta a conversa.
- `report_user(...)`: cria denúncia, caso de moderação e bloqueio imediato.
- `submit_profile_photo(object_path)`: confirma que o objeto privado existe, pertence ao usuário e está em sua pasta imutável; depois marca somente essa foto como `pending`.
- `moderate_profile_photo(profile_id, object_path, decision)`: RPC exclusiva do `service_role` que decide somente a foto atual ainda pendente.
- `get_my_profile_photo_state()`: devolve somente ao dono a candidata, seu estado de moderação e o caminho público anteriormente aprovado.
- `get_my_gender_settings()` e `update_gender_settings(...)`: mantêm identidade multisseleção/autodescrição/visibilidade separadas da preferência privada de descoberta.
- `get_discovery_profiles(cursor_profile_id, page_size, preference_cursor_version)`: aplica no servidor a preferência persistida antes da paginação e rejeita cursor antigo depois de uma alteração.
- `create_private_album(...)`, `reserve_private_album_item(...)` e `finalize_private_album_item(...)`: criam no máximo um álbum, reservam atomicamente até dez caminhos imutáveis e tornam uma imagem válida `available` sem aprovação prévia.
- `grant_private_album_access(...)` e `revoke_private_album_access(...)`: controlam concessão individual e revogável; bloquear revoga permanentemente concessões nos dois sentidos.
- `list_private_albums_shared_with_me()` e `get_private_album(...)`: retornam somente álbum/item IDs que o destinatário ainda pode abrir, sem nome de objeto ou URL.
- `authorize_private_album_item(item_id)`: revalida conta, moderação, bloqueio e concessão em cada leitura e devolve o caminho somente para o proxy autenticado de mídia.
- `private-album-delete`: recebe exclusivamente `item_id` ou `delete_album: true`, marca e oculta pelo JWT do dono e só então remove do Storage pelo `service_role`, sem devolver caminhos nem exigir `SELECT` no bucket.
- `report_private_album(...)`: abre caso de moderação e encerra apenas a concessão do denunciante, preservando destinatários não relacionados até uma decisão.
- `begin_private_album_deletion()` e `finalize_private_album_deletion()`: ocultam e revogam primeiro; a limpeza física idempotente usa a fila privada de objetos.

No Supabase, essas funções são chamadas por `POST /rest/v1/rpc/<nome_da_função>`. Leituras usam a API PostgREST gerada e são limitadas por RLS. `messages` e `conversations` entram na publicação Realtime.

## Estrutura

- `config.toml`: configuração local segura para versionamento.
- `migrations/`: schema, índices, RLS, grants e funções transacionais.
- `seed.sql`: usuários e conteúdo exclusivamente sintéticos, com e-mails no domínio reservado `.invalid`.
- `tests/database/`: testes pgTAP de contrato, quota e autorização.
- `functions/`: Edge Functions e testes puros dos fluxos de aferição etária e álbum privado.

## Segurança aplicada

- Somente o ano de nascimento fica em `accounts`; mês e dia não são coletados, e `profiles` nunca expõe esse campo.
- Descoberta, leitura de conversa, envio e quota exigem conta ativa, autodeclaração 18+ e termos aceitos; não dependem de uma decisão Didit.
- `age_verification_attempts` separa o pseudônimo opaco estável por usuário da referência única de tentativa e guarda somente workflow/versão, estado normalizado e metadados técnicos mínimos; identificadores diretos e PII retornada pelo Didit, selfie, documento, data de nascimento, URL de mídia, score, biometria e payload bruto são proibidos. O pseudônimo continua protegido como dado pessoal sob a LGPD.
- A aprovação exige, na mesma sessão `user`, documento brasileiro autêntico com regra 18+, prova de vida aprovada com `method = PASSIVE` e correspondência facial 1:1 aprovados pelo workflow Didit publicado. Controle ausente, revisão, cancelamento, erro ou reprovação mantém o perfil ativo e sem selo; somente a aprovação documental define `profiles.verified = true`.
- As evidências ficam no Didit com retenção configurada em um mês; não existe cópia no Matcher.
- Resultado informado pelo Android, deep link de retorno ou corpo de webhook isolado nunca concede o selo. O backend busca o resultado servidor-servidor.
- Webhook atrasado ou repetido não rebaixa uma verificação concluída, não muda visibilidade e nunca reativa conta suspensa/excluída.
- O bucket `profile-photos` é privado, limita objetos a 5 MB e aceita somente JPEG, PNG e WebP. O caminho é `{user_id}/{uuid}.{ext}` e não existe policy de `UPDATE`, impedindo move/upsert de bytes aprovados.
- Donos podem ler, inserir e excluir apenas objetos de sua pasta. Terceiros autenticados leem somente `profiles.avatar_path`, que contém a última foto aprovada de perfil visível e ativo; apagar esse objeto limpa a referência antes que o mesmo nome possa ser reutilizado.
- Candidata e estado ficam exclusivamente em `private.profile_photo_submissions`, sem grant direto para `authenticated`. O estado aceita somente `none`, `pending`, `approved`, `blocked_adult` e `blocked_abusive` e é consultado pelo dono via `get_my_profile_photo_state()`.
- Enviar ou bloquear a candidata B preserva a aprovada A. Somente a decisão `approved` promove B para `profiles.avatar_path`; assim bytes novos nunca herdam aprovação anterior nem ficam públicos durante moderação.
- Perfil armazena somente `region_code` aproximado, sem latitude ou longitude.
- Escrita direta nas tabelas críticas é revogada para `authenticated` e `anon`.
- Conversas e mensagens só são legíveis pelos dois participantes enquanto o contato estiver ativo e sem bloqueio.
- A cota é serializada por usuário com advisory lock e consumida junto da criação da conversa.
- Denúncias geram caso de moderação e auditoria sem copiar o texto da mensagem para logs/audit metadata.
- Funções `security definer` usam `search_path` vazio e referências totalmente qualificadas.
- Contas ainda sem onboarding, suspensas ou excluídas não conseguem consultar perfis de descoberta; conta ativa não perde acesso por estado de verificação documental.
- O catálogo versionado `gender_options` usa IDs estáveis; identidade e preferência são arrays distintos em tabelas `private`. Perfis legados recebem exclusivamente `prefer_not_to_say`, oculto, e preferência `everyone`, sem inferência por nome, bio, foto ou conversa.
- Preferência específica combina somente com identidades publicadas por interseção e antes do `LIMIT`. `everyone` não filtra, mas identidade oculta ou “prefiro não informar” continua ausente do payload. Alterar os ajustes incrementa `preference_cursor_version`, invalidando páginas antigas.
- Metadados, itens e concessões de álbum vivem no schema `private`; `authenticated` não recebe privilégios diretos. O bucket `private-albums` é privado, aceita apenas JPEG/PNG/WebP de até 5 MB, não possui policy de `SELECT`, `UPDATE` ou `DELETE` e só aceita upload para um caminho previamente reservado pelo servidor.
- Não usar `createSignedUrl` para álbum privado. A Edge Function `private-album-media` recebe apenas `item_id`, executa `authorize_private_album_item` com o JWT do usuário e transmite bytes pelo `service_role` com `Cache-Control: private, no-store, max-age=0`, sem redirect, URL persistente ou caminho no corpo/log.
- Revogar, bloquear, denunciar, suspender ou remover por moderação faz a próxima autorização retornar vazia. Imagens já carregadas precisam ser descartadas da memória pela UI; nenhuma solução promete recuperar screenshots ou fotografias externas.
- Exclusão muda o estado para `deleting` antes da limpeza. O Android chama `private-album-delete`, que autoriza pelos RPCs do usuário antes de remover somente os caminhos canônicos retornados com `service_role`; o cliente não recebe `SELECT` nem apaga diretamente no bucket. O worker `private-album-cleanup` consome a fila e confirma de modo idempotente qualquer limpeza pendente. A fila e os caminhos nunca são expostos a `authenticated`.
- Auditoria do álbum registra apenas tipo de evento, atores e motivo normalizado; nome de objeto, URL, bytes, identidade/autodescrição, preferência e texto livre de denúncia não entram em `audit_events.metadata`.

## Execução local em máquina com Docker

Requer Supabase CLI e um runtime compatível com Docker:

```powershell
supabase start
supabase db reset
supabase test db
supabase db lint --local
```

O stack local completo recomenda pelo menos 7 GB disponíveis. Nesta máquina, prefira um projeto Supabase remoto exclusivo de desenvolvimento para não disputar memória com Android Studio.

## Projeto remoto de desenvolvimento

O projeto atual é `Matcher Dev`, ref `gevdssaambgivxiqilad`, na região `sa-east-1`. Ele é isolado de produção e contém somente migrations e dados sintéticos de desenvolvimento.

- Auth Android: código OTP de seis dígitos validado automaticamente dentro do app, sem deep link.
- SMTP do Resend configurado no projeto de desenvolvimento e template **Magic link or OTP** usando `{{ .Token }}`.
- A migration forward-only `20260731190000_soft_age_gate_profile_photos.sql` desfaz o bloqueio de produto sem apagar o histórico: recupera contas `pending` com perfil 18+ completo, separa acesso do selo Didit e adiciona o fluxo privado de fotos. Ela está aplicada no remoto de desenvolvimento desde 31/07/2026.
- A migration forward-only `20260731210000_private_albums_gender_preferences.sql` adiciona identidade/preferência privadas, descoberta autoritativa, álbum privado, revogação por bloqueio, denúncia e fila de limpeza. Antes de aplicá-la no remoto, publique também `private-album-media`, `private-album-delete` e o consumidor `private-album-cleanup`; a migration ainda não deve ser tratada como aplicada em produção.
- As Edge Functions `age-verification-session` e `age-verification-webhook` foram republicadas depois da validação do workflow e dos cinco valores `DIDIT_*`; uma nova publicação deve repetir essas verificações.
- Os cinco arquivos pgTAP atuais declaram 224 asserções de autorização, todas aprovadas localmente em 31/07/2026; a execução local exige Docker.
- `seed.sql` aplicado apenas para disponibilizar perfis sintéticos na grade de desenvolvimento.

Depois de criar um projeto vazio e separado no painel do Supabase:

```powershell
supabase login
supabase link --project-ref <DEV_PROJECT_REF>
supabase db push --dry-run
supabase functions deploy age-verification-return
supabase functions deploy age-verification-webhook
supabase functions deploy age-verification-session
supabase functions deploy private-album-media
supabase functions deploy private-album-delete
supabase functions deploy private-album-cleanup
supabase db push
supabase test db --linked
```

Nunca use `db reset --linked` fora de um projeto descartável. O seed não deve ser enviado a produção. Antes de aplicar a migration soft-gate, revise o `db push --dry-run`; ela reativa somente contas `pending` com ano 18+, termos e perfil completos e mantém `suspended`/`deleted` intocados.

O contrato completo de workflow, decisão autoritativa, retenção e capacidade está em [`docs/age-assurance.md`](../docs/age-assurance.md). As Edge Functions exigem `DIDIT_API_KEY`, `DIDIT_WORKFLOW_ID`, `DIDIT_WORKFLOW_VERSION`, `DIDIT_WEBHOOK_SECRET` e `DIDIT_ENVIRONMENT`, sempre do mesmo ambiente. Na data deste documento, o Didit divulga 500 verificações gratuitas mensais para cada recurso central; como o fluxo usa três recursos, 500 fluxos completos é apenas o máximo teórico sem repetições. Franquia e preços precisam ser reconfirmados nos termos vigentes e não constituem promessa de gratuidade futura.

As variáveis consumidas futuramente pelo Android estão documentadas em `.env.example`. O app usará apenas URL e chave publicável; chaves secretas permanecem fora do APK.

# Matcher — test harness

## Objetivo

Manter um conjunto de testes reproduzíveis para validar produto, segurança, quota e performance antes de qualquer staging/produção.

O harness deve funcionar com dados sintéticos, sem depender de uma VM de produção e sem usar compras reais.

## Perfis de execução

| Perfil | Uso | Dependências |
|---|---|---|
| `fast` | Domínio, ViewModels, contratos e regressão rápida | JDK/Gradle, sem Docker e sem emulador |
| `device` | Fluxos principais do app | Android Studio + um AVD ou aparelho físico + backend dev |
| `full` | Integração local completa | Docker/WSL2, emulador acelerado e serviços locais |
| `ci` | Validação automatizada | Runner com Android SDK, cache Gradle e dispositivo/emulador configurado |

## Camadas

1. **Static:** lint, análise de dependências, detecção de segredos e validação de schema.
2. **Unit:** quota, autorização, visibilidade, filtros, estados de chat e ViewModels.
3. **Contract:** requests/responses da API, erros, paginação e entitlements.
4. **Instrumented/UI:** onboarding, identidade/preferência, selo 18+ opcional, moderação/versionamento de fotos públicas, álbum privado, grade, abertura direta, quota, bloqueio, denúncia e exclusão.
5. **Smoke:** instalação, login de teste, grade, primeira mensagem e logout.
6. **Performance:** startup, primeiro lote da grade, rolagem, memória e carregamento de imagens.
7. **Security:** acesso entre usuários, bloqueio, rate limit, dados sensíveis em logs e tentativa de burlar quota.

## Fixtures

- Usuários sintéticos: `user-free`, `user-extra`, `user-pro`, `user-blocked`, `user-moderation-review`.
- Perfil sem localização, perfil com localização aproximada e perfil oculto; identidade publicada, identidade oculta e “prefiro não informar”.
- Preferência privada sintética com múltiplos gêneros e preferência “todas as pessoas”; nenhuma fixture infere identidade por nome, bio ou foto.
- Conversa criada imediatamente, conversa existente, bloqueada e denunciada.
- Conta ativa não verificada; verificação Didit opcional não iniciada, pendente, workflow completo aprovado, `In Review` preservando a sessão corrente, controle obrigatório ausente, recusada, cancelada, expirada, capacidade indisponível e callback forjado.
- Versões sintéticas de foto em `pending`, `approved`, `adult` e `abusive`, incluindo substituição de uma versão aprovada por uma nova versão pendente. As fixtures representam estados, não arquivos reais nem um classificador automático.
- Álbum privado sintético com zero, dez e onze referências opacas a imagens artificiais; concessão vigente, revogada, denunciada e removida por bloqueio. Nenhum arquivo real, nudez ou URL pública faz parte da fixture.
- Entitlement válido, expirado, cancelado e compra não reconhecida.
- Nunca incluir e-mail, telefone, foto ou coordenada de pessoa real.

Os cenários executáveis ficam em:

- `harness/scenarios/onboarding.yml` (versão 14): autenticação por e-mail com single-flight, cooldown e timeout indeterminado, onboarding 18+, compatibilidade segura, identidade de gênero, selo opcional, precedência da moderação e visibilidade/versionamento de foto pública.
- `harness/scenarios/discovery.yml` (versão 2): preferência privada multi-seleção, filtragem autoritativa, bloqueio de leitura ampla, troca segura de cursor, privacidade e migração.
- `harness/scenarios/private-album.yml` (versão 6): limite, reserva idempotente com TTL/reaper, cleanup após cancelamento, upload real do Storage, autenticação e classificação de falhas da mídia, recuperação de prévia, acesso, concorrência, concessão, revogação, bloqueio, denúncia, moderação, proteção de mídia e limpeza.
- `harness/scenarios/chat.yml`: abertura direta e quota de novas conversas.

## Comandos planejados

Quando o projeto Android estiver criado, os comandos oficiais serão padronizados no Gradle Wrapper:

```text
./gradlew test
./gradlew lintDebug
./gradlew connectedDebugAndroidTest
./gradlew :app:assembleDebug
```

No Windows, usar `gradlew.bat` equivalente. O harness não deve exigir Docker para os testes `fast`.

## Definition of Done do harness

- Todo requisito P0 tem pelo menos um cenário em `harness/scenarios/`.
- Quota é testada com duas requisições concorrentes.
- Bloqueio é testado tanto no app quanto no backend.
- Mensagem de usuário bloqueado não chega ao destinatário.
- Perfis não retornam coordenadas exatas.
- Entitlement nunca é aceito somente por valor informado pelo cliente.
- Confirmar o OTP cria ou confirma a conta; concluir o onboarding com autodeclaração 18+ e termos ativa o perfil como não verificado, sem exigir Didit para descoberta ou conversa.
- Deep link ou resultado enviado pelo cliente nunca concede o selo; somente o resultado consultado pelo backend pode alterar o estado da verificação.
- Nenhum teste, log ou fixture armazena identificador direto ou PII retornada pelo Didit, selfie, documento, data de nascimento, URL de mídia, score, biometria ou payload bruto; somente pseudônimos sintéticos podem representar o contrato.
- A aprovação 18+ exige sessão `user` em workflow publicado e versionado, documento brasileiro, prova de vida aprovada com `method = PASSIVE` e correspondência facial aprovada; ela concede somente **18+ verificado**, nunca identidade ou idade exata.
- `vendor_data` é estável e opaco por usuário, enquanto a referência de tentativa é única e separada; `In Review` não cria nem reabre sessão e, assim como falha ou cancelamento, não desativa a conta.
- Suspensão, exclusão e moderação prevalecem: um resultado Didit ou uma foto aprovada nunca reativa nem republica uma conta restrita.
- O contrato testa retenção de um mês e a franquia atualmente divulgada de 500 verificações mensais por recurso central, tratando 500 fluxos completos apenas como máximo teórico e sem prometer gratuidade futura ou ativar cobrança excedente. Esgotamento bloqueia somente uma nova sessão opcional.
- Foto permitida pode ser qualquer imagem; `pending`, `adult` e `abusive` ficam privadas e geram placeholder cinza para terceiros, enquanto somente `approved` é visível.
- Uma nova versão não substitui a versão aprovada atual até receber sua própria aprovação. Os testes validam decisões e visibilidade sem pressupor nem prometer classificação automática.
- Identidade e preferência são testadas como dados distintos; a preferência aceita múltiplos gêneros, é aplicada pelo servidor antes da paginação e nunca aparece para terceiros, em logs ou telemetria.
- Perfil legado recebe “prefiro não informar” oculto e “todas as pessoas”, sem inferência; identidade oculta não pode ser deduzida por uma filtragem específica.
- Álbum privado é invisível fora do fluxo autorizado, aceita no máximo dez imagens e as disponibiliza sem aprovação prévia somente ao titular ou destinatário com concessão vigente.
- Leitura sem concessão, após revogação, bloqueio, suspensão ou moderação é negada também no Storage. URLs públicas/permanentes e cache persistente de álbum são proibidos.
- Bloqueio revoga concessões de álbum nos dois sentidos e desbloqueio não restaura acesso; denúncia abre caso auditável, oculta o conteúdo para o denunciante, impede nova concessão enquanto o caso estiver vigente e preserva a evidência por no mínimo 30 dias.
- Excluir item, álbum ou conta oculta imediatamente e limpa objeto, metadados e concessões de forma idempotente, exceto pela evidência sob retenção. Toda mutação é vinculada ao `album_id` exibido, então retry ou resposta atrasada de uma geração excluída nunca altera a substituta. Vídeo, expiração e visualização única são confirmados como fora desta versão.
- Falha de rede mostra estado recuperável e não duplica mensagem.
- O relatório informa comando, ambiente, resultado e evidência.

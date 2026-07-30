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
4. **Instrumented/UI:** onboarding, grade, criação de solicitação, quota, bloqueio, denúncia e exclusão.
5. **Smoke:** instalação, login de teste, grade, primeira mensagem e logout.
6. **Performance:** startup, primeiro lote da grade, rolagem, memória e carregamento de imagens.
7. **Security:** acesso entre usuários, bloqueio, rate limit, dados sensíveis em logs e tentativa de burlar quota.

## Fixtures

- Usuários sintéticos: `user-free`, `user-extra`, `user-pro`, `user-blocked`, `user-moderation-review`.
- Perfil sem localização, perfil com localização aproximada e perfil oculto.
- Conversa pendente, aceita, ignorada, bloqueada e denunciada.
- Entitlement válido, expirado, cancelado e compra não reconhecida.
- Nunca incluir e-mail, telefone, foto ou coordenada de pessoa real.

Os cenários executáveis ficam em:

- `harness/scenarios/onboarding.yml`: maioridade, termos e perfil local.
- `harness/scenarios/chat.yml`: solicitações diretas e quota de novas conversas.

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
- Falha de rede mostra estado recuperável e não duplica mensagem.
- O relatório informa comando, ambiente, resultado e evidência.

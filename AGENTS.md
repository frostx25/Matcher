# Matcher — instruções para agentes

## Objetivo do repositório

O Matcher é um app Android de conexões entre adultos. A experiência usa descoberta por proximidade e conversa direta, sem swipe e sem match obrigatório.

Decisões atuais:

- Plataforma inicial: Android nativo.
- Público: pessoas adultas, 18+.
- UI: preto/rosa, moderna, arredondada e acessível.
- Free: 5 novas conversas por janela móvel de 24 horas.
- Extra/Pro: limites maiores e recursos premium ainda sujeitos a validação.
- Localização: aproximada; nunca exibir ou persistir coordenada exata como dado de perfil.
- Stack-base: Kotlin, Jetpack Compose, PostgreSQL/PostGIS e Supabase para acelerar o ciclo inicial.

## Regras de trabalho

1. Leia `PLANO.md` e `docs/SPEC-MVP.md` antes de alterar comportamento do produto.
2. Mude o contrato antes da implementação: atualize a spec, cenários do `harness/` e depois o código.
3. Não introduza swipe, match obrigatório, feed público ou localização exata sem uma decisão explícita do produto.
4. Dados de orientação, identidade, mensagens, fotos e localização são sensíveis. Não coloque esses dados em logs, fixtures reais, commits ou mensagens de erro.
5. Bloqueio, denúncia, limite anti-spam e controle de visibilidade são requisitos do núcleo, não melhorias futuras.
6. O cliente nunca decide sozinho quota, assinatura, permissão de conversa ou estado de moderação; essas decisões devem ser confirmadas pelo servidor.
7. O app deve continuar utilizável com rede lenta, localização aproximada e sem permissão de localização, quando a função permitir.
8. Proteja performance da grade: paginação por cursor, miniaturas, cache, chaves estáveis e nenhuma consulta que retorne todos os perfis.
9. Use dados sintéticos determinísticos. Nunca use dados reais de pessoas nos testes.
10. Não configure produção, não coloque segredos no repositório e não dependa de uma VM para o desenvolvimento local.

## Testes obrigatórios por mudança

- Domínio/servidor: testes unitários e de autorização para a regra alterada.
- Android: teste de ViewModel/repositório; teste Compose quando houver mudança visual ou de fluxo.
- API/dados: teste de contrato e migração quando houver mudança de schema.
- Segurança: testar bloqueio, denúncia e vazamento de dados quando a mudança tocar conversa, perfil, localização ou billing.
- Performance: medir grade, imagens e startup quando a mudança tocar descoberta, navegação ou renderização.

## Definition of Done

Uma tarefa só está pronta quando a spec e os cenários estão atualizados, os testes relevantes passam, a mudança foi revisada quanto a privacidade e o resumo final informa riscos, comandos executados e o que ficou fora de escopo.


# Papéis de agentes do Matcher

Os papéis abaixo são contratos de trabalho. Um agente pode acumular papéis em uma tarefa pequena, mas cada mudança deve ter um responsável principal.

## 1. Orquestrador de produto

Responsável por priorização, decisões de escopo, critérios de aceitação e consistência com `PLANO.md`/`docs/SPEC-MVP.md`.

Entrega: decisão registrada, tarefa pequena, dependências explícitas e critério de pronto.

## 2. Agente Android/UI

Responsável por Compose, navegação, estados de tela, design system rosa/preto, acessibilidade, carregamento e experiência de erro.

Entrega: telas, ViewModels, testes de UI quando necessário, screenshots ou evidência de teste e nenhuma regra de quota duplicada no cliente.

## 3. Agente backend/dados

Responsável por schema, PostGIS, autorização, quota atômica, chat, entitlements e contratos de API.

Entrega: migração reversível quando possível, testes de autorização/concorrência, contrato atualizado e dados sintéticos.

## 4. Agente de confiança, segurança e privacidade

Responsável por idade, bloqueio, denúncia, moderação, retenção, minimização de dados e revisão de ameaças.

Entrega: checklist de risco, casos abusivos testados e registro de qualquer dado sensível tratado.

## 5. Agente QA/harness

Responsável por fixtures, cenários de aceitação, testes de contrato, smoke tests, testes de regressão e relatório de falhas.

Entrega: cenário reproduzível, comando executado, resultado e evidência.

## 6. Agente de performance/release

Responsável por startup, grade, imagens, consumo de memória, builds, signing de teste e preparação de beta.

Entrega: medição antes/depois, orçamento de performance e confirmação de que nenhum segredo ou configuração de produção entrou no build local.

## Regras de coordenação

- Uma tarefa deve ter um agente owner.
- Contratos e critérios de aceitação vêm antes da implementação.
- Agentes podem trabalhar em paralelo somente quando não editam o mesmo contrato/arquivo.
- O agente QA valida o comportamento; não deve validar apenas se o app compila.
- O agente de segurança pode bloquear a entrega de uma feature que exponha dados, permita abuso ou não tenha bloqueio/denúncia.


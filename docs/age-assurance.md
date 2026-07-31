# Selo 18+ opcional com Didit

Status: contrato operacional da verificação opcional do MVP
Versão: 1.1
Provedor: Didit
Mercado: Brasil
Retenção de evidências no provedor: 1 mês

## 1. Decisão

O e-mail confirmado cria ou confirma a conta. O onboarding básico exige ano de nascimento, autodeclaração 18+ e aceite dos Termos/Política; ao concluí-lo, conta e perfil ficam ativos e utilizáveis como **não verificados**. Descoberta e conversa não exigem Didit.

Depois, a pessoa pode iniciar voluntariamente a experiência hospedada pelo Didit na aba Perfil. Uma decisão servidor-servidor concede somente o selo **18+ verificado** quando confirma, na mesma sessão:

1. documento brasileiro válido e autêntico, com a regra de idade mínima de 18 anos atendida;
2. prova de vida passiva aprovada;
3. correspondência facial 1:1 aprovada entre o retrato do documento e a captura ao vivo.

Não existe caminho parcial para o selo: nessa verificação opcional, documento, prova de vida e correspondência facial são todos obrigatórios. Não iniciar, cancelar, falhar ou ficar `In Review` não desativa a conta, que permanece ativa como não verificada; nenhum desses estados, isoladamente, oculta o perfil ou bloqueia descoberta/conversa.

O selo comunica apenas que o workflow confirmou 18+. Ele não comunica identidade verificada nem idade exata e não substitui a idade declarada do perfil. Suspensão, exclusão e restrições de moderação prevalecem: um resultado Didit jamais reativa, republica ou libera uma conta moderada.

## 2. Contrato do workflow publicado

Cada ambiente possui um workflow próprio no Didit Business Console. Antes de aceitar tráfego, o workflow precisa estar **publicado**; uma sessão deve ficar vinculada à versão imutável que foi aprovada para aquele ambiente.

Configuração mínima:

| Etapa | Regra obrigatória |
|---|---|
| ID Verification | Restringir aos documentos brasileiros suportados, validar autenticidade e exigir idade mínima de 18 anos. |
| Passive Liveness | Exigir captura ao vivo, decisão aprovada e `method = PASSIVE` na decisão autoritativa. |
| Face Match | Comparação 1:1 entre o retrato extraído do documento e a captura da prova de vida, com decisão aprovada. |
| Retenção | Application Settings → Data configurado para `1 month`. |

Documentos aceitos devem ser limitados aos tipos brasileiros que o Didit suporta e que a operação aprovou, como passaporte, carteira de identidade e carteira de motorista. A lista efetiva do workflow é a autoridade; adicionar outro país ou tipo documental exige nova revisão e uma nova versão publicada.

O backend compara a sessão retornada com `DIDIT_WORKFLOW_ID`, `DIDIT_WORKFLOW_VERSION` e `DIDIT_ENVIRONMENT`. Mudança de configuração gera uma nova versão e exige atualização controlada desses valores. Rascunho, versão antiga, ambiente diferente ou workflow desconhecido falham de forma fechada para o selo, sem alterar o acesso da conta.

## 3. Fluxo servidor-servidor

### 3.1 Criação da sessão

1. O Android solicita uma sessão ao backend autenticado; ele nunca chama a API de gerenciamento do Didit diretamente.
2. O backend confirma que o onboarding foi concluído, que a conta não está suspensa nem excluída e que a solicitação partiu da ação opcional no Perfil. Se a sessão corrente estiver `In Review`, retorna revisão pendente e não cria nem reabre outra sessão.
3. O backend deriva ou recupera um pseudônimo opaco e estável por usuário e o envia como `vendor_data`. Esse valor é igual nas tentativas do mesmo usuário e diferente entre usuários, mas não contém e-mail, nome, data de nascimento, ID de autenticação ou outro identificador direto.
4. O backend gera uma referência opaca única para cada tentativa. Essa referência e o ID de sessão são separados do `vendor_data` estável.
5. A sessão é criada somente com o workflow KYC publicado configurado para o ambiente. A API v3 em produção pode omitir `session_kind` nessa resposta inicial; a decisão autoritativa posterior precisa devolvê-lo como `user`.
6. O Android recebe apenas a URL hospedada e a referência pública mínima necessária para abrir o fluxo.

### 3.2 Webhook

O webhook Didit v3 é público apenas para receber notificações e precisa:

- validar `X-Signature-V2` com `DIDIT_WEBHOOK_SECRET`, HMAC-SHA256, comparação em tempo constante e a canonicalização definida pela documentação oficial;
- validar o timestamp e rejeitar mensagens fora de uma janela máxima de cinco minutos;
- limitar método, `Content-Type` e tamanho do corpo;
- responder rapidamente e deixar a finalização idempotente por sessão;
- nunca registrar corpo bruto, assinatura, documento, selfie, PII ou biometria.

Uma assinatura válida não é uma decisão de maioridade. A notificação apenas agenda ou dispara a consulta autoritativa.

### 3.3 Decisão autoritativa

Depois do gatilho, a Edge Function consulta a decisão pelo endpoint de gerenciamento `GET /v3/session/{session_id}/decision/` com `DIDIT_API_KEY`. O selo só é concedido se todas estas condições forem verdadeiras:

- sessão conhecida e vinculada à referência única da tentativa e à conta corretas;
- `session_kind` igual a `user`;
- `vendor_data` igual ao pseudônimo opaco estável registrado para o usuário, sem ser confundido com a referência única da tentativa;
- ambiente e `workflow_id` exatamente iguais aos vinculados à tentativa; a versão é validada na resposta de criação e fica registrada antes de a URL ser entregue ao Android;
- decisão final da sessão igual a `Approved`;
- relatório documental aprovado no workflow publicado que restringe os documentos ao Brasil e aplica `minimum_age = 18` com ação de recusa;
- prova de vida aprovada e método retornado exatamente igual a `PASSIVE`;
- correspondência facial aprovada;
- conta não suspensa, não excluída e sem restrição de moderação incompatível com a exibição do selo.

Estado `In Review` é normalizado como revisão pendente: mantém a mesma sessão corrente e impede criar ou reabrir outra sessão Didit até uma decisão final. `Declined`, expirado, cancelado, erro de rede, resposta malformada, controle ausente ou valor desconhecido também não concedem o selo; nova tentativa ou atendimento só aparece conforme a política do estado final, nunca durante `In Review`. A conta continua ativa e utilizável como não verificada, salvo suspensão, exclusão ou moderação independente.

A finalização do selo é atômica e idempotente. Evento repetido não duplica auditoria; evento atrasado não remove um selo já confirmado e nunca reativa, republica ou libera conta suspensa, excluída ou limitada pela moderação.

## 4. Minimização, logs e retenção

O Didit hospeda a captura e retém as evidências pelo período configurado. A resposta autoritativa pode conter PII ou referências de mídia; a Edge Function deve extrair somente campos técnicos em uma allowlist e descartar o restante em memória, sem copiar, retornar ao Android ou registrar em logs.

O Matcher pode persistir apenas:

- pseudônimo técnico estável do usuário usado como `vendor_data`, sem identificador direto;
- identificador interno único da tentativa e referência de sessão opaca, separados do `vendor_data`;
- provedor `didit` e método normalizado `document`;
- status normalizado e booleano 18+;
- workflow, versão do workflow e versão da política;
- instantes de criação, atualização e decisão.

O `vendor_data` continua sendo dado pessoal pseudonimizado sob a LGPD, embora não identifique diretamente a pessoa. Ele recebe os mesmos controles de acesso, finalidade e retenção dos demais metadados técnicos; esta é a única referência estável permitida no provedor.

É proibido persistir em banco, Storage, analytics, logs, tracing, auditoria, crash report ou fixture:

- nome civil, e-mail, telefone, CPF ou número documental obtido do Didit;
- documento, selfie, vídeo, retrato extraído ou URL de mídia;
- data de nascimento completa, idade extraída ou endereço;
- score, embedding, template facial ou qualquer dado biométrico;
- corpo bruto de webhook ou resposta bruta de decisão.

A retenção no Didit fica configurada em **um mês**, o menor período adotado. Não aumentar esse prazo sem revisão de privacidade, segurança e base legal. O Matcher conserva somente o registro técnico mínimo segundo sua política geral de conta e auditoria.

## 5. Franquia gratuita e capacidade

Na data desta decisão, a documentação comercial do Didit informa **500 verificações gratuitas por mês para cada recurso central**, incluindo ID Verification, Passive Liveness e Face Match. O workflow usa os três recursos; portanto, 500 fluxos completos é apenas o máximo teórico quando cada recurso é consumido uma vez e não há tentativas parciais, repetições ou reprocessamentos. Franquia, classificação dos recursos e preços podem mudar: painel, documentação e termos vigentes precisam ser conferidos antes de cada lançamento e durante a operação. Este contrato não promete gratuidade futura.

Operação mínima:

- acompanhar consumo por recurso no painel, não apenas sessões concluídas;
- reconfirmar franquia e preços vigentes antes do deploy e registrar a data da conferência;
- alertar em 70%, 85%, 95% e 100% da menor franquia restante;
- impedir novas sessões opcionais quando qualquer recurso obrigatório do workflow estiver sem capacidade conhecida;
- manter contas ativas como não verificadas e mostrar uma mensagem recuperável apenas na área do selo;
- nunca remover um controle, aceitar aprovação parcial ou habilitar cobrança excedente automaticamente;
- contratar capacidade paga somente após aprovação explícita de produto/finanças e atualização do runbook; nunca assumir que os 500 gratuitos continuarão disponíveis indefinidamente.

## 6. Variáveis de ambiente

Todas as variáveis abaixo são exclusivas do backend:

| Variável | Uso |
|---|---|
| `DIDIT_API_KEY` | Autentica criação e consulta de sessões. Segredo. |
| `DIDIT_WORKFLOW_ID` | Identifica o workflow publicado permitido. |
| `DIDIT_WORKFLOW_VERSION` | Fixa a versão publicada aceita pelo backend. |
| `DIDIT_WEBHOOK_SECRET` | Verifica a assinatura do webhook. Segredo. |
| `DIDIT_ENVIRONMENT` | `sandbox` ou `live`, exatamente como retornado pela Didit. |

Chave, workflow, versão e webhook devem pertencer ao mesmo ambiente. As cinco variáveis ficam em Supabase Secrets ou em `supabase/functions/.env.local` durante desenvolvimento e nunca entram em `local.properties`, `BuildConfig`, APK, logs ou commits.

Ao rotacionar `DIDIT_WEBHOOK_SECRET`, use uma janela controlada que aceite o segredo anterior apenas pelo menor tempo necessário. Remova-o ao terminar a rotação.

## 7. Gate de implantação da verificação opcional

O onboarding e o uso da conta não dependem deste gate. Antes de disponibilizar a ação **Verificar 18+** no Perfil, concluir este checklist:

- workflow de sandbox publicado com as três etapas e idade mínima de 18 anos;
- Passive Liveness configurado e validado com `method = PASSIVE`;
- criação de sessão com workflow KYC publicado, `vendor_data` estável por usuário e referência única de tentativa separada; a decisão consultada deve confirmar `session_kind = user`;
- retenção confirmada em um mês;
- `workflow_id` e versão copiados da publicação, sem usar rascunho;
- webhook v3 apontando para a Edge Function correta e assinatura V2 validada;
- as cinco variáveis `DIDIT_*` configuradas no mesmo ambiente;
- Edge Functions criando e consultando sessões Didit, validando workflow/versão e falhando de forma fechada;
- teste aprovado em sandbox e teste negativo para menor, spoof, `In Review` sem nova sessão, assinatura inválida, replay e versão divergente, confirmando que nenhum resultado desativa uma conta ativa;
- inspeção de banco, logs e relatórios confirmando ausência de PII retornada pelo Didit, documento, mídia, score e payload bruto, preservando apenas o pseudônimo técnico previsto;
- franquia e preços vigentes reconfirmados, com monitoramento mensal configurado.

Também deve existir teste explícito de precedência: uma aprovação atrasada pode registrar o resultado técnico permitido, mas não reativa nem torna público um perfil suspenso, excluído ou restrito pela moderação.

O retorno `matcher://age-verification/...` serve apenas para reabrir o app e solicitar o status ao backend. Ele não transporta nem comprova uma decisão.

## 8. Referências do provedor

- [Preços e franquia gratuita do Didit](https://docs.didit.me/getting-started/pricing)
- [Workflows e versionamento](https://docs.didit.me/console/workflows)
- [Criação de sessão](https://docs.didit.me/sessions-api/create-session)
- [Webhooks](https://docs.didit.me/integration/webhooks)
- [ID Verification e regras de idade](https://docs.didit.me/core-technology/id-verification/overview)
- [Documentos suportados](https://docs.didit.me/reference/supported-documents)

# Matcher — especificação do MVP

Status: rascunho executável  
Versão: 0.1  
Plataforma: Android  
Mercado inicial: Brasil, uma região metropolitana

## 1. Objetivo

Permitir que pessoas adultas descubram perfis próximos e iniciem conversas diretamente, sem swipe e sem match obrigatório, com limite gratuito de cinco novas conversas por janela móvel de 24 horas.

## 2. Atores

- **Usuário:** cria perfil, descobre pessoas, inicia conversas e controla sua visibilidade.
- **Destinatário:** recebe uma solicitação de conversa e decide aceitar, ignorar, bloquear ou denunciar.
- **Moderador:** revisa denúncias, conteúdo e contas sinalizadas.
- **Serviço de entitlement:** confirma o plano Free, Extra ou Pro no servidor.

## 3. Escopo funcional

### 3.1 Conta e idade

- Cadastro por e-mail/OTP na primeira versão.
- Confirmação de maioridade e aceitação dos Termos de Uso/Política de Privacidade antes de publicar perfil.
- Sessão revogável, recuperação de conta e logout de todos os dispositivos.
- Exclusão de conta no app e solicitação de exclusão por página web.

### 3.2 Perfil

- Nome de exibição, idade derivada da data de nascimento, bio e até cinco fotos.
- Identidade de gênero, pronomes, orientação, intenção e tipo de relacionamento como campos separados.
- Cada campo possui visibilidade configurável; o usuário pode preferir não informar.
- Fotos e bio passam pelas regras de conteúdo antes de ficarem públicas.

### 3.3 Descoberta

- Grade paginada de perfis próximos.
- Ordenação por proximidade aproximada, atividade recente e compatibilidade com preferências declaradas.
- Filtros básicos por idade, identidade, intenção, tipo de relacionamento e verificação.
- Distância mostrada em faixas, nunca em metros exatos.
- Funciona com localização escolhida por região e com localização aproximada do Android.
- Pausar descoberta, ocultar distância e não aparecer em exploração.

### 3.4 Conversa direta

- O botão principal do perfil é **Conversar**.
- O primeiro envio cria uma `chat_request` e consome uma abertura de conversa.
- O destinatário recebe uma solicitação, não um chat irreversível.
- O destinatário pode aceitar, ignorar, bloquear ou denunciar.
- Quando aceita ou responde, a conversa fica ativa.
- Texto e fotos são suportados; mídia efêmera e chamadas ficam fora do MVP.
- Mensagens em conversas já ativas não consomem novas aberturas.

### 3.5 Quota e planos

- **Free:** 5 novas aberturas por janela móvel de 24 horas.
- **Extra:** quota maior e recursos adicionais definidos após beta.
- **Pro:** quota muito maior ou ilimitada, sempre sujeita a limites anti-spam e moderação.
- A quota é calculada no servidor e decrementada atomicamente.
- O cliente exibe quota restante, próxima renovação e motivo de bloqueio.
- Assinaturas são verificadas pelo backend; o cliente não pode se conceder entitlement.

### 3.6 Segurança

- Bloquear e denunciar a partir do perfil e da conversa.
- Silenciar conversa.
- Limites por usuário, dispositivo, IP, perfil destinatário e intervalo de tempo.
- Detecção de spam, mensagens repetitivas, criação em massa e evasão de banimento.
- Painel de moderação com fila, evidência, histórico e auditoria.

## 4. Requisitos não funcionais

- Android nativo com Kotlin e Jetpack Compose.
- UI base preta/rosa, componentes arredondados e suporte a textos ampliados.
- Primeira tela interativa: meta de até 2,5 s em aparelho Android intermediário.
- Primeiro conjunto de perfis: meta de até 1,5 s após resposta da API.
- Grade sem retornar todos os perfis; usar paginação por cursor e índice geográfico.
- Imagens com miniaturas, cache e carregamento sob demanda.
- Mensagens com confirmação visual imediata e confirmação do servidor em até 500 ms em rede normal.
- HTTPS em todas as comunicações e controle de acesso por função.
- Não registrar no log conteúdo de mensagens, coordenadas exatas, documentos de identidade ou orientação sexual.

## 5. Regras de negócio críticas

### BR-CHAT-01 — nova abertura

Uma nova conversa só pode ser criada se a conta estiver ativa, o destinatário não estiver bloqueado e a quota do remetente for maior que zero.

### BR-CHAT-02 — consumo atômico

Criar `chat_request` e consumir uma abertura devem ocorrer na mesma transação. Duas requisições simultâneas não podem gastar uma única abertura duas vezes nem ultrapassar o limite.

### BR-CHAT-03 — destinatário no controle

O remetente não pode forçar uma conversa ativa. Ignorar não notifica o remetente com detalhes; bloquear e denunciar encerram o acesso conforme a política de segurança.

### BR-CHAT-04 — plano pago não remove segurança

Extra e Pro aumentam acesso pago, mas não removem bloqueio, denúncia, rate limit, moderação ou suspensão.

### BR-LOC-01 — privacidade geográfica

O backend usa região/índice espacial reduzido para descoberta. Latitude e longitude exatas, quando inevitáveis para uma operação curta, não são expostas ao usuário nem persistidas como atributo público.

### BR-DATA-01 — exclusão

Excluir conta remove ou anonimiza dados associados conforme a política de retenção documentada, incluindo perfil, fotos, conversas e entitlements, salvo retenções justificadas para segurança ou obrigação legal.

## 6. Critérios de aceitação do MVP

- **AC-ONB-01:** usuário menor de 18 anos não consegue concluir o onboarding adulto.
- **AC-ONB-02:** sem aceite dos termos, o perfil não fica público.
- **AC-DISC-01:** a grade carrega em páginas e permite continuar rolando sem recarregar os primeiros itens.
- **AC-DISC-02:** nenhuma tela mostra a distância exata ou coordenadas.
- **AC-CHAT-01:** tocar em Conversar permite escrever a primeira mensagem e mostra a quota antes do envio.
- **AC-CHAT-02:** ao enviar, o destinatário vê aceitar, ignorar, bloquear e denunciar.
- **AC-CHAT-03:** uma conversa ativa permite várias mensagens sem consumir novas aberturas.
- **AC-CHAT-04:** a sexta nova abertura no Free é bloqueada pelo servidor e oferece upgrade sem perder conversas existentes.
- **AC-SAFE-01:** bloquear remove o perfil/conversa da descoberta e impede novos contatos entre as contas.
- **AC-SAFE-02:** denunciar cria caso de moderação com motivo, evidência e estado auditável.
- **AC-BILL-01:** entitlement pago só é ativado após validação de compra no backend.
- **AC-DATA-01:** usuário encontra exclusão de conta dentro do app e por link externo.

## 7. Fora do MVP

Swipe, match obrigatório, chamadas, live, feed público, mapa com pinos, localização em segundo plano, IA de compatibilidade, perfis de casal completos, eventos, tradução automática e anúncios.

## 8. Contratos iniciais

O primeiro contrato de API deve cobrir:

- `POST /auth/session`
- `GET /discovery?cursor=...`
- `GET /profiles/{id}`
- `POST /chat-requests`
- `POST /chat-requests/{id}/accept`
- `POST /chat-requests/{id}/ignore`
- `POST /blocks`
- `POST /reports`
- `GET /conversations`
- `GET /conversations/{id}/messages`
- `POST /conversations/{id}/messages`
- `GET /entitlements`
- `POST /account/deletion-request`

Os nomes são provisórios; qualquer mudança deve atualizar esta spec e os cenários do harness.

## 9. Fronteira do protótipo Android local

Enquanto o backend ainda não estiver disponível, o app usa um repositório em memória com a mesma interface do futuro gateway remoto. Essa implementação existe somente para desenvolvimento e testes locais.

- A UI envia uma intenção de nova conversa e consome um resultado explícito: criada, já existente, bloqueada ou quota esgotada.
- O repositório fake aplica a quota de 5 aberturas e não permite que a tela altere o saldo diretamente.
- Os testes usam IDs sintéticos (`user-free`, `user-target-*`) e não simulam dados reais.
- A implementação de produção deverá substituir o fake por API autenticada com decremento atômico no servidor, sem alterar o contrato da UI.

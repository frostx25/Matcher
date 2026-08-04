# Matcher — especificação do MVP

Status: rascunho executável  
Versão: 1.2
Plataforma: Android  
Mercado inicial: Brasil, uma região metropolitana

## 1. Objetivo

Permitir que pessoas adultas descubram perfis próximos e iniciem conversas diretamente, sem swipe e sem match obrigatório, com limite gratuito de cinco novas conversas por janela móvel de 24 horas.

## 2. Atores

- **Usuário:** cria perfil, descobre pessoas, inicia conversas e controla sua visibilidade.
- **Destinatário:** recebe a primeira mensagem em uma conversa já ativa e pode responder, bloquear ou denunciar.
- **Destinatário de álbum:** recebe do titular uma autorização individual e revogável para abrir um álbum privado, podendo denunciar seu conteúdo.
- **Moderador:** revisa denúncias, conteúdo e contas sinalizadas.
- **Serviço de entitlement:** confirma o plano Free, Extra ou Pro no servidor.

- **Didit:** oferece, depois do onboarding e por iniciativa da pessoa na aba Perfil, um workflow opcional de documento brasileiro, prova de vida passiva e correspondência facial 1:1; o backend usa somente a decisão mínima necessária para conceder o selo 18+ verificado.

## 3. Escopo funcional

### 3.1 Conta e idade

- Cadastro por e-mail/OTP na primeira versão. No Android de desenvolvimento, o provedor envia um código numérico de seis dígitos; a pessoa pode consultar o e-mail em outro aparelho e o app valida automaticamente assim que o sexto dígito é informado. A validação confirma ou cria a conta e estabelece sua sessão autenticada.
- O código de autenticação é validado pelo Supabase Auth, possui expiração e nunca é registrado em logs, fixtures ou mensagens de erro.
- Solicitação, reenvio e validação do OTP são operações exclusivas no Android: enquanto uma delas estiver em andamento, ações repetidas não iniciam outra chamada ao provedor. O estado “código enviado” só aparece depois da confirmação do serviço.
- Se a solicitação expirar sem resposta, o app trata a entrega como indeterminada: não repete automaticamente o envio, mantém disponível a entrada do código que ainda possa chegar e explica que a pessoa deve conferir o e-mail antes de reenviar. Reenvios respeitam o intervalo local configurado e qualquer limite mais restritivo informado pelo provedor.
- O APK recebe apenas a URL do projeto e a chave publicável. Chave secreta, `service_role`, senha do banco e token de sessão não fazem parte da configuração do cliente.
- O onboarding básico exige autodeclaração de maioridade e aceite dos Termos de Uso/Política de Privacidade. Ele recebe somente o ano de nascimento, valida no servidor a declaração 18+ e registra a versão aceita dos documentos legais.
- O onboarding também apresenta identidade de gênero e preferência de descoberta como escolhas distintas. `gender_identity_ids` contém uma ou mais opções de um catálogo versionado, incluindo autodescrição e “prefiro não informar”; esta última é exclusiva. `looking_for_gender_ids` é uma seleção privada de uma ou mais opções do mesmo catálogo ou o valor exclusivo “todas as pessoas”.
- Autodescrição é texto fornecido pela pessoa e nunca é inferida. A identidade pode ser alterada no Perfil e possui controle de visibilidade; a preferência pode ser alterada no Perfil ou na Descoberta, não aparece para terceiros e nunca é incluída em payload público.
- Ao concluir o onboarding básico, a conta e o perfil ficam ativos e utilizáveis como **não verificados**. Descoberta e conversa não dependem do Didit, mas continuam sujeitas a suspensão, bloqueios, moderação, quota e às regras de visibilidade de conteúdo.
- Depois, na aba Perfil, a pessoa pode iniciar voluntariamente a verificação Didit. O workflow publicado aplica, na mesma sessão, autenticidade e idade mínima de 18 anos em documento brasileiro, prova de vida com método `PASSIVE` e correspondência 1:1 entre o retrato do documento e a captura ao vivo.
- Os três controles são obrigatórios apenas para conceder o selo **18+ verificado**. `In Review` preserva a mesma sessão corrente e impede criar ou reabrir outra enquanto houver revisão; recusa, erro, cancelamento, controle ausente, workflow ou versão divergente não concedem o selo, mas também não desativam nem ocultam automaticamente a conta já ativa.
- O backend cria a sessão hospedada somente com o workflow KYC publicado, envia como `vendor_data` um pseudônimo opaco estável por usuário e mantém uma referência opaca única de tentativa separada. A resposta de criação v3 pode omitir `session_kind`; por isso a notificação assinada é apenas um gatilho e o backend consulta a decisão autoritativa, onde exige `session_kind = user`, além de conferir ambiente, sessão, referências, `workflow_id`, `workflow_version` e todos os controles antes de marcar `over_18` e conceder o selo. Resultado, URL, deep link ou parâmetro informado pelo APK nunca concede verificação.
- O Matcher persiste apenas status normalizado, método/nível de garantia, instante da decisão, versão da política, workflow e referência técnica opaca. Respostas do Didit são filtradas por allowlist e não podem ser persistidas ou registradas em bruto; selfie, documento, nome civil, número documental, CPF, data de nascimento completa, idade extraída, URL de mídia, score ou template biométrico não são armazenados pelo app ou Supabase.
- A retenção de evidências no Didit fica configurada em um mês, o mínimo operacional adotado, sem cópia para o Matcher. Qualquer aumento exige revisão de privacidade e segurança.
- Na data desta especificação, o Didit informa 500 verificações gratuitas mensais para cada recurso central. O fluxo usa ID Verification, Passive Liveness e Face Match, então 500 fluxos completos é apenas um máximo teórico sem repetições; consumo real, franquia e preços devem ser reconfirmados no painel e nos termos vigentes, sem promessa de gratuidade futura.
- O selo resultante comunica **18+ verificado**, não identidade verificada nem idade exata verificada. O ano do perfil continua declarado enquanto não houver um método separado que confirme sua correspondência.
- Suspensão, exclusão e restrições de moderação prevalecem sobre qualquer resultado do Didit: uma decisão aprovada nunca reativa, republica ou libera uma conta moderada.
- Sessão revogável, recuperação de conta e logout de todos os dispositivos.
- Exclusão de conta no app e solicitação de exclusão por página web.

No protótipo local e remoto de desenvolvimento, o onboarding usa somente o ano de nascimento, confirmação explícita de 18+ e aceite separado dos Termos/Política. Depois da validação pelo servidor, o perfil fica ativo e utilizável sem selo; a verificação Didit é uma ação opcional posterior no Perfil. Como o ano isolado não informa se o aniversário já ocorreu, ele continua sendo um dado declarado e não é convertido em selo de verificação.

### 3.2 Perfil

- Nome de exibição, idade declarada a partir do ano de nascimento, bio e uma única foto pública de perfil.
- O perfil público de outra pessoa prioriza a foto autorizada, nome, idade, faixa de distância, intenção, bio e campos que ela decidiu publicar. Voltar e o menu de segurança permanecem sobre a área visual; `Conversar` e o acesso contextual ao álbum ficam em uma barra inferior persistente, sem exigir rolar até o fim da página.
- O menu de segurança reúne bloqueio e denúncia sem esconder essas ações em menus de assinatura. O botão de álbum fica desabilitado quando não existe acesso recebido nem álbum próprio disponível; quando habilitado, diferencia explicitamente abrir um álbum recebido de liberar ou revogar o próprio álbum.
- Identidade de gênero, pronomes, orientação, intenção e tipo de relacionamento como campos separados.
- Cada campo possui visibilidade configurável; o usuário pode preferir não informar.
- A foto pode ser qualquer imagem permitida pela política de conteúdo e não precisa representar um rosto. A mídia do Didit nunca vira foto de perfil.
- Cada versão de foto é privada enquanto estiver `pending`; terceiros recebem um placeholder cinza. Somente uma decisão `approved` torna aquela versão visível a terceiros. Decisões `adult` ou `abusive` mantêm a versão privada e o placeholder.
- A triagem da única foto pública usa exclusivamente o endpoint gratuito de moderação de imagens da OpenAI com `omni-moderation-latest`. `sexual` classifica a candidata como `adult`; `sexual/minors`, `violence` ou `violence/graphic` classificam como `abusive`; outra categoria sinalizada encaminha para revisão humana. Resposta incompleta, inválida ou indisponível mantém a mídia privada e entra em retry limitado.
- A decisão normalizada é autoritativa para a visibilidade da foto, mas não comprova idade nem identidade. Resposta bruta, scores, bytes, base64 e chave do provedor não são persistidos nem registrados em logs.
- Uma nova versão fica em moderação separada e não substitui a versão aprovada atual. A troca pública ocorre somente depois de a nova versão receber `approved`; se permanecer `pending` ou receber `adult`/`abusive`, a versão aprovada anterior continua visível.
- O upload da candidata aceita a pré-checagem real do Storage com `mimetype` e `contentLength`, revalida o metadado final `size` e limita a imagem a 5 MB; nenhuma dessas etapas cria um segundo espaço público de foto.
- A triagem automática cautelosa é aplicada somente à candidata da foto pública de perfil. Fotos de conversa e imagens do álbum privado nunca são encaminhadas ao classificador automático; continuam sujeitas à Política de Conteúdo, denúncia e remoção por moderação.

#### 3.2.1 Álbum privado

- Cada conta ativa pode possuir no máximo um álbum privado com até dez imagens. O álbum, seus metadados e suas imagens não aparecem na grade, na busca, no perfil público, nas fotos públicas nem em prévias de conversa.
- Imagens válidas ficam `available` imediatamente após o upload, sem fila de aprovação prévia. Este tratamento é distinto do versionamento moderado das fotos públicas de perfil e uma imagem privada nunca se torna pública automaticamente.
- “Sem aprovação prévia” não significa “sem regras”: antes do primeiro upload a pessoa aceita a Política de Conteúdo; menores, conteúdo não consensual, exploração, violência proibida, atividade ilegal e demais violações continuam proibidos. Destinatários podem denunciar e moderadores podem ocultar ou remover item, álbum ou conta.
- Somente o titular e uma conta ativa com concessão individual vigente podem listar metadados ou baixar uma imagem. A autorização é decidida no servidor em cada listagem/download; objetos não usam URL pública ou permanente e o cliente não mantém cópia em cache persistente.
- O titular pode conceder e revogar acesso por destinatário. A revogação impede imediatamente novas listagens e downloads e remove o conteúdo já carregado da interface; cópias já capturadas fora do controle do app não podem ser recuperadas.
- Bloquear uma conta revoga permanentemente todas as concessões de álbum entre as duas contas, em ambas as direções. Desbloquear não restaura acesso; somente uma nova concessão explícita pode fazê-lo.
- Concessão e bloqueio concorrentes são serializados pelo servidor. Uma concessão nunca pode ficar ativa por uma corrida com o bloqueio, e uma concessão encerrada por denúncia não pode ser reativada unilateralmente pelo titular enquanto o caso estiver preservado.
- Toda mutação de uma geração do álbum (reservar upload, conceder, revogar, denunciar ou excluir) carrega o `album_id` que a interface exibiu e o servidor o revalida. Uma requisição ou repetição atrasada da geração A nunca pode atuar sobre um álbum substituto B.
- Denunciar um item ou álbum abre um caso de moderação auditável, oculta o álbum para quem denunciou e encerra sua concessão. A evidência preservada segue retenção mínima e acesso restrito; conteúdo sensível não é escrito em logs.
- Excluir uma imagem, o álbum ou a conta remove metadados, concessões e objetos de Storage de modo idempotente. A exclusão do álbum é vinculada ao `album_id` exibido ao usuário; uma repetição tardia nunca pode atingir um álbum substituto. Falha parcial entra em rotina de limpeza sem tornar o objeto acessível.
- Upload e exclusão concorrentes são serializados pelo caminho reservado. A limpeza usa lease/backoff, não deixa um item com falha monopolizar a fila e respeita retenção restrita de evidência de denúncia.
- Antes de servir bytes, o backend confere formato, tamanho e limites seguros de dimensões/pixels. O Android faz decode amostrado; metadata ou extensão declarada pelo cliente não basta para considerar a imagem segura.
- O upload usa uma reserva imutável antes de chamar o Storage. A policy de transporte aceita somente o caminho reservado, o titular autenticado e os metadados de pré-checagem produzidos pelo próprio Storage (`mimetype` e `contentLength`); a finalização continua exigindo os metadados definitivos (`mimetype` e `size`). Qualquer `SELECT` necessário para o retorno do upload fica restrito à operação `storage.object.upload` e não autoriza listar, baixar, assinar ou consultar objetos do álbum.
- A tentativa gera no cliente uma chave UUID opaca antes da primeira reserva e a reutiliza em qualquer retry da mesma intenção. Se a resposta da reserva ficar indeterminada por falha de transporte, resposta truncada ou erro transitório do servidor, o Android repete somente essa reserva uma vez, com a mesma chave. Depois que o Storage conclui, uma resposta igualmente indeterminada da finalização repete somente a RPC idempotente uma vez, com o mesmo `item_id`; o byte não é reenviado. Rejeição de negócio e cancelamento não são repetidos. O servidor atribui uma lease imutável de 30 minutos: a mesma chave e o mesmo MIME retornam a mesma reserva ou item já concluído, enquanto payload divergente é recusado. Se o adaptador receber uma resposta idempotente `available` sem `object_path`, ela é tratada defensivamente como sucesso e não chama Storage, finalização ou cleanup; o cliente não promete preservar a chave depois que a chamada termina. Depois do prazo, tanto um novo `INSERT` quanto uma finalização tardia são negados; o item vira `deleting`, libera sua posição e entra uma única vez na fila privada de limpeza. O poll normal do worker executa esse reaper de forma limitada antes de conceder seus leases, sem depender de cron externo, e somente `service_role` pode acionar o reaper global ou receber caminhos da fila.
- Depois que uma reserva já é conhecida pelo cliente, cancelamento, saída da tela ou falha antes da finalização ainda executam uma tentativa de limpeza curta, limitada e não cancelável antes de propagar o cancelamento. Se a resposta da própria reserva se perder e o cliente nunca receber seu ID, o TTL e o reaper do servidor são a recuperação autoritativa; o cliente não inventa caminho nem confirma limpeza.
- A função que entrega a mídia privada aceita os JWTs de usuário emitidos pela chave de assinatura atual do projeto e revalida conta, moderação, bloqueio e concessão pela RPC autoritativa `authorize_private_album_item`, executada como `SECURITY DEFINER` sob a identidade do JWT, antes de usar o cliente privilegiado somente para ler o objeto. Token ou sessão comprovadamente inválidos retornam `401`; indisponibilidade, timeout ou falha operacional do Auth retornam `503`, sem detalhes do provedor. Depois que o item foi finalizado, uma falha ao atualizar a prévia nunca é apresentada como falha de upload nem incentiva um segundo envio da mesma foto; o item permanece no álbum e a interface oferece nova tentativa de carregamento.
- O aviso recuperável de “foto adicionada, prévia indisponível” vale somente para falha transitória ao buscar a prévia de um item que continua autorizado e consistente. Falha de autenticação/autorização, resposta incompatível, ID/posição divergente ou outra quebra de invariante após a finalização não pode cair nesse aviso: bytes privados e estado derivado são descartados imediatamente, a falha é tratada explicitamente e nenhuma nova cópia é enviada automaticamente.
- Antes de abrir o álbum, o destinatário vê uma tela bloqueada e um aviso de conteúdo privado/capturas. O app não promete impedir screenshots ou fotografias feitas com outro aparelho.
- Esta versão aceita somente imagens. Vídeo, concessões com expiração e visualização única ficam fora do MVP.

### 3.3 Descoberta

- Grade paginada de perfis próximos.
- No Android, a tela inicial apresenta a descoberta em uma grade densa de três colunas no telefone em modo retrato e aumenta progressivamente a quantidade de colunas em telas largas ou no modo paisagem. O topo mantém acesso direto ao próprio perfil, contexto de localização aproximada, quota de novas conversas e filtros; cada cartão mostra somente nome, idade, faixa de distância e intenção sobre a miniatura pública autorizada.
- Ordenação por proximidade aproximada, atividade recente e compatibilidade com preferências declaradas.
- Filtros básicos por idade, identidade, intenção, tipo de relacionamento e verificação.
- A preferência `looking_for_gender_ids` é privada e aplicada pelo servidor antes da paginação. “Todas as pessoas” não filtra por gênero; uma seleção específica inclui somente perfis cuja identidade publicada possua interseção com a seleção.
- Se a identidade de um perfil estiver oculta ou marcada como “prefiro não informar”, uma preferência específica não pode usá-la para incluir esse perfil, evitando revelar indiretamente um dado oculto. A preferência “todas as pessoas” pode incluir o perfil sem expor identidade.
- Perfis criados antes desta versão recebem, sem inferência, identidade “prefiro não informar” oculta e preferência privada “todas as pessoas”. Nome, bio, foto, conversa e verificação nunca são usados para deduzir gênero.
- Alterar a preferência invalida a página/cursor anterior e a próxima consulta reinicia a descoberta com a decisão atual do servidor. O cliente não filtra um conjunto mais amplo como substituto da regra autoritativa.
- `authenticated` não possui leitura ampla de `public.profiles`; perfil próprio e descoberta são servidos por RPCs contextuais. Ao detectar cursor obsoleto, o cliente substitui a lista pela nova primeira página, sem mesclar resultados anteriores.
- Distância mostrada em faixas, nunca em metros exatos.
- Funciona com localização escolhida por região e com localização aproximada do Android.
- Pausar descoberta, ocultar distância e não aparecer em exploração.

### 3.4 Conversa direta

- O botão principal do perfil é **Conversar**.
- O primeiro envio cria uma conversa ativa e sua primeira mensagem na mesma transação, consumindo uma abertura.
- Não existe solicitação pendente, aceite prévio ou match obrigatório.
- O destinatário pode responder, bloquear ou denunciar desde a primeira mensagem.
- Bloquear interrompe novas mensagens e remove a visibilidade entre as contas; denunciar também cria um caso de moderação auditável.
- A conversa ativa mantém voltar, identidade pública autorizada e menu de segurança no cabeçalho. Bloquear e denunciar ficam agrupados nesse menu e nunca dependem de assinatura.
- O atalho contextual de álbum diferencia abrir o álbum recebido de liberar ou revogar o próprio álbum. Sem nenhuma ação disponível, o atalho fica desabilitado; a conversa nunca mostra miniatura ou imagem privada antes do aviso e da revalidação autoritativa.
- O campo de mensagem e o envio permanecem fixos e utilizáveis com o teclado aberto. Mensagens vazias não são enviadas, uma falha preserva o texto digitado e somente a confirmação do repositório limpa o campo.
- A lista de conversas mostra somente pares ativos, com mídia pública autorizada, nome, faixa aproximada e última mensagem truncada; quando vazia, orienta a voltar para `Perto` sem inventar solicitações, matches ou contatos sugeridos.
- Antes da primeira mensagem, a pessoa vê o perfil destinatário, a quota restante e a consequência direta do envio. O botão principal ocupa a largura disponível, permanece desabilitado para texto vazio e usa o mesmo vocabulário do chat que será aberto.
- Texto e fotos são suportados; mídia efêmera e chamadas ficam fora do MVP.
- O botão de mídia do compositor abre duas intenções distintas: `Selecionar foto`, para enviar uma imagem somente nesta conversa, e `Liberar meu álbum`/`Revogar meu álbum`, para controlar o acesso ao álbum privado inteiro. Abrir um álbum recebido continua sendo uma terceira ação contextual e nunca é confundido com o envio de uma foto.
- Cada tentativa de foto recebe no cliente uma chave UUID opaca reutilizada em retry. O servidor associa essa chave, o remetente e a conversa a no máximo uma mensagem, valida o caminho privado reservado e não permite que repetições criem mensagens ou objetos duplicados.
- Fotos de conversa válidas ficam disponíveis imediatamente aos dois participantes ativos, sem triagem automática. Bloqueio, suspensão, denúncia ou decisão posterior de moderação impedem novas leituras.
- Mensagens apresentam estados locais `sending` e `failed` e estados autoritativos `sent`, `delivered` e `read`. Falha preserva a intenção e oferece reenvio manual com a mesma chave; o app nunca repete automaticamente uma foto inteira depois de resposta indeterminada.
- Abrir a conversa marca como lidas somente as mensagens recebidas daquela conversa. A lista mostra contagem de não lidas calculada pelo servidor; Realtime apenas invalida os dados e não concede acesso.
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
- Silenciar é uma preferência por participante confirmada pelo servidor. A notificação de mensagem nunca inclui texto, imagem, URL de mídia, nome de objeto privado ou detalhes de denúncia; o padrão é uma prévia neutra (`Nova mensagem`).
- O backend registra uma entrega pendente em outbox apenas quando o destinatário não silenciou a conversa. O envio efetivo por FCM depende de credenciais de infraestrutura fora do APK e uma falha de push nunca altera o estado ou a visibilidade da mensagem.
- Mensagem, foto de conversa, perfil e álbum privado podem ser denunciados separadamente. A denúncia referencia IDs opacos, preserva evidência com acesso restrito e oculta imediatamente o conteúdo denunciado para o denunciante.
- Limites por usuário, dispositivo, IP, perfil destinatário e intervalo de tempo.
- Detecção de spam, mensagens repetitivas, criação em massa e evasão de banimento.
- Painel de moderação com fila, evidência, histórico e auditoria.
- Políticas de acesso testadas para metadados e objetos do álbum privado, incluindo tentativa por usuário sem concessão, concessão revogada, bloqueio, suspensão e exclusão.
- Nenhum log, evento analítico ou notificação contém preferência de gênero, nome de objeto privado, imagem, URL de mídia ou texto livre de denúncia.

## 4. Requisitos não funcionais

- Android nativo com Kotlin e Jetpack Compose.
- UI base preta/rosa, componentes arredondados e suporte a textos ampliados.
- Primeira tela interativa: meta de até 2,5 s em aparelho Android intermediário.
- Primeiro conjunto de perfis: meta de até 1,5 s após resposta da API.
- Grade sem retornar todos os perfis; usar paginação por cursor e índice geográfico.
- Imagens com miniaturas, cache e carregamento sob demanda.
- Fotos públicas podem usar cache conforme seu estado de moderação; imagens de álbum privado são buscadas sob autenticação, não usam cache persistente e são descartadas da interface quando o acesso deixa de ser válido.
- Mensagens com confirmação visual imediata e confirmação do servidor em até 500 ms em rede normal.
- HTTPS em todas as comunicações e controle de acesso por função.
- Não registrar no log conteúdo de mensagens, coordenadas exatas, documentos de identidade ou orientação sexual.

## 5. Regras de negócio críticas

### BR-CHAT-01 — nova abertura

Uma nova conversa só pode ser criada se a conta estiver ativa, o destinatário não estiver bloqueado e a quota do remetente for maior que zero.

### BR-CHAT-02 — consumo atômico

Criar a conversa, registrar sua primeira mensagem e consumir uma abertura devem ocorrer na mesma transação. Duas requisições simultâneas não podem gastar uma única abertura duas vezes nem ultrapassar o limite.

### BR-CHAT-03 — contato direto com proteção

A primeira mensagem torna a conversa ativa sem aceite prévio. O destinatário pode bloquear ou denunciar a qualquer momento; bloquear encerra o acesso nos dois sentidos e denunciar cria um caso de moderação sem expor detalhes internos ao remetente.

### BR-CHAT-04 — plano pago não remove segurança

Extra e Pro aumentam acesso pago, mas não removem bloqueio, denúncia, rate limit, moderação ou suspensão.

### BR-CHAT-05 — foto privada e idempotente

Uma foto de conversa é um recurso separado do álbum. Reserva, upload e finalização usam caminho privado vinculado ao remetente, conversa e chave idempotente. A mídia válida fica disponível imediatamente aos participantes ativos, sem classificação automática; bloquear, suspender, remover ou denunciar revoga a leitura imediatamente.

### BR-CHAT-06 — leitura, silêncio e notificação privada

O servidor registra leitura por participante e deriva não lidas sem confiar no contador do cliente. Silenciar impede novas entradas de push para aquela pessoa, sem impedir a mensagem. Payloads de push são neutros e nunca carregam conteúdo sensível.

### BR-CHAT-07 — entrega push privada e recuperável

IDs de instalação Firebase (FID) pertencem a uma instalação autenticada, ficam em schema privado e nunca aparecem em resposta pública, log ou analytics. O worker usa lease, retry limitado e a API HTTP v1; instalação recusada permanentemente é desativada. O Android mostra somente `Matcher` e `Nova mensagem`, e tocar na notificação abre o app sem transportar texto, foto, nome de objeto ou identidade da outra pessoa.

### BR-CHAT-08 — limite da triagem automática

O worker de moderação reivindica exclusivamente candidatas do bucket privado `profile-photos` e envia seus bytes, somente em memória e sem URL pública, ao endpoint `/v1/moderations` da OpenAI com `omni-moderation-latest`. Fotos de conversa e imagens do álbum privado não são enviadas ao provedor nem dependem de aprovação automática para ficarem disponíveis aos participantes autorizados. A classificação não concede selo 18+; denúncia, bloqueio, suspensão e moderação posterior continuam autoritativos.

### BR-LOC-01 — privacidade geográfica

O backend usa região/índice espacial reduzido para descoberta. Latitude e longitude exatas, quando inevitáveis para uma operação curta, não são expostas ao usuário nem persistidas como atributo público.

### BR-DATA-01 — exclusão

Excluir conta torna a conta indisponível imediatamente, remove o perfil da descoberta, encerra conversas e revoga concessões. A remoção ou anonimização física posterior é idempotente e segue a política de retenção documentada para perfil, fotos, mensagens e entitlements, salvo retenções justificadas para segurança ou obrigação legal.

### BR-AGE-01 — ativação pelo onboarding básico

Depois que o e-mail confirma ou cria a conta, o backend ativa o perfil para uso ao validar ano de nascimento compatível, autodeclaração 18+ e aceite da versão vigente dos Termos/Política. A ausência de verificação Didit não bloqueia descoberta nem conversa e deve ser representada apenas como ausência do selo.

### BR-AGE-02 — selo opcional e minimização

O backend só concede **18+ verificado** após decisão final `Approved` do workflow Didit publicado e configurado, com documento brasileiro, regra de idade mínima de 18 anos, prova de vida aprovada com `method = PASSIVE` e correspondência facial aprovados. O Matcher não persiste nem registra identificador direto ou PII retornada pelo Didit, documento, mídia, score ou biometria; a retenção de evidências fica limitada a um mês no Didit. `In Review` mantém revisão pendente na sessão corrente e bloqueia outra sessão; demais resultados deixam a conta ativa e sem selo.

### BR-AGE-03 — autoridade, ambiente e orçamento

Webhook, callback e deep link nunca são prova de verificação. O selo exige sessão de tipo `user`, `vendor_data` pseudônimo estável por usuário, referência única de tentativa separada, consulta servidor-servidor, vínculo da sessão à conta, conferência exata de ambiente e versão do workflow e finalização idempotente. Sandbox nunca concede selo live. A franquia divulgada de 500 verificações mensais por recurso central é monitorada e reconfirmada periodicamente; não é garantia permanente nem autoriza contratação automática de excedente. Falta de capacidade afeta apenas o início da verificação opcional.

### BR-AGE-04 — moderação prevalece

Uma aprovação Didit altera somente o estado do selo. Conta suspensa, excluída ou limitada pela moderação permanece nessa condição, e o selo não pode reativar acesso, republicar perfil nem contornar uma restrição.

### BR-PHOTO-01 — visibilidade por versão

O perfil possui um único espaço de foto pública. Cada versão candidata possui decisão própria: `pending`, `adult` e `abusive` permanecem privadas e são representadas a terceiros por placeholder cinza; somente `approved` pode ocupar esse espaço e ser entregue a terceiros. A triagem automática cautelosa atua somente nessa candidata.

### BR-PHOTO-02 — substituição segura

Ao enviar uma nova versão, a versão aprovada atual permanece pública até a nova versão também receber `approved`. Estado pendente ou decisão `adult`/`abusive` da nova versão não remove nem substitui a imagem aprovada anterior, salvo uma ação de moderação independente sobre a versão anterior ou sobre a conta.

### BR-GENDER-01 — identidade separada da preferência

Identidade de gênero pertence ao perfil, pode conter múltiplas opções/autodescrição e possui visibilidade controlada. A preferência “quem quero encontrar” é uma seleção privada independente, não é retornada a terceiros e não pode ser inferida ou preenchida a partir de qualquer conteúdo do perfil.

### BR-GENDER-02 — descoberta autoritativa e sem inferência

O servidor aplica a preferência de gênero antes da paginação. Uma seleção específica combina por interseção apenas com identidades publicadas; identidade oculta ou “prefiro não informar” não participa dessa filtragem. “Todas as pessoas” não filtra por gênero. Perfis legados recebem identidade oculta “prefiro não informar” e preferência “todas as pessoas”, sem inferência.

### BR-ALBUM-01 — privado por padrão

Existe no máximo um álbum privado por titular, com até dez imagens. Álbum, itens e objetos não são públicos nem aparecem em descoberta, perfil público ou conversa; somente titular ou destinatário com concessão individual vigente pode listar ou ler seu conteúdo.

### BR-ALBUM-02 — disponibilidade sem aprovação prévia

Uma imagem válida fica disponível no álbum imediatamente, sem estado `pending` ou aprovação prévia. Ela continua sujeita à Política de Conteúdo, denúncia e decisão de moderação, que pode ocultar/remover item, álbum ou conta. O conteúdo não pode ser promovido automaticamente a foto pública.

### BR-ALBUM-03 — autorização, revogação e bloqueio

Toda listagem ou leitura revalida no servidor a conta, o bloqueio e a concessão atuais. Revogar encerra o acesso individual; bloquear revoga permanentemente concessões nos dois sentidos, e desbloquear não as recria. Objetos não possuem URL pública ou permanente.

### BR-ALBUM-04 — denúncia, exclusão e captura

Denunciar oculta o álbum para o denunciante, encerra sua concessão e cria caso auditável de moderação. Excluir item, álbum ou conta limpa metadados, concessões e Storage de modo idempotente. A interface alerta que capturas externas não podem ser impedidas e nunca promete recuperar cópias já obtidas.

### BR-ALBUM-05 — limite desta versão

O limite de um álbum e dez imagens é imposto atomicamente no servidor, inclusive sob uploads concorrentes. Vídeo, acesso com expiração e visualização única não fazem parte desta versão.

### BR-ALBUM-06 — reserva idempotente e recuperação autoritativa

Cada intenção de upload possui uma chave idempotente opaca e uma lease de 30 minutos definida pelo servidor. Retry com a mesma chave não consome outra posição; reserva expirada não aceita upload nem finalização e é tombstonada de forma idempotente na fila privada. O reaper é limitado, usa os mesmos locks do caminho reservado e entrega a remoção física somente ao worker `service_role` com lease/backoff. Cancelamento após conhecer a reserva ainda tenta a limpeza em contexto curto e não cancelável; perder a resposta antes de conhecer o ID é recuperado pelo TTL do servidor.

### BR-ALBUM-07 — gestão explícita de acessos

Concessões vigentes aparecem em uma tela própria de compartilhamento. O titular pode selecionar uma ou mais pessoas e confirmar a revogação em lote; cada destinatário não selecionado mantém seu acesso. A interface limpa a seleção e recarrega a lista autoritativa após a operação, inclusive se uma revogação intermediária falhar. Conceder acesso continua sendo uma ação individual e explícita.

## 6. Critérios de aceitação do MVP

- **AC-ONB-01:** usuário menor de 18 anos não consegue concluir o onboarding adulto.
- **AC-ONB-02:** sem aceite dos termos, o onboarding não ativa o perfil.
- **AC-ONB-03:** ano de nascimento incompatível com 18+ bloqueia o avanço e não salva perfil.
- **AC-ONB-04:** com maioridade declarada, termos aceitos, nome, identidade de gênero ou “prefiro não informar” e preferência de descoberta preenchidos, o perfil fica ativo e utilizável como não verificado, inclusive para descoberta e conversa quando nenhuma restrição independente se aplica.
- **AC-GENDER-01:** identidade e preferência aparecem como controles separados no onboarding e podem ser alteradas depois; a preferência aceita múltiplas opções ou “todas as pessoas”, que é exclusiva.
- **AC-GENDER-02:** nenhum perfil, resposta pública, log ou telemetria revela a preferência de gênero de outra pessoa.
- **AC-AGE-01:** a pessoa pode iniciar a verificação Didit opcional depois na aba Perfil; não iniciá-la não reduz o acesso de uma conta ativa.
- **AC-AGE-02:** somente a decisão final do Didit confirmada pelo backend, em sessão `user` do workflow publicado configurado, com documento brasileiro 18+, prova de vida aprovada com método `PASSIVE` e correspondência facial aprovada, concede o selo **18+ verificado**.
- **AC-AGE-03:** `In Review` mantém revisão pendente e a sessão corrente, sem criar ou reabrir outra sessão; controle ausente, inconclusivo ou recusado não concede aprovação parcial nem desativa a conta.
- **AC-AGE-04:** forjar no Android um resultado, callback ou URL de sucesso não concede selo nem altera o acesso da conta.
- **AC-AGE-05:** tabelas, logs e auditoria do Matcher não contêm payload bruto, nome civil, número documental, selfie, documento, data completa, idade extraída, URL de mídia, score ou template biométrico; o Didit retém as evidências por um mês.
- **AC-AGE-06:** sessão de outro ambiente, workflow ou versão não concede selo; indisponibilidade de capacidade bloqueia somente novas sessões Didit e mantém a conta ativa como não verificada.
- **AC-AGE-07:** tentativas do mesmo usuário usam o mesmo `vendor_data` pseudônimo, usuários diferentes usam valores distintos e cada tentativa possui referência única separada; nenhum valor contém identificador direto.
- **AC-AGE-08:** aprovação atrasada nunca reativa uma conta suspensa, excluída ou limitada pela moderação.
- **AC-PHOTO-01:** qualquer imagem permitida pode ser enviada; enquanto sua versão está `pending`, ou após decisão `adult`/`abusive`, somente o titular pode acessar a mídia e terceiros recebem placeholder cinza.
- **AC-PHOTO-02:** somente a versão `approved` é visível a terceiros, sem depender de o arquivo mostrar um rosto.
- **AC-PHOTO-03:** uma nova versão pendente ou não aprovada não substitui a versão aprovada atual; a troca só ocorre quando a nova versão também é aprovada.
- **AC-PHOTO-04:** existe somente uma foto pública de perfil por pessoa; a triagem automática reivindica apenas sua candidata e nunca fotos de conversa ou do álbum privado.
- **AC-PROFILE-01:** ao abrir um perfil público, a pessoa encontra `Conversar` sem rolar, volta pelo topo e acessa bloqueio/denúncia no menu de segurança; a tela exibe somente mídia pública autorizada, campos publicados e faixa de distância aproximada.
- **AC-PROFILE-02:** o botão contextual `Álbum` permanece junto da ação de conversa, fica desabilitado sem ação disponível e, quando habilitado, separa abrir álbum recebido de liberar/revogar o álbum próprio sem mostrar miniatura privada.
- **AC-ALBUM-01:** titular cria somente um álbum, envia até dez imagens e consegue abri-las imediatamente sem aprovação prévia; a décima primeira é recusada pelo servidor mesmo sob concorrência.
- **AC-ALBUM-02:** conta sem concessão não lista metadados nem lê bytes; conceder acesso a uma pessoa não libera para nenhuma outra.
- **AC-ALBUM-03:** revogar acesso impede novas listagens/downloads e remove o conteúdo da tela do destinatário, sem afetar concessões de outras pessoas.
- **AC-ALBUM-04:** bloquear revoga concessões nos dois sentidos e desbloquear não restaura nenhuma delas.
- **AC-ALBUM-05:** denunciar encerra o acesso do denunciante, oculta o conteúdo para ele e cria caso de moderação; uma remoção por moderador impede leitura por titular e destinatários conforme a decisão.
- **AC-ALBUM-06:** excluir item, álbum ou conta não deixa objeto legível nem concessão órfã; repetição da limpeza produz o mesmo resultado.
- **AC-ALBUM-07:** álbum e miniaturas não aparecem em descoberta, perfil público ou conversa; uma imagem privada nunca vira foto pública automaticamente.
- **AC-ALBUM-08:** antes de abrir, o destinatário vê aviso sobre conteúdo privado e possibilidade de captura; o fluxo não oferece vídeo, expiração nem visualização única.
- **AC-ALBUM-09:** depois de uma reserva válida, o upload real do Storage aceita a pré-checagem com `mimetype` e `contentLength`, conclui com `mimetype` e `size` e torna o item `available`; a permissão de retorno do upload não permite listar, baixar diretamente, assinar ou consultar o objeto por nenhuma outra operação.
- **AC-ALBUM-10:** um JWT autenticado válido consegue baixar a mídia permitida somente pela Edge Function após a RPC `SECURITY DEFINER` revalidar a autorização com aquele mesmo JWT; credencial comprovadamente inválida recebe `401`, enquanto falha operacional ou transitória na verificação de identidade recebe `503` e não executa a RPC. Se o upload já foi finalizado e apenas a atualização da prévia falhar, o app mantém o novo item, informa que a foto foi adicionada e não cria uma duplicata automaticamente.
- **AC-ALBUM-11:** duas reservas concorrentes ou repetidas com o mesmo `idempotency_key`, álbum e MIME retornam o mesmo item e consomem uma posição; o Android reutiliza essa chave no único retry automático de uma resposta indeterminada. Depois do upload, uma finalização com resposta indeterminada é repetida uma vez com o mesmo `item_id`, sem repetir Storage. Uma resposta defensiva `available` com `object_path = null` encerra a chamada com sucesso, sem novo upload, finalização ou cleanup; reutilizar a chave com payload divergente é recusado. Após 30 minutos, Storage e finalização recusam a reserva, a posição é liberada e polls repetidos criam/consomem um único tombstone com lease, sem expor caminho a `authenticated`.
- **AC-ALBUM-12:** a tela de compartilhamento lista concessões vigentes, permite selecionar múltiplas pessoas e só revoga as selecionadas após confirmação; a lista é revalidada no servidor depois da tentativa.
- **AC-ALBUM-12:** se a operação for cancelada depois que o ID da reserva ficou conhecido, o cliente executa cleanup limitado em contexto não cancelável e só então propaga o cancelamento. Se a resposta da reserva nunca chegou, uma execução posterior do worker remove a reserva pelo TTL sem depender do cliente ou de cron externo pago.
- **AC-ALBUM-13:** após finalizar, somente uma falha transitória de prévia para item ainda autorizado usa o estado recuperável sem reupload. Falha de acesso, resposta divergente ou quebra de invariante descarta bytes/estado privado, não exibe o aviso simples de prévia e não inicia outro upload automaticamente.
- **AC-AUTH-01:** informar e-mail e um código OTP válido cria a sessão no app; código inválido ou expirado não autentica.
- **AC-AUTH-02:** o app aceita somente seis dígitos no campo OTP, inicia a validação automaticamente ao receber o sexto dígito e não persiste nem registra o código informado.
- **AC-AUTH-03:** dois toques ou callbacks imediatos de envio, reenvio ou validação geram no máximo uma chamada ao provedor enquanto a operação estiver pendente; os controles de autenticação permanecem desabilitados até sua conclusão.
- **AC-AUTH-04:** após envio confirmado, timeout de entrega indeterminada ou limite remoto, o reenvio respeita o cooldown configurado. Timeout nunca dispara retry automático nem impede validar um código que chegue, e rate limit/timeout são explicados sem serem apresentados como falha genérica de conexão.
- **AC-DISC-01:** a grade carrega em páginas e permite continuar rolando sem recarregar os primeiros itens.
- **AC-DISC-02:** nenhuma tela mostra a distância exata ou coordenadas.
- **AC-DISC-03:** preferência específica retorna, antes da paginação, somente perfis com identidade publicada compatível; alterar a preferência invalida o cursor anterior.
- **AC-DISC-04:** identidade oculta ou “prefiro não informar” não é inferida por uma preferência específica; “todas as pessoas” preserva a descoberta sem expor o campo.
- **AC-DISC-05:** perfil legado continua utilizável com identidade oculta “prefiro não informar” e preferência privada “todas as pessoas”, sem classificação automática.
- **AC-DISC-06:** a tela inicial Android exibe três cartões por linha no telefone em modo retrato e usa mais colunas conforme a largura disponível, mantendo filtros e quota fora dos cartões e abrindo o próprio Perfil pelo avatar do topo; nenhum cartão revela distância exata, preferência privada ou imagem não aprovada.
- **AC-CHAT-01:** tocar em Conversar permite escrever a primeira mensagem e mostra a quota antes do envio.
- **AC-CHAT-02:** ao enviar a primeira mensagem, a conversa fica ativa para as duas contas sem aceite prévio, com bloquear e denunciar disponíveis.
- **AC-CHAT-03:** uma conversa ativa permite várias mensagens sem consumir novas aberturas.
- **AC-CHAT-04:** a sexta nova abertura no Free é bloqueada pelo servidor e oferece upgrade sem perder conversas existentes.
- **AC-CHAT-05:** a conversa ativa mantém identidade pública, voltar, álbum contextual e segurança acessíveis sem rolar; abrir álbum recebido e liberar/revogar o álbum próprio são operações separadas e nenhuma miniatura privada aparece no chat.
- **AC-CHAT-06:** bloquear e denunciar ficam disponíveis no menu de segurança; o compositor acompanha o teclado, recusa texto vazio e só limpa uma mensagem depois de o repositório confirmar o envio.
- **AC-CHAT-07:** a lista exibe apenas conversas ativas e, quando vazia, oferece voltar à descoberta; o diálogo da primeira mensagem identifica o destinatário, mostra a quota, explica que o envio abre a conversa sem aceite e mantém o botão principal desabilitado para texto vazio.
- **AC-CHAT-08:** o compositor oferece `Selecionar foto` e liberar/revogar o álbum como ações diferentes; selecionar foto não cria concessão de álbum e liberar o álbum não envia nem revela miniatura privada no histórico.
- **AC-CHAT-09:** repetir a mesma chave de envio de foto produz uma única mensagem; a mídia válida fica disponível imediatamente aos participantes ativos e não bloqueados, sem triagem automática.
- **AC-CHAT-10:** o remetente distingue `enviando`, `enviada`, `entregue`, `lida` e `falhou`; uma falha permite retry manual sem duplicar a mensagem.
- **AC-CHAT-11:** abrir uma conversa zera somente suas não lidas; silenciar impede a criação de novas entregas push para o participante, mas não impede novas mensagens ou a atualização por Realtime.
- **AC-CHAT-12:** a notificação usa texto neutro e não inclui corpo de mensagem, bytes, URL, caminho de Storage ou detalhes livres; denunciar mensagem ou foto cria um caso referenciando somente IDs autorizados.
- **AC-CHAT-13:** uma instalação autenticada registra/rotaciona seu FID sem expô-lo; o worker entrega cada outbox sob lease, desativa instalação permanentemente inválida, repete somente falha transitória e respeita o silenciamento decidido antes da criação da outbox.
- **AC-CHAT-14:** o worker de visão não reivindica nem baixa fotos de conversa ou do álbum privado; somente a candidata da foto pública de perfil pode entrar na triagem automática.
- **AC-SAFE-01:** bloquear remove o perfil/conversa da descoberta e impede novos contatos entre as contas.
- **AC-SAFE-02:** denunciar cria caso de moderação com motivo, evidência e estado auditável.
- **AC-SAFE-03:** suspender qualquer participante ou titular encerra acesso ao álbum privado sem depender do estado no cliente.
- **AC-BILL-01:** entitlement pago só é ativado após validação de compra no backend.
- **AC-DATA-01:** o usuário encontra a exclusão dentro do app; ao confirmar, a conta deixa de autenticar/agir imediatamente, sai da descoberta, fecha conversas e revoga acessos, enquanto a limpeza física segue uma fila privada idempotente e retenções justificadas.

## 7. Fora do MVP

Swipe, match obrigatório, chamadas, live, feed público, mapa com pinos, localização em segundo plano, IA de compatibilidade, perfis de casal completos, eventos, tradução automática, anúncios, vídeo em álbum privado, concessão com expiração e visualização única.

## 8. Contratos iniciais

O primeiro contrato de API deve cobrir:

- `POST /auth/session`
- `POST /onboarding` (valida autodeclaração 18+, termos, identidade de gênero e preferência privada e ativa o perfil como não verificado)
- `POST /age-verification/sessions` (opcional, iniciado no Perfil; cria uma sessão Didit `user`, com pseudônimo estável e referência única de tentativa separada)
- `GET /age-verification/status`
- `POST /age-verification/provider-callback` (webhook Didit servidor-servidor; a notificação é apenas gatilho e nunca concede selo pelo APK)
- `POST /profile/photos` (cria uma nova versão privada em `pending`)
- `GET /profiles/{id}/photos` (entrega mídia somente para versões `approved`; nos demais estados entrega placeholder a terceiros)
- `PATCH /profile/gender` (altera identidade/autodescrição e sua visibilidade)
- `PUT /profile/discovery-preferences` (substitui a preferência privada multi-seleção)
- `POST /profile/private-album` (cria ou retorna o único álbum do titular)
- `GET /profile/private-album` (lista o próprio álbum, itens e concessões vigentes)
- `POST /profile/private-album/images` e `DELETE /profile/private-album/images/{id}`
- `PUT /profile/private-album/grants/{recipient_id}` e `DELETE /profile/private-album/grants/{recipient_id}`
- `GET /profiles/{owner_id}/private-album` (lista/baixa somente com concessão vigente)
- `POST /profiles/{owner_id}/private-album/reports`
- `GET /discovery?cursor=...`
- `GET /profiles/{id}`
- `POST /conversations` (cria conversa ativa com a primeira mensagem)
- `POST /blocks`
- `POST /reports`
- `GET /conversations`
- `GET /conversations/{id}/messages`
- `POST /conversations/{id}/messages`
- `GET /entitlements`
- `POST /account/deletion-request`

Os nomes são provisórios; qualquer mudança deve atualizar esta spec e os cenários do harness.

Na fundação Supabase, `POST /conversations` é implementado pela RPC `start_conversation(recipient_id, first_message)`. Envio, bloqueio, denúncia e consulta de quota usam respectivamente `send_message`, `block_user`, `report_user` e `get_chat_quota`; escrita direta nas tabelas críticas não faz parte do contrato do cliente.

`POST /onboarding` é implementado pela RPC `complete_onboarding`, que recebe ano, nome, bio, intenção, região, consentimento, `gender_identity_ids`, autodescrição/visibilidade e `looking_for_gender_ids`. A função valida sessão, ano de nascimento, maioridade declarada, consentimento e o catálogo versionado, ativa o perfil como não verificado e grava a preferência em área privada. A migração de perfis existentes usa “prefiro não informar” oculto e “todas as pessoas”; nunca infere valores. A leitura da descoberta usa uma RPC paginada que aplica a preferência atual no servidor e não retorna essa preferência.

As operações de álbum são autoritativas no servidor e usam um bucket privado. Criação, limite, inclusão de metadados, concessão, revogação, denúncia e remoção não podem ser decididos somente pelo cliente. Cada leitura autenticada verifica titular/concessão, bloqueios e estados das contas; URL pública ou permanente é proibida. A função de bloqueio também revoga concessões nos dois sentidos na mesma transação, e a rotina de exclusão limpa objetos e metadados de forma idempotente.

Quando solicitada posteriormente no Perfil, a Edge Function `age-verification-session` cria uma sessão Didit `user` no workflow publicado, com `vendor_data` pseudônimo estável por usuário e referência única de tentativa; `age-verification-webhook` valida a assinatura, usa a notificação somente como gatilho, consulta a decisão diretamente no Didit e verifica todos os controles antes de conceder o selo. `In Review` preserva a sessão corrente e bloqueia apenas nova criação de sessão Didit. Falha, revisão ou ausência da verificação não desativa a conta nem bloqueia descoberta/conversa; suspensão e moderação continuam autoritativas. O contrato operacional detalhado está em [age-assurance.md](age-assurance.md).

## 9. Fronteira Android local e remota

O app mantém um repositório em memória para testes determinísticos e demonstração offline. O projeto de desenvolvimento remoto usa um gateway autenticado para Auth, PostgREST/RPC e Realtime; falhas remotas não podem cair silenciosamente no fake.

- A UI envia uma intenção de nova conversa e consome um resultado explícito: criada e ativa, já existente, bloqueada ou quota esgotada.
- O repositório fake aplica a quota de 5 aberturas e não permite que a tela altere o saldo diretamente.
- A UI mostra apenas conversas ativas; não existem estados pendente, aceito ou ignorado.
- A primeira mensagem cria a conversa ativa imediatamente. Bloquear e denunciar continuam disponíveis no perfil e dentro da conversa.
- Denunciar cria um caso de moderação sintético e auditável no fake, sem registrar conteúdo sensível em logs.
- Mensagens em conversas ativas são validadas pelo repositório e não alteram a quota de novas aberturas.
- O protótipo inicia com uma conversa ativa sintética e determinística para permitir validar o fluxo sem backend.
- Os testes usam IDs sintéticos (`user-free`, `user-target-*`) e não simulam dados reais.
- A configuração Android contém somente URL e chave publicável do projeto de desenvolvimento. Senha de banco, chave secreta e `service_role` nunca entram no APK.
- O gateway remoto usa apenas as RPCs autorizadas para onboarding e escritas críticas; a UI não decrementa quota nem decide estado de moderação.
- Realtime apenas invalida/recarrega dados permitidos por RLS; a autorização continua sendo decidida pelo servidor.
- Fixtures da foto pública modelam versões e decisões (`pending`, `approved`, `adult`, `abusive`) sem mídia real; fixtures de chat e álbum validam que essas mídias não entram na triagem automática.
- O repositório de perfil mantém identidade e preferência como estados distintos; somente a identidade visível pode compor cartões públicos e a preferência nunca sai do estado privado do próprio usuário.
- O álbum privado não reutiliza URL/cache das fotos públicas. O cliente limpa imagens carregadas ao perder concessão, receber bloqueio/moderação ou sair da tela, e sempre trata negação do servidor como estado autoritativo.
- Toda operação assíncrona de álbum fica vinculada à sessão e à tela que a iniciou. Voltar, sair ou trocar de conta cancela/invalida a operação, e uma resposta antiga nunca pode repopular bytes privados.
- Durante a janela de compatibilidade, o overload legado de onboarding continua disponível somente como wrapper server-side com identidade oculta `prefer_not_to_say` e preferência privada `everyone`.

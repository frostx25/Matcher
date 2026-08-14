# Matcher — plano inicial do produto

## 1. Visão

Criar um app Android de descoberta e conexão entre adultos, inspirado na velocidade e na proximidade do Grindr, mas desenhado desde o início para diferentes gêneros, orientações, pronomes e formatos de relacionamento. A experiência será mais rápida, visualmente mais refinada e com mais controle de privacidade.

O produto deve ser de encontros e conexões consensuais — não uma plataforma de conteúdo sexual explícito. A confiança, a privacidade da localização e o controle sobre quem pode entrar em contato serão parte central da experiência.

**Hipótese inicial:** pessoas adultas querem descobrir quem está disponível por perto sem serem forçadas a escolher apenas “homem/mulher” ou a expor sua localização exata, e abandonam apps quando encontram assédio, perfis falsos ou filtros que não representam sua identidade.

**Recorte recomendado para começar:** Android, português do Brasil, maiores de 18 anos e lançamento fechado em uma única região metropolitana. Apps baseados em proximidade precisam de densidade local antes de tentar atender o país inteiro.

**Direção visual:** base preta, rosa vibrante como cor de ação e destaque, superfícies escuras elevadas, tipografia limpa, componentes modernos e arredondados. O visual deve transmitir energia e intimidade sem parecer infantil ou excessivamente sexualizado.

## 2. O que aprendemos com o mercado

| Referência | Elemento útil | O que não devemos copiar literalmente |
|---|---|---|
| Grindr | Grade de perfis próximos, filtros, exploração por local e recursos de privacidade como modo incógnito | Exposição excessiva de distância/localização e experiência que pode favorecer assédio ou objetificação |
| Tinder | Interesse mútuo antes do chat, descoberta simples e verificação de fotos | Dependência exclusiva de swipe e perfis pouco expressivos |
| Bumble | Verificação, chamadas dentro do app e recursos de segurança | Regras de iniciativa baseadas em papéis heteronormativos |
| Feeld | Separação entre gênero, sexualidade, desejos e tipo de relacionamento; perfis conectados | Complexidade excessiva no onboarding e foco de nicho para o nosso primeiro lançamento |
| HER | Comunidade e linguagem voltadas a pessoas queer | Não assumir que uma única comunidade representa todo o público do produto |

O posicionamento mais promissor é: **“conexões perto de você, com identidade no seu idioma e privacidade por padrão.”**

## 3. Princípios do produto

1. **Identidade não é preferência.** Gênero, pronomes, orientação, intenção e preferências de descoberta serão campos separados.
2. **Visibilidade é controlável.** A pessoa escolhe quem pode encontrá-la, quais campos aparecem e se deseja usar localização aproximada.
3. **Contato direto com proteção imediata.** Não haverá swipe, match obrigatório ou aceite prévio: a primeira mensagem abre a conversa. Quem recebe pode bloquear ou denunciar a qualquer momento, e essas ações interrompem o contato imediatamente.
4. **Segurança é uma feature.** Bloqueio, denúncia, moderação, prevenção a perfis falsos e proteção contra exposição de localização entram no MVP.
5. **Coleta mínima.** Só pediremos uma permissão ou dado quando houver uma função clara que dependa dele.
6. **Inclusão com linguagem simples.** Opções abertas, exemplos e explicações curtas; nenhum usuário precisa escolher um rótulo para continuar.

## 3.1. Direção visual e de experiência

### Sistema de cores inicial

- Fundo principal: `#0B0B0F`.
- Superfície/card: `#17131A`.
- Rosa principal: `#FF2D7A`.
- Rosa suave/estado selecionado: `#FF78A8`.
- Texto principal: `#FFF7FB`.
- Texto secundário: `#B9AEB5`.
- Estados de erro e alerta devem manter contraste próprio; o rosa não será usado para tudo.

### Componentes

- Cards de perfil com raio de 20–24 dp.
- Botões principais com raio de 16–20 dp e área mínima de toque de 48 dp.
- Bottom sheets e diálogos com raio superior de 24 dp.
- Grade com fotos grandes, hierarquia visual clara e poucos elementos sobrepostos.
- Microinterações rápidas e discretas: entrada de card, confirmação de interesse, envio de mensagem e estados de carregamento.
- Acessibilidade desde o início: contraste validado, textos redimensionáveis, labels para leitores de tela e não depender somente de cor para comunicar estado.

O Android será nativo por enquanto. Isso reduz a camada de abstração e facilita otimizar a grade, o cache de imagens, as animações e o chat para os aparelhos Android que escolhermos suportar.

### Metas de performance do MVP

Metas iniciais, medidas em aparelhos Android intermediários e acompanhadas por telemetria:

- Primeira tela interativa em até 2,5 s após abertura a frio.
- Primeiro conjunto de perfis utilizável em até 1,5 s após a resposta da API.
- Rolagem da grade sem travamentos perceptíveis.
- Envio de mensagem com confirmação visual imediata e confirmação do servidor em até 500 ms quando houver conexão normal.
- Fotos carregadas por miniaturas responsivas, com carregamento sob demanda e cache local.
- Nenhum endpoint retornará todos os perfis; descoberta usará paginação por cursor e índice geográfico.

## 4. MVP

### Onboarding e conta

- Cadastro por e-mail/OTP; a confirmação do e-mail cria ou confirma a conta. Telefone pode ser adicionado depois para reduzir fraude, sem importar contatos.
- Onboarding básico com ano de nascimento, autodeclaração de que a pessoa tem 18 anos ou mais e aceite dos Termos de Uso e da Política de Privacidade antes de criar conteúdo.
- O onboarding apresenta identidade de gênero separada da preferência de descoberta. A pessoa pode selecionar mais de uma identidade, usar autodescrição ou escolher “prefiro não informar”; a preferência “quem quero encontrar” também aceita múltiplas opções e nunca é exibida a terceiros.
- Ao concluir esse onboarding, a conta e o perfil ficam ativos e utilizáveis como **não verificados**; a verificação Didit não é requisito para descoberta ou conversa.
- Fluxo para apagar conta dentro do app e link web externo para solicitar a exclusão.

### Perfil

- Nome de exibição, idade declarada a partir do ano de nascimento, bio e uma única foto pública de perfil.
- Identidade de gênero com seleção múltipla e opção de autodescrição.
- Pronomes opcionais.
- Orientações/interesses de conexão opcionais, com explicação de visibilidade.
- Intenção: conhecer pessoas, namoro, amizade, encontros casuais ou outra opção definida pelo usuário.
- Tipo de relacionamento, incluindo monogamia, não monogamia consensual e “prefiro não informar”.
- Verificação 18+ opcional, iniciada depois pela aba Perfil. Somente o workflow Didit completo — documento brasileiro, prova de vida passiva e correspondência facial — concede o selo **18+ verificado**; ele não é um selo de identidade nem publica o documento.
- A foto de perfil pode ser qualquer imagem permitida pela política de conteúdo; não precisa mostrar o rosto nem ser a selfie usada pelo Didit.
- Somente a foto pública de perfil passa pela triagem automática de imagem. Fotos enviadas no chat e imagens do álbum privado não são encaminhadas ao classificador automático; continuam sujeitas à Política de Conteúdo, denúncia e remoção por moderação.
- Cada versão de foto começa privada em `pending`; terceiros veem um placeholder cinza. Somente `approved` fica visível a terceiros, enquanto decisões `adult` ou `abusive` mantêm a imagem privada e o placeholder.
- Enviar uma nova versão não substitui a versão já aprovada: a imagem anterior permanece pública até a nova versão também receber `approved`.
- Cada pessoa pode manter um álbum privado com até dez imagens, totalmente separado das fotos públicas do perfil. As imagens do álbum ficam disponíveis imediatamente após o upload, sem aprovação prévia, mas continuam sujeitas à Política de Conteúdo, denúncia e remoção por moderação.
- O álbum não aparece na descoberta nem no perfil público. Somente o titular e destinatários autorizados individualmente podem abrir suas imagens; o titular pode revogar cada acesso a qualquer momento.
- Bloquear alguém revoga permanentemente qualquer acesso de álbum nos dois sentidos. Desbloquear não restaura concessões anteriores, e uma nova autorização explícita é necessária.
- A experiência avisa que capturas ou fotografias por outro aparelho não podem ser impedidas. Vídeo, acesso temporário e visualização única ficam fora desta versão.

### Descoberta

- Grade de pessoas próximas, ordenada por distância aproximada, atividade recente e compatibilidade com os filtros.
- Filtros por faixa etária, identidade, intenção, tipo de relacionamento e verificação.
- A preferência privada de gêneros é aplicada pelo servidor à descoberta. Uma seleção específica só combina com identidades que a pessoa escolheu publicar; identidade oculta ou “prefiro não informar” não pode ser inferida pelo resultado do filtro.
- Perfis existentes recebem migração segura: identidade “prefiro não informar”, oculta, e preferência “todas as pessoas”, sem deduzir gênero a partir de nome, bio, fotos ou conversas.
- Distância exibida em faixas (“perto”, “na região”, “mais distante”), nunca em metros exatos.
- Alternativa de escolher uma cidade/região sem compartilhar localização do aparelho.
- Controles: pausar descoberta, ocultar distância, não aparecer em exploração e bloquear região específica.

### Conexões e conversa

- Botão principal “Conversar” diretamente no perfil.
- A primeira mensagem abre imediatamente uma conversa ativa, sem solicitação ou aceite prévio.
- Não haverá “passar”, recusar match ou dependência de interesse recíproco como no Tinder.
- Chat 1:1 em tempo real desde o primeiro envio.
- O plano Free poderá iniciar 5 novas conversas por janela de 24 horas; mensagens em conversas já abertas não consomem novas aberturas.
- Limites por minuto, por perfil e por dispositivo continuam valendo também para planos pagos.
- Envio de texto e fotos que passam por regras de conteúdo; sem mídia efêmera no MVP.
- Silenciar, bloquear e denunciar dentro da conversa.

### Monetização inicial

- **Free:** grade, perfil, filtros básicos e 5 novas conversas iniciadas por 24 horas.
- **Extra:** 20 novas conversas por 24 horas, filtros avançados, até 200 favoritos, 50 perfis vistos recentemente e opção de ocultar atividade recente.
- **Pro:** 50 novas conversas por 24 horas, favoritos ilimitados, até 200 perfis vistos recentemente, ver quem favoritou, modo incógnito, ocultar atividade e confirmação de leitura, além de um destaque por semana.
- **Ilimitado:** sem limite comercial visível para novas conversas, históricos e favoritos; inclui tudo do Pro, até três álbuns privados com 30 fotos no total, quem viu o perfil nos últimos 30 dias, um destaque por dia e suporte prioritário.
- “Sem limite comercial” nunca desliga os controles anti-spam, antifraude ou de segurança por minuto, destinatário, conta e dispositivo.
- Bloqueio, denúncia, apelação, exclusão de conta, verificação 18+, resposta em conversas existentes e controles básicos de álbum permanecem gratuitos.
- Preços de referência para validação: Extra R$ 14,90/mês, Pro R$ 29,90/mês e Ilimitado R$ 49,90/mês. O preço autoritativo será sempre o exibido pelo Google Play.
- Os planos devem vender valor recorrente real, com preço, renovação automática e cancelamento claramente informados.
- No Android, assinaturas e recursos digitais serão integrados ao Google Play Billing, salvo eventual programa de cobrança alternativa aplicável ao país.

### Segurança e operação

- Denúncia de perfil, foto e mensagem com motivo estruturado e campo livre.
- Denúncia de imagem ou álbum privado pelo destinatário autorizado, com ocultação imediata para quem denunciou e preservação mínima de evidência para moderação.
- Fila de moderação com estados, evidências, histórico de ação e auditoria.
- Limites por conta/dispositivo/IP para criação de perfis, interesses e mensagens.
- Controles iniciais de spam, repetição de texto, conteúdo de imagem e comportamento abusivo. O contrato define os estados e efeitos da moderação, sem prometer classificação automática.
- Central de segurança com dicas de encontro, contato de confiança e orientação para emergência.
- Painel web interno para moderadores, separado do app do usuário.

## 5. Fora do MVP

Chamadas de vídeo/voz, transmissão ao vivo, mapa com pinos, localização em segundo plano, matching por IA, perfis de casal completos, eventos, feed público, tradução automática, anúncios, vídeo em álbum privado, concessão de álbum com expiração e visualização única. Esses itens só entram depois de validar retenção, segurança e densidade de usuários.

## 6. Segurança, privacidade e conformidade

### Google Play

- O app deve usar o recurso **Restrict Minor Access** do Play Console e manter o gate básico de 18+ no onboarding. O Didit é uma verificação opcional posterior e não substitui a revisão de conformidade necessária antes do lançamento de um app de namoro/matchmaking.
- Conteúdo sexual explícito, pornografia, solicitação de atos sexuais mediante compensação, sugar dating, conteúdo não consensual e exploração de menores serão proibidos.
- Como haverá conteúdo gerado por usuários, precisamos de Termos/Política de Conteúdo, moderação contínua, denúncia de conteúdo e usuários, bloqueio em conversas 1:1 e resposta a violações.
- O formulário Data safety precisa refletir localização, fotos, mensagens, identificadores, orientação e demais dados realmente tratados.

### LGPD e privacidade

- Dados sobre vida sexual e dados que revelem aspectos íntimos podem ser dados pessoais sensíveis; o tratamento deve ser definido com base legal, consentimento específico quando aplicável, finalidade clara, retenção limitada e controles de titular.
- Não armazenar latitude/longitude exatas como dado de perfil. Guardar apenas uma representação espacial reduzida ou um índice de região, com atualização controlada.
- Não usar localização para publicidade. No Android, começar com localização aproximada e em primeiro plano; não pedir localização em segundo plano no MVP.
- Criptografar tráfego e dados sensíveis, restringir acesso interno, separar dados de autenticação de conteúdo, registrar auditoria e preparar resposta a incidentes.
- A política de privacidade deve explicar quem controla os dados, quais campos são públicos, com quem são compartilhados, prazos de retenção e como exercer direitos.

## 7. Arquitetura técnica recomendada

### Android

- Kotlin + Jetpack Compose + Material 3.
- Arquitetura de uma Activity, navegação por Compose, ViewModels e fluxo unidirecional de estado.
- Camadas: `ui`, `domain`, `data`; repositórios para autenticação, perfis, descoberta, chat e moderação.
- Paging 3 para carregar a grade por cursor, `LazyVerticalGrid` com chaves estáveis e Coil para miniaturas com cache em memória/disco.
- Room para cache local mínimo, WorkManager para tarefas não urgentes e FCM para notificações.
- Android Photo Picker/CameraX quando necessário; pedir apenas permissões em contexto.

### Backend para o primeiro ciclo

- PostgreSQL com PostGIS para busca geográfica e filtros relacionais.
- Supabase como acelerador inicial para Auth, Storage, Realtime, Row Level Security e banco; manter regras de negócio sensíveis em funções/API próprias.
- Consultas de descoberta com índice espacial, paginação por cursor, payloads pequenos e cache apenas para regiões/consultas quentes; Redis fica como otimização posterior, se os dados comprovarem necessidade.
- Fotos servidas por CDN com versões de miniatura, média e original; o feed inicial nunca deve baixar imagens originais.
- Processo de moderação de imagens e texto com estados auditáveis e possibilidade de revisão humana; o MVP não depende nem promete um classificador automático.
- Painel de moderação web com permissões por função, logs e exportação controlada de evidências.
- Observabilidade: erros, latência, entregas de mensagens, decisões de moderação e eventos de produto sem registrar conteúdo sensível desnecessário.

### Modelo de dados inicial

`users`, `profiles`, `profile_identities`, `profile_preferences`, `photos`, `private_albums`, `private_album_items`, `private_album_grants`, `locations`, `conversation_openings`, `conversations`, `messages`, `blocks`, `reports`, `verifications`, `subscriptions`, `entitlements`, `moderation_cases` e `audit_events`.

Regras importantes: identidade e orientação são atributos que o usuário pode escolher mostrar ou ocultar; preferências de descoberta não devem inferir ou expor uma identidade que a pessoa não publicou. Metadados, objetos e permissões do álbum privado também são privados por padrão, e cada leitura precisa revalidar no servidor a concessão atual, a relação de bloqueio e o estado das contas.

## 8. Roadmap de execução

### Fase 0 — Descoberta e validação

Entrevistar 10–15 pessoas de perfis diversos, escolher a primeira cidade, validar linguagem de identidade e segurança, mapear concorrentes, revisar nome/domínio e produzir o fluxo de onboarding.

### Fase 1 — Protótipo navegável

Desenhar o design system rosa/preto, onboarding, edição de perfil, grade, filtros, conversa direta, chat, denúncia e exclusão de conta. Testar com usuários antes de codificar, incluindo leitura em aparelhos menores e condições de rede lenta.

### Fase 2 — Fundação técnica

Criar projeto Android, ambiente de backend, autenticação, banco, políticas de acesso, upload seguro, paginação geográfica, cache de imagens, eventos de performance, controle de limites de conversa, entitlements de assinatura e painel mínimo de moderação.

### Fase 3 — MVP fechado

Implementar o escopo da seção 4, testes de permissão/localização, testes de abuso, dados de teste e distribuição interna para um grupo pequeno.

### Fase 4 — Beta por convite

Abrir uma única região, acompanhar denúncias diariamente, medir tempo até a primeira conexão, corrigir falhas de privacidade e ajustar filtros e onboarding.

### Fase 5 — Lançamento controlado

Publicar com faixa etária correta, Data safety preenchido, política de privacidade, canal de suporte, processo de remoção e playbook de incidentes. Expandir cidade por cidade somente quando houver densidade e moderação suficiente.

## 9. Métricas para decidir se o MVP funciona

- Percentual que conclui o perfil.
- Tempo até a primeira conversa iniciada e até a primeira resposta.
- Percentual de usuários ativos que recebem ao menos uma conexão.
- Taxa de início de conversa e resposta.
- Retenção D1/D7/D30 por cidade.
- Taxa de denúncias e bloqueios por mil usuários.
- Tempo mediano para tratar denúncia e reincidência após moderação.
- Percentual de perfis verificados e taxa de contas suspeitas bloqueadas.
- Número de incidentes de exposição de localização — meta: zero.

## 10. Decisões já tomadas

- Plataforma inicial: somente Android.
- Direção visual: rosa e preto, estilo moderno e arredondado.
- Prioridade de produto: velocidade percebida, especialmente na grade e no chat.
- Estratégia técnica: Android nativo para permitir otimização mais direta.
- Modelo de interação: conversa direta a partir do perfil, sem swipe e sem match obrigatório.
- Monetização: plano Free com 5 novas conversas por janela de 24 horas e planos Extra/Pro com limites maiores.

## 11. Decisões que precisamos fechar agora

1. Qual será a primeira cidade/região de lançamento?
2. O produto será focado em namoro, em conexões casuais ou em ambos com intenções declaradas?
3. Decidido: o onboarding básico ativa a conta como não verificada após confirmação do e-mail, autodeclaração 18+ e aceite dos termos; Didit não bloqueia descoberta nem conversa. A pessoa pode solicitar depois, no Perfil, o selo **18+ verificado**. Para concedê-lo, o Didit executa um workflow publicado e versionado com documento brasileiro, prova de vida `PASSIVE` e correspondência facial 1:1; falha ou revisão não desativa a conta, e suspensão/moderação sempre prevalecem. O Matcher mantém apenas pseudônimo técnico, referências opacas e resultado mínimo, sem identificador direto, dado documental, selfie ou biometria.
4. O nome de trabalho “Matcher” será mantido ou vamos iniciar uma etapa de naming?

**Assunção de trabalho para continuar sem bloquear:** Brasil, uma região metropolitana, adultos 18+, conversa direta sem aceite prévio, 5 novas aberturas por 24 horas no Free, bloqueio e denúncia imediatos, localização aproximada e interface escura com rosa como destaque.

## 12. Documentos de execução

- [Instruções para agentes](AGENTS.md)
- [Especificação do MVP](docs/SPEC-MVP.md)
- [Papéis de agentes](docs/AGENT_ROLES.md)
- [Ambiente local](docs/LOCAL_DEV.md)
- [Contrato do selo 18+ opcional com Didit](docs/age-assurance.md)
- [Harness de testes](harness/README.md)
- [Cenários de conversa e quota](harness/scenarios/chat.yml)
- [Cenários de onboarding e perfil](harness/scenarios/onboarding.yml)
- [Cenários de identidade e descoberta](harness/scenarios/discovery.yml)
- [Cenários de álbum privado](harness/scenarios/private-album.yml)

## Referências consultadas

- [Grindr — The Grid](https://help.grindr.com/hc/en-us/articles/12155355365011-The-Grid) e [Explore](https://help.grindr.com/hc/en-us/articles/12155548594707-Explore)
- [Grindr — Unlimited](https://help.grindr.com/hc/en-us/articles/1500008656741-Grindr-Unlimited)
- [Grindr — Albums](https://help.grindr.com/hc/en-us/articles/4414580688787-Albums), [Filters](https://help.grindr.com/hc/en-us/articles/12155443240851-Filters) e [How to Build Your Profile](https://help.grindr.com/hc/en-us/articles/4402336949523-How-to-Build-Your-Profile)
- [Bumble — recursos de namoro](https://bumble.com/en-us/the-buzz/bumble-dating-features) e [recursos de segurança](https://support.bumble.com/hc/en-us/articles/28537051467293-Our-safety-features)
- [Feeld — recursos do app](https://feeld.co/the-app) e [identidades, desejos e relacionamentos](https://support.feeld.co/hc/en-gb/articles/18822038569884-Desires-Relationship-Types-Sexualities-and-Genders-on-Feeld-Profiles-Explained)
- [Google Play — conteúdo sexual](https://support.google.com/googleplay/android-developer/answer/17190352?hl=en&rd=3)
- [Google Play — conteúdo gerado por usuários](https://support.google.com/googleplay/android-developer/answer/9876937?hl=en-GB)
- [Google Play — restrição de menores para namoro/matchmaking](https://support.google.com/googleplay/android-developer/answer/16838200?hl=en)
- [Google Play — exclusão de conta e dados](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)
- [Android — arquitetura](https://developer.android.com/topic/architecture), [Jetpack Compose](https://developer.android.com/develop/ui/compose/architecture) e [localização aproximada](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime)
- [LGPD — Lei nº 13.709/2018](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm)
- [ANPD — Glossário de proteção de dados](https://www.gov.br/anpd/pt-br/documentos-e-publicacoes/glossario-anpd)

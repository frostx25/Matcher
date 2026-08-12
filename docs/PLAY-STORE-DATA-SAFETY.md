# VibeAli — rascunho do Data safety

Documento operacional para preencher o Play Console. Confirmar cada resposta contra a versão publicada e os contratos vigentes dos fornecedores antes do envio.

## Visão geral sugerida

- O app coleta dados: **sim**.
- O app compartilha dados com terceiros: **sim**, no sentido do formulário da Play, quando dados são enviados a operadores como Supabase, Resend, Firebase, Didit opcional e OpenAI para funções específicas.
- Dados são criptografados em trânsito: **sim**.
- Usuário pode solicitar exclusão: **sim**, dentro do app e em `https://vibeali.shop/excluir-conta/`.
- Serviço exclusivo para maiores de 18 anos.

## Categorias a declarar

| Categoria Play | Exemplos no VibeAli | Finalidade | Obrigatório? | Compartilhamento/operador |
|---|---|---|---|---|
| Informações pessoais — e-mail | Login e contato da conta | Gerenciamento de conta, segurança | Sim | Supabase Auth e Resend |
| Informações pessoais — nome | Nome de exibição e autodescrição opcional | Funcionalidade do app | Nome de exibição sim | Supabase |
| Informações pessoais — data de nascimento | Apenas ano de nascimento declarado | Restrição 18+ e funcionalidade | Sim | Supabase |
| Localização aproximada | Região/cidade e faixa de proximidade | Descoberta | Pode operar com região escolhida | Supabase |
| Fotos e vídeos | Foto pública, álbum privado, fotos de chat; sem vídeo no MVP | Perfil, comunicação e álbum | Não | Supabase Storage; foto pública pode ir à OpenAI |
| Mensagens | Texto, reações e eventos de conversa | Comunicação | Não | Supabase |
| Atividade no app | Favoritos, bloqueios, denúncias, preferências e estado de atividade | Funcionalidade, segurança e moderação | Parcial | Supabase |
| IDs do dispositivo ou outros IDs | Firebase Installation ID e identificadores opacos de sessão | Notificações, segurança | Notificações são opcionais | Firebase e Supabase |
| Arquivos e documentos | Documento somente no fluxo opcional 18+ | Verificação de maioridade | Não | Didit; VibeAli não guarda cópia |
| Informações biométricas | Selfie/prova de vida/comparação facial opcional | Verificação de maioridade | Não | Didit; VibeAli não guarda cópia/template |

## Pontos que exigem cuidado

- Preferência de descoberta, identidade, orientação e intenção podem ser consideradas informações pessoais sensíveis conforme contexto; declarar de forma conservadora quando o Play Console oferecer categoria aplicável.
- Marcar conteúdo como “efêmero” somente quando realmente não for persistido. Mensagens, fotos e denúncias não são efêmeras.
- Não afirmar que todo dado pode ser apagado imediatamente: evidências de denúncia e registros mínimos podem ter retenção legítima.
- OpenAI recebe somente a candidata a foto pública para moderação; álbum privado e foto de chat não entram nesse processamento automático.
- Didit só recebe dados quando a pessoa inicia voluntariamente a verificação 18+.

## Verificação final

- [ ] Comparar com o inventário de tabelas, buckets e logs da versão de produção.
- [ ] Conferir os termos e a seção Data safety de cada SDK na data do lançamento.
- [ ] Validar se analytics ou crash reporting foram adicionados; atualmente não fazem parte do contrato documentado.
- [ ] Confirmar URLs públicas e e-mail de suporte ativos.

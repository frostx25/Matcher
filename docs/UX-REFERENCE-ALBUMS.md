# Referência funcional — descoberta, perfil e álbuns

Observação realizada em 04/08/2026 no aplicativo instalado no Samsung de desenvolvimento. O objetivo é entender hierarquia, navegação e regras de interação; o Matcher mantém textos, cores, componentes e identidade próprios. Nenhuma conversa foi aberta, nenhum álbum de terceiro foi revelado, nenhuma concessão foi alterada e nenhuma captura com conteúdo pessoal foi preservada.

## Padrões observados

### Descoberta

- Avatar próprio no canto superior esquerdo abre a área da conta.
- Busca e filtros ficam acima de uma grade densa de três colunas.
- Estado online e informações curtas aparecem sobre cada cartão.
- Navegação principal permanece no rodapé; ações contextuais não disputam espaço com as abas.

### Conta e perfil

- A área da conta separa claramente `Editar perfil` de `Meus álbuns`.
- O editor prioriza fotos, depois nome, apresentação, tags e demais atributos.
- No perfil de outra pessoa, ações de segurança ficam no topo e mensagem/contato ficam fixos no rodapé.
- Ocultar e denunciar aparecem em uma folha de ações, sem ocupar permanentemente o conteúdo do perfil.

### Álbuns privados

- `Meus álbuns` abre uma tela própria em grade de três colunas.
- O primeiro cartão é sempre a criação/adição de conteúdo.
- Cada álbum mostra capa, nome, quantidade de itens e menu `⋮`.
- O menu reúne `Editar`, `Compartilhamento` e `Excluir`.
- O editor mostra nome privado, quantidade/atualização, acesso atual, adição de mídia, exclusão individual e reordenação.
- `Compartilhamento` abre uma lista separada de pessoas. Seleções múltiplas exibem uma ação fixa `Parar de compartilhar (n)` no rodapé.

## Decisões para o Matcher

- Nesta versão continua existindo no máximo um álbum ativo por conta, conforme a regra autoritativa do backend. Suporte a múltiplos álbuns exige migration, novos contratos e testes próprios; não será simulado apenas na interface.
- A entrada no perfil passa a se chamar `Meus álbuns` e mostra quantidade de fotos e acessos.
- O álbum usa grade de três colunas, com `Adicionar` como primeiro cartão.
- Um resumo separado informa quantidade de fotos e pessoas com acesso.
- O menu `⋮` concentra gerenciamento de compartilhamento e exclusão do álbum.
- Compartilhamento possui tela própria: concessões ativas são selecionáveis para revogação em lote e perfis sem acesso recebem uma ação explícita `Liberar`.
- A identidade visual continua preta, rosa e ameixa. Não são copiados marca, ícones proprietários, textos, nomes de recursos pagos nem composição exata de outro produto.

## Próximas fases possíveis

1. Migration e APIs para múltiplos álbuns nomeados, se isso entrar no escopo do produto.
2. Reordenação persistente das fotos e escolha de capa.
3. Atalho de compartilhamento dentro da conversa, mantendo confirmação explícita.
4. Pesquisa/paginação de destinatários quando a lista de contatos crescer.
5. RPC transacional para revogação em lote; até lá, cada concessão continua sendo revogada e revalidada individualmente pelo servidor.

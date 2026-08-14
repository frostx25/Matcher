# VibeAli — preparação para a Play Store

## Pronto no repositório

- Nome público `VibeAli` e identidade visual documentada.
- Ícones legado, redondo, adaptativo e monocromático de notificação.
- `targetSdk 36`, App Bundle release, minificação e redução de recursos.
- Exclusão de conta disponível dentro do aplicativo.
- Localização exibida apenas em faixas aproximadas e notificações sem corpo da mensagem.

## Bloqueios antes de enviar para análise

- [ ] Criar o projeto Supabase de produção separado e aplicar migrations/functions por pipeline.
- [x] Documentar separação Dev/Prod, configuração de CI e responsabilidades da VM.
- [ ] Definir a chave de upload da Play Store fora do repositório e guardar cópia recuperável.
- [x] Publicar Política de Privacidade em `https://vibeali.shop/privacidade/`.
- [x] Publicar página web de solicitação de exclusão em `https://vibeali.shop/excluir-conta/`.
- [x] Definir `suporte@vibeali.shop` e encaminhar para a caixa operacional verificada via Cloudflare Email Routing.
- [ ] Inserir na Política de Privacidade a identificação legal e o endereço do responsável quando a estrutura de publicação estiver definida.
- [ ] Preencher Data safety a partir do inventário real de Auth, localização aproximada, fotos, mensagens, FCM, Didit e moderação.
- [ ] Responder à classificação de conteúdo para um aplicativo exclusivo para maiores de 18 anos.
- [ ] Produzir capturas com contas e imagens sintéticas, sem conversa ou dado pessoal real.
- [ ] Executar teste fechado antes de solicitar produção.
- [ ] Validar backup/restore, alertas, limites, retenção, atendimento e apelação de moderação.
- [x] Publicar Termos de Uso e Política de Conteúdo compatíveis com o fluxo 18+ e conteúdo gerado por usuários.

Rascunhos operacionais: `PLAY-STORE-DATA-SAFETY.md`, `PLAY-STORE-CONTENT-RATING.md` e `PLAY-STORE-SCREENSHOTS.md`.

## Artefato local

```powershell
.\gradlew.bat clean test lintRelease bundleRelease
```

O arquivo esperado é `app/build/outputs/bundle/release/app-release.aab`. Sem uma configuração de assinatura local, ele serve apenas para validação; a chave e suas senhas nunca entram no Git.

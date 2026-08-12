# VibeAli — preparação para a Play Store

## Pronto no repositório

- Nome público `VibeAli` e identidade visual documentada.
- Ícones legado, redondo, adaptativo e monocromático de notificação.
- `targetSdk 36`, App Bundle release, minificação e redução de recursos.
- Exclusão de conta disponível dentro do aplicativo.
- Localização exibida apenas em faixas aproximadas e notificações sem corpo da mensagem.

## Bloqueios antes de enviar para análise

- [ ] Criar o projeto Supabase de produção separado e aplicar migrations/functions por pipeline.
- [ ] Definir a chave de upload da Play Store fora do repositório e guardar cópia recuperável.
- [ ] Publicar Política de Privacidade em URL estável no domínio `vibeali.shop`.
- [ ] Publicar página web de solicitação de exclusão de conta e dados.
- [ ] Definir e validar e-mail de suporte público no domínio.
- [ ] Preencher Data safety a partir do inventário real de Auth, localização aproximada, fotos, mensagens, FCM, Didit e moderação.
- [ ] Responder à classificação de conteúdo para um aplicativo exclusivo para maiores de 18 anos.
- [ ] Produzir capturas com contas e imagens sintéticas, sem conversa ou dado pessoal real.
- [ ] Executar teste fechado antes de solicitar produção.
- [ ] Validar backup/restore, alertas, limites, retenção, atendimento e apelação de moderação.

## Artefato local

```powershell
.\gradlew.bat clean test lintRelease bundleRelease
```

O arquivo esperado é `app/build/outputs/bundle/release/app-release.aab`. Sem uma configuração de assinatura local, ele serve apenas para validação; a chave e suas senhas nunca entram no Git.

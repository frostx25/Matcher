# Site público VibeAli

Landing page estática servida pelo Caddy. O container solicita e renova TLS automaticamente depois que `vibeali.shop` e `www.vibeali.shop` apontarem para a VM.

```bash
docker compose up -d
docker compose logs -f web
```

Antes de publicar, liberar TCP 80/443 e UDP 443 tanto no firewall da Oracle Cloud quanto no sistema operacional.

## Páginas públicas da Play Store

- Política de Privacidade: `https://vibeali.shop/privacidade/`
- Exclusão de conta: `https://vibeali.shop/excluir-conta/`
- Termos de Uso: `https://vibeali.shop/termos/`
- Política de Conteúdo: `https://vibeali.shop/conteudo/`
- Suporte pretendido: `suporte@vibeali.shop`

`suporte@vibeali.shop` usa Cloudflare Email Routing e encaminha para a caixa operacional verificada. A política ainda precisa receber a identificação legal do responsável quando a estrutura de publicação for definida.

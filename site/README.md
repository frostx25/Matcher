# Site público VibeAli

Landing page estática servida pelo Caddy. O container solicita e renova TLS automaticamente depois que `vibeali.shop` e `www.vibeali.shop` apontarem para a VM.

```bash
docker compose up -d
docker compose logs -f web
```

Antes de publicar, liberar TCP 80/443 e UDP 443 tanto no firewall da Oracle Cloud quanto no sistema operacional.

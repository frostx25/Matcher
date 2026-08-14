# Operação do VibeAli

## Papel da VM

A VM serve o site e o painel estático, executa verificações leves e pode manter cópias lógicas criptografadas. PostgreSQL, Auth, Storage e Realtime permanecem no Supabase.

## Monitoramento

Os arquivos em `ops/vm` instalam um timer systemd que verifica os endpoints públicos a cada cinco minutos. Falhas ficam no journal (`journalctl -u vibeali-healthcheck`). Alertas externos ainda precisam de um destino operacional; o timer local não substitui monitoramento fora da própria VM.

## Backup

O timer de backup fica desativado até `/etc/vibeali/backup.env` e o arquivo de senha existirem com modo `0600`. Nunca versionar a URL de banco ou a senha de criptografia. O dump não inclui bytes do Supabase Storage; uma rotina separada de objetos e um destino externo ainda são obrigatórios antes de produção.

Restaurações devem ser ensaiadas em um projeto Supabase descartável, nunca diretamente sobre produção.

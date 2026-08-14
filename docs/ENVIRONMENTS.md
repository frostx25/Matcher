# Ambientes do VibeAli

## Desenvolvimento

- Supabase: `Matcher Dev` (`gevdssaambgivxiqilad`)
- Aplicação Android: dados públicos em `local.properties`
- Site/painel: podem apontar para Dev somente durante validação interna
- Dados: exclusivamente sintéticos ou contas de teste controladas

## Produção

Produção exige um projeto Supabase separado, em região próxima ao público inicial. Não reutilizar banco, chaves, Storage, Vault, SMTP, Didit, Firebase ou secrets do Dev.

Checklist de criação:

1. Criar `VibeAli Prod` no Supabase.
2. Ativar MFA da organização, RLS, SSL enforcement e restrições de rede administrativas.
3. Aplicar migrations por pipeline e validar pgTAP antes de liberar tráfego.
4. Criar buckets e Edge Functions; cadastrar secrets diretamente no ambiente.
5. Configurar Resend/SMTP, callbacks `https://vibeali.shop` e deep link Android.
6. Criar projeto Firebase de produção e restringir suas credenciais.
7. Executar smoke com contas sintéticas e revisar Security/Performance Advisor.
8. Configurar backup lógico criptografado e ensaio de restauração; Storage precisa de cópia própria.

## Configuração Android

`local.properties` continua sendo apenas local. CI e builds de produção devem injetar valores públicos por secrets do ambiente. Nenhuma `service_role`, senha de banco ou chave privada pode entrar no APK.

O build `benchmark` usa minificação de release e assinatura debug apenas para medição local; ele nunca é distribuído. O AAB de produção usa `release` e uma chave de upload mantida fora do repositório.

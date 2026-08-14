# Ambientes do VibeAli

## Desenvolvimento

- Supabase: `Matcher Dev` (`gevdssaambgivxiqilad`)
- Aplicação Android: dados públicos em `local.properties`
- Site/painel: podem apontar para Dev somente durante validação interna
- Dados: exclusivamente sintéticos ou contas de teste controladas

## Produção

Produção exige um projeto Supabase separado, em região próxima ao público inicial. Não reutilizar banco, chaves, Storage, Vault, SMTP, Didit, Firebase ou secrets do Dev.

Estado em 14/08/2026:

- projeto `VibeAli Prod`: `jtbeuxouxkckmzgkpbzq`, região `sa-east-1`;
- schema: 35 migrations aplicadas, 3 buckets e catálogo com 4 planos;
- Auth: Site URL `https://vibeali.shop`, callback web e deep link Android, OTP de 6 dígitos e intervalo mínimo de 60 segundos;
- Edge Functions: 9 funções publicadas;
- workers: desativados até Firebase/OpenAI e demais segredos próprios de produção serem cadastrados;
- dados: nenhum seed e nenhuma conta do Dev copiados;
- configuração local: senha do banco e segredo do worker protegidos por DPAPI fora do repositório;
- pendente antes do smoke: SMTP/Resend, Firebase Prod, Didit Prod, OpenAI, administrador inicial do painel, backup externo e revisão dos avisos do Security Advisor.

Checklist de criação:

1. Criar `VibeAli Prod` no Supabase.
2. Ativar MFA da organização, RLS, SSL enforcement e restrições de rede administrativas.
3. Aplicar migrations por pipeline e validar pgTAP antes de liberar tráfego.
4. Criar buckets e Edge Functions; cadastrar secrets diretamente no ambiente.
5. Configurar Resend/SMTP, callbacks `https://vibeali.shop` e deep link Android.
6. Criar projeto Firebase de produção e restringir suas credenciais.
7. Executar smoke com contas sintéticas e revisar Security/Performance Advisor.
8. Configurar backup lógico criptografado e ensaio de restauração; Storage precisa de cópia própria.

Após aplicar as migrations em um ambiente novo, execute como operador do banco
`private.configure_worker_schedules('https://<project-ref>.supabase.co')`. Isso
substitui os endpoints históricos do Dev sem misturar filas entre ambientes. Os
jobs só devem ser ativados depois de cadastrar `matcher_worker_shared_secret` no Vault
e publicar as duas funções correspondentes.

## Configuração Android

`local.properties` continua sendo apenas local. CI e builds de produção devem injetar valores públicos por secrets do ambiente. Nenhuma `service_role`, senha de banco ou chave privada pode entrar no APK.

Localmente, `./gradlew -PbackendEnv=prod assembleRelease` lê
`local.prod.properties` (ignorado pelo Git). Sem a propriedade, o projeto continua
lendo `local.properties` e apontando para Dev. A configuração Prod nunca deve ser
copiada por cima da configuração Dev.

O build `benchmark` usa minificação de release e assinatura debug apenas para medição local; ele nunca é distribuído. O AAB de produção usa `release` e uma chave de upload mantida fora do repositório.

# Performance Android

## Baseline de 13/08/2026

Samsung SM-A315G, Android 12:

- APK debug: inicialização observada entre 3,5 s e 5,5 s; amostra com 36,69% de frames lentos.
- APK benchmark minificado: Activity fria em aproximadamente 495 ms.

O debug não representa a experiência publicada. A carga autenticada ainda deve ser medida separadamente até a primeira grade utilizável.

## Otimização aplicada

Após validar acesso/conta, perfil é obtido primeiro e as leituras independentes de identidade, descoberta, favoritos, privacidade e chat são executadas em paralelo. Retornos ao app dentro de 15 segundos não repetem uma carga completa.

Autorização permanece no servidor; nenhuma resposta é aceita depois de troca de sessão.

## Próximas medições

- tempo até a primeira grade utilizável com rede normal e lenta;
- jank em rolagem da descoberta com miniaturas reais;
- duração individual das RPCs/PostgREST sem conteúdo sensível nos logs;
- tamanho/cache/decodificação de imagens;
- Macrobenchmark e Baseline Profile em módulo separado antes do teste fechado.

## Medição de 14/08/2026

Samsung SM-A315G, Android 12, APK debug reinstalado com sessão preservada:

- inicialização fria autenticada medida por `am start -W`: **5.619 ms**;
- após estabilização, 69 frames observados, **7,25%** classificados como lentos;
- percentis: p50 **17 ms**, p90 **23 ms**, p95 **34 ms** e p99 **400 ms**;
- nenhum upload lento de bitmap foi detectado nessa amostra.

O resultado confirma que o maior custo atual está na inicialização/carga remota do build debug, não no desenho contínuo da grade. A meta para o teste fechado permanece medir o build minificado com Macrobenchmark e separar tempo de autenticação, perfil e primeira página de descoberta. Não migrar o banco para a VM como tentativa de otimização: a latência deve ser tratada com paginação, paralelismo seguro, cache e redução das chamadas iniciais.

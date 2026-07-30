# Ambiente local do Matcher

## 1. Perfil recomendado

Para desenvolvimento confortável com Android Studio, um emulador e serviços locais:

- Windows 11 64-bit atualizado.
- CPU Intel Core i5/i7 recente ou AMD Ryzen 5/7, com VT-x/AMD-V habilitado na BIOS/UEFI.
- 16 GB de RAM como mínimo prático; 32 GB recomendado.
- SSD com pelo menos 50 GB livres para o ciclo inicial; 100 GB livres é mais confortável com SDKs, Gradle, AVDs e imagens.
- GPU com pelo menos 4 GB de VRAM para usar emulador com maior conforto; 8 GB é preferível.
- Conexão estável para SDKs, dependências e imagens de teste.
- Um aparelho Android físico é útil como segunda validação, mas não é obrigatório para começar; o projeto será iniciado com um único emulador AVD leve.

O disco D: foi escolhido para os artefatos pesados e tem aproximadamente 931 GB livres. O projeto pode continuar no workspace atual, mas SDK, imagens do emulador e caches devem ficar em D:.

O Android Studio informa 16 GB como mínimo para Studio + Emulator e 32 GB como recomendado; para esse cenário, também recomenda GPU com 4 GB de VRAM no mínimo e 8 GB na configuração recomendada. A aceleração do emulador depende de virtualização habilitada. [Android Studio](https://developer.android.com/studio/install.html) · [aceleração do emulador](https://developer.android.com/studio/run/emulator-acceleration)

## 2. Diagnóstico da máquina atual

Detectado neste workspace:

- Windows 11 Home Single Language.
- Intel Core i7-10510U, 4 cores/8 threads.
- 7,8 GB de RAM.
- NVIDIA GeForce MX250, 2 GB de VRAM.
- Aproximadamente 21,6 GB livres no disco C:.

### Conclusão

Esta máquina é suficiente para começar com Android Studio, compilação e um emulador AVD leve, mas não é adequada para manter simultaneamente Android Studio, emulador pesado, Docker e backend local completo. O gargalo principal é memória, VRAM e espaço livre.

## 3. Modo local recomendado agora

### Perfil `fast`

- Android Studio aberto.
- Emulador fechado durante testes puramente unitários.
- Backend fake/in-memory para testes de tela e domínio.
- Supabase de desenvolvimento remoto somente quando precisarmos validar autenticação, storage ou realtime.
- Testes unitários e de contrato executados localmente.

### Perfil `device`

- Android Studio + um único emulador AVD leve ou aparelho físico.
- Backend de desenvolvimento isolado.
- Testes de fluxo: onboarding, grade, quota, solicitação de conversa, bloqueio e denúncia.

### Perfil `full`

- Android Studio + emulador acelerado.
- Docker/WSL2 com serviços locais.
- Reservado para uma máquina com pelo menos 16 GB de RAM e mais espaço livre, ou para CI.

Docker Desktop no Windows usa WSL2 ou Hyper-V; o backend WSL2 exige virtualização e pelo menos 8 GB de RAM no sistema. Na máquina atual, não será o modo padrão. [Docker no Windows](https://docs.docker.com/desktop/setup/install/windows-install/)

## 4. Software local

- Android Studio no canal stable.
- Android SDK, Platform Tools e `adb`.
- Git.
- JDK fornecido pelo Android Studio; usar o Gradle Wrapper do projeto.
- Navegador moderno para painel e documentação.
- Docker Desktop + WSL2 apenas quando o perfil `full` for necessário.
- Conta de desenvolvimento separada para Supabase/serviços externos; nenhuma credencial de produção no ambiente local.

### Caminhos recomendados

- Android Studio portátil: `D:\Android\AndroidStudio\android-studio`
- Android SDK: `D:\Android\Sdk`
- AVDs/imagens do emulador: `D:\Android\Avd`
- Cache do Gradle: `D:\Android\Gradle`
- Cache opcional de dependências: `D:\Android\Caches`

No Android Studio, confirmar `D:\Android\Sdk` como localização do SDK. Para os demais caches, configurar as variáveis de usuário antes do primeiro build:

```text
ANDROID_AVD_HOME=D:\Android\Avd
GRADLE_USER_HOME=D:\Android\Gradle
ANDROID_HOME=D:\Android\Sdk
```

O Android Studio está em `D:\Android\AndroidStudio\android-studio`; os componentes grandes também ficam em D:. Depois de configurar os caminhos, reiniciar o Android Studio para que o Device Manager e o Gradle reconheçam as variáveis.

O projeto já inclui Gradle Wrapper 9.5.1. O primeiro build usa `compileSdk 37.1`, `targetSdk 36`, JDK 17 do Android Studio e pode baixar dependências para `D:\Android\Gradle`.

Build e instalação local:

```powershell
$env:JAVA_HOME="D:\Android\AndroidStudio\android-studio\jbr"
$env:ANDROID_HOME="D:\Android\Sdk"
$env:ANDROID_SDK_ROOT="D:\Android\Sdk"
$env:GRADLE_USER_HOME="D:\Android\Gradle"
.\gradlew.bat :app:assembleDebug
& "D:\Android\Sdk\platform-tools\adb.exe" install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Smoke test no emulador conectado:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## 5. Configuração inicial do emulador

Para esta máquina, criar somente um AVD de telefone com:

- Imagem `x86_64`, compatível com o processador Intel.
- Uma versão Android estável disponível no SDK Manager.
- Resolução de telefone menor ou média; evitar AVD de tablet, foldable ou XR.
- RAM do AVD entre 1,5 GB e 2 GB.
- Graphics em `Automatic`; usar `Software` apenas se o driver da MX250 apresentar falhas.
- Snapshot habilitado para não reiniciar o sistema inteiro a cada execução.
- Fechar Docker e outros AVDs enquanto o emulador estiver aberto.

AVD criado e validado: `Matcher_Pixel_Lite`, usando `android-35-ext15/google_apis/x86_64`, 1,5 GB de RAM e GPU automática. O `adb` reconheceu o dispositivo e o boot terminou com sucesso; a aceleração WHPX está disponível.

No Windows, habilitar virtualização na BIOS/UEFI e confirmar a aceleração com o `-accel-check`. O Android Emulator usa aceleração de CPU/GPU para melhorar velocidade; sem ela, o teste pode ficar impraticável. [Aceleração do emulador](https://developer.android.com/studio/run/emulator-acceleration)

Fluxo no Android Studio:

1. Abrir **Tools > Device Manager**.
2. Criar um novo dispositivo virtual de telefone.
3. Escolher uma imagem `x86_64` estável e baixá-la pelo SDK Manager.
4. Ajustar RAM para 1,5–2 GB e Graphics para `Automatic`.
5. Iniciar o AVD e executar um build `debug`.
6. Se houver lentidão extrema, reduzir resolução/RAM, fechar o Docker e testar Graphics `Software`.

Para iniciar pelo terminal:

```powershell
$env:ANDROID_AVD_HOME="D:\Android\Avd"
D:\Android\Sdk\emulator\emulator.exe -avd Matcher_Pixel_Lite -gpu auto
```

## 6. Teste em aparelho físico

1. Ativar Opções do desenvolvedor e Depuração USB.
2. Conectar o aparelho e aceitar a chave RSA.
3. Confirmar com `adb devices`.
4. Instalar o build debug.
5. Executar smoke tests com rede normal, rede lenta e localização aproximada/desativada.

O emulador será o caminho principal neste início. Um aparelho físico poderá ser adicionado depois para validar câmera, notificações, consumo de bateria e comportamento em hardware real. [Executar em dispositivo físico](https://developer.android.com/studio/run/device.html)

## 7. Quando passar para uma VM

A VM só será necessária para staging/produção. Nesse momento, definir Ubuntu LTS, Docker, HTTPS, backups, monitoramento, banco/Storage persistentes e firewall. A VM não deve ser usada para emular Android; o emulador acelerado precisa rodar diretamente no host, não dentro de outra VM. [limitação de aceleração do emulador](https://developer.android.com/studio/run/emulator-acceleration)

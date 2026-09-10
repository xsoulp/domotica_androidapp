# APT Access

Aplicação Android simples para controlar as portas através do servidor em
`https://keys.lmpinto.pt`.

## Funcionalidade

- Abrir a porta do apartamento (`POST /apt_door/open`)
- Abrir a porta do prédio (`POST /bld_door`)
- Trancar e destrancar a porta do apartamento
- Autenticação biométrica obrigatória antes de cada operação
- Coordenadas GPS enviadas nos headers `X-Latitude` e `X-Longitude`
- Bearer guardado cifrado com uma chave no Android Keystore
- Verificação de conectividade antes do pedido
- Identificação automática do utilizador pelo bearer
- Gestão de utilizadores para administradores
- Criação, edição e remoção de vários períodos de acesso
- Acesso local fixo a 100 m ou acesso remoto sem limite

## Instalar no Pixel

Ativa *Opções de programador* e *Depuração USB*, liga o Pixel ao computador e
executa:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Na primeira abertura, permite a localização precisa. Em **Definições**, cola o
bearer restrito e confirma o endereço do servidor.

## Compilar

O projeto usa Java 17, Android Gradle Plugin 9.4.0 e Gradle 9.6.0:

```bash
./gradlew assembleDebug lint
```

O APK é criado em `app/build/outputs/apk/debug/app-debug.apk`.

## Segurança

O servidor é acedido através de HTTPS. As coordenadas comunicadas por um cliente
podem ser falsificadas; a impressão digital protege as operações nesta app, mas
não transforma o GPS numa prova criptográfica de presença.

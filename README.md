# Domótica server

API HTTP local para controlar uma fechadura SwitchBot Lock Pro e um SwitchBot
Bot. Esta branch é independente da aplicação Android mantida na branch `main`.

## Instalação

Requer Python 3.13, BlueZ e um adaptador Bluetooth funcional.

```sh
python3 -m venv "$HOME/switchbot-venv"
"$HOME/switchbot-venv/bin/pip" install -r requirements.txt
cp .server.env.example .server.env
cp .switchbot-bot.env.example .switchbot-bot.env
cp .switchbot-lock.env.example .switchbot-lock.env
```

Preenche os ficheiros `.env` e preserva o `users.json` da instalação existente.
Para uma instalação nova, o servidor aceita `--token-file` e
`--restricted-token-file` no primeiro arranque e cria `users.json`; remove esses
dois ficheiros de token logo após a migração. Os `.env`, tokens, `users.json` e
histórico contêm dados privados e não devem ser adicionados ao Git.

O ficheiro `users.example.json` documenta apenas a estrutura vazia e não deve ser
usado para inicializar autenticação.

## Serviço

```sh
mkdir -p ~/.config/systemd/user
cp switchbot-http.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now switchbot-http.service
```

Estado e logs:

```sh
systemctl --user status switchbot-http.service
journalctl --user -u switchbot-http.service -f
```

Endpoints principais:

```text
GET  /health
GET  /apt_door/status
POST /apt_door/lock
POST /apt_door/unlock
POST /apt_door/open
POST /bld_door
```

Consulta [readme_api.md](readme_api.md) para a API completa.

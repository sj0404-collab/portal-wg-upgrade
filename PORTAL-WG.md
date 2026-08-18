# AetherWG (portal-wg-upgrade)

Свой клиент на **официальном** WireGuard Android:

- id: `app.aetherwg.client`
- имя: **AetherWG**
- единый ключ `keystore/androide.jks` (как AndroIDE 2.6+)
- автообновление с GitHub Releases этого репо
- LinkGuard + keepalive 25 + START_STICKY

Закрытый APK PORTAL WG **не вшит**.

Репозиторий создан как **апгрейд-база на официальном открытом Android-клиенте WireGuard**, не на закрытых сборках «PORTAL WG».

## Что официально и открыто (Android)

| Проект | Статус | Ссылка |
|---|---|---|
| **WireGuard Android GUI** | **Официальный** клиент Jason A. Donenfeld / ZX2C4 | Канон: https://git.zx2c4.com/wireguard-android — зеркало: https://github.com/WireGuard/wireguard-android |
| **WG Tunnel** | FOSS-клиент WireGuard + AmneziaWG (не ZX2C4, но исходники открыты, MIT) | https://github.com/wgtunnel/android |
| **wg-portal** | Веб-портал управления сервером WG, **не Android-приложение** | https://github.com/h44z/wg-portal |

Этот каталог — shallow-клон официального `WireGuard/wireguard-android`.

Сборка:

```
./gradlew assembleRelease
```

## PORTAL WG (STRUGOV / STR_BYPASS)

Это **не** официальный WireGuard. Публичного нормального исходника на GitHub сейчас нет (`STR97/STRUGOV` отдаёт 404). Есть только APK/релизы в сторонних ветках. Для апгрейда легально и прозрачно берём официальный Android GUI выше.

Токен в этот репозиторий не кладётся.

## Разбор APK «PORTAL WG 1.3.6»

Архив в репо распакован: внутри один APK. Это **не** исходники. В нём нативные:

- `libwg-go.so` / `libwg.so` — WireGuard userspace
- `libam-go.so` / `libam.so` — AmneziaWG
- `libhev-socks5-tunnel.so` — SOCKS5-туннель

Это закрытая сборка обхода. **Реверс, патч APK и «мосты обхода» на GitHub runner здесь не делаются.**

Что реально добавлено в **официальный** клиент этого репо:

- `LinkGuard`: при потере радио VPN-процесс **не гасится**; при появлении сети снова `restoreState(UP)`
- `VpnService` = `START_STICKY`
- PersistentKeepalive по умолчанию **25** сек (чтобы NAT не рвал сессию)

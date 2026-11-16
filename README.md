# AirTouch — 把手机当成电脑的外设

用一台 Android 手机，通过校园局域网（同一个 Wi-Fi）无线控制 Windows 11 电脑：**空中鼠标**（陀螺仪）、
**触摸板**（手势 + 点击 + 滚轮）、**双向文件互传**。全链路 `wss://` 加密，扫码即连。

> The product UI, logs and `docs/PROTOCOL.md` are written in Chinese; this README is in English so the
> whole design and its verification status can be reviewed quickly.

---

## 1. What it does

| Feature | Windows side | Android side |
| --- | --- | --- |
| 空中鼠标 Air mouse | `SendInput` relative move | `TYPE_GYROSCOPE` angular velocity × Δt × sensitivity |
| 触摸板 Touchpad | `SendInput` move / click / wheel | drag = move, two-finger swipe = scroll, tap = left click |
| Buttons | left / right click | 左键 / 右键 toolbar |
| Wheel mode | scroll notches (positive = up) | 滚轮模式 + drag distance → `scroll` |
| Reset | `SetCursorPos` to primary-screen centre | 复位 button (also clears the gyro accumulator) |
| File phone → PC | saves into the user's Downloads | SAF picker, 64 KiB chunks |
| File PC → phone | 发送文件 button | saved via MediaStore Downloads (or legacy path < API 29) |
| Pairing | window shows LAN IP, status and a QR code | ZXing scan; manual connect dialog as a fallback |

Endpoint: `wss://{IP}:{PORT}/ws?token={TOKEN}` — default port **8443**, path `/ws`, one-time UUID token.

---

## 2. Repository layout

```
docs/PROTOCOL.md            wire protocol v1 — the single source of truth
NuGet.config                points restore at the vendored feed (.nuget-local)
tools/fetch-nuget.mjs       offline package fetcher (Node/OpenSSL; see §7)
tools/dotnet.ps1            dotnet wrapper that keeps SDK state inside the repo
tools/build.ps1             builds Core + Server + SmokeTest (no .sln needed)
tools/wss-probe.mjs         independent RFC 6455 TLS client probe (Node/OpenSSL)
tools/verify-wss.ps1        runs a headless server + the probe above
server/AirTouch.Core/       protocol, WSS host, certificate, security guard,
                            SendInput mouse, file transfer, QR rendering
server/AirTouch.Server/     WPF UI (QR, status, log, send file, unbind/reset)
server/AirTouch.SmokeTest/  self-test client: unit + end-to-end + live input + WPF check
android/                    Kotlin app (see android/README.md)
```

---

## 3. Requirements

**Windows 11** — .NET 8 SDK/runtime for building (`net8.0-windows`, `RollForward=LatestMajor` so a
newer runtime such as .NET 10 also runs it). No admin rights needed.

**Android 8.0 (API 26) or newer** — JDK 17 + Android SDK 34 to build, or just install the APK.

Both devices must be on the same LAN with **AP isolation disabled** (client isolation blocks
phone→PC traffic; nothing in the app can work around it).

---

## 4. Build and run

### Windows server

```powershell
pwsh -File tools\build.ps1              # Debug: Core + Server + SmokeTest
pwsh -File tools\build.ps1 -Release     # Release
pwsh -File tools\build.ps1 -Run         # build and start the WPF app
```

`server\AirTouch.Server\bin\Debug\net8.0-windows\AirTouch.Server.exe` starts the WPF window, which
immediately begins listening. The window shows the LAN address, the connection status, the client,
the bound IP, the banned list, the certificate fingerprint and a live log.

On first launch Windows Firewall asks whether to allow the app — **allow it for private networks**,
otherwise the phone cannot connect. To script it once (admin shell):

```powershell
netsh advfirewall firewall add rule name="AirTouch" dir=in action=allow protocol=TCP localport=8443
```

### Android app

```powershell
cd android
gradlew.bat assembleDebug               # needs JDK 17 and Android SDK 34
```

Create `android/local.properties` with `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`,
or simply open the `android/` folder in Android Studio and press Run. The Gradle wrapper (8.7,
including `gradle-wrapper.jar`) is committed, so no Gradle installation is required.
APK output: `android/app/build/outputs/apk/debug/app-debug.apk`.

### Pairing

1. Start the Windows app; the QR panel shows `wss://<lan-ip>:8443/ws?token=<uuid>`.
2. On the phone, open AirTouch → **扫码连接**, point the camera at the QR.
3. The phone pins the server certificate (TOFU) and connects; the token is destroyed immediately
   after a successful connection and the PC locks onto the phone's IP.
4. If scanning is inconvenient, the manual dialog accepts the IP and the token typed by hand.

### Verification

```powershell
# 1) full self-test: policy units, filename hardening, protocol, certificate, wss handshake,
#    end-to-end over the real socket, live SendInput, and the WPF window
server\AirTouch.SmokeTest\bin\Debug\net8.0-windows\AirTouch.SmokeTest.exe .smoke

# 2) independent cross-check of the wss endpoint from a non-Schannel TLS client
pwsh -File tools\verify-wss.ps1
```

Last run on this machine: **120/120 passed** (.NET suite) and **32/32 passed** (Node/OpenSSL probe),
including TLS 1.3, the certificate fingerprint check, one-time token reuse, a reconnecting phone's
replayed token being rejected as `token_expired` **without** consuming the brute-force ban budget,
IP lock across two different source addresses, brute-force ban, hostile filenames (including
Chinese/emoji names), 300 KiB and 220 KiB byte-exact file transfers in both directions, the Android
client's exact upload sequence (`hello` → `file_header` → chunks → `file_end`) with a Chinese
filename, and `SendInput`/`SetCursorPos` verified against the real cursor (`reset` landed exactly on
the primary screen centre). See §9 for what is *not* verified.

---

## 5. Using it

**Top tabs.** 空中鼠标 (air mouse) / 触摸板 (touchpad).

**Touchpad.** Drag to move (deltas are scaled by the sensitivity slider), two-finger swipe to scroll,
a tap without movement to left-click. The bottom toolbar has 左键 / 右键 / 滚轮模式 / 复位.

**Air mouse.** Hold the phone and move it; the gyroscope angular velocity is integrated per frame
(angle × Δt × sensitivity) and streamed as `move`. 复位 recentres the PC cursor *and* discards the
accumulated remainder so the pointer does not jump afterwards.

**Files.** 发送文件 on the PC pushes a file to the phone. On the phone, the file picker uploads to the
PC's Downloads folder. Both directions verify byte counts; a partial transfer is written to `.part`
and only renamed into place when complete.

---

## 6. Security model

| Threat | Mitigation |
| --- | --- |
| Eavesdropping on the Wi-Fi | `wss://` (TLS 1.2/1.3) with a per-install self-signed certificate |
| Rogue server / MITM after pairing | Android pins the leaf certificate's SHA-256 per host (TOFU) and pins the hostname to the paired address; a changed fingerprint aborts the connection |
| Random device connecting | One-time random UUID token in the QR, destroyed on first successful use; a replayed token is reported as `token_expired` without counting towards the ban counter |
| Another device hijacking the session | IP binding: after the first successful pairing only that IP is accepted; a second connection gets `busy` (1013); the UI can 解除绑定并重置 |
| Token brute force | 3 consecutive failures permanently ban the offending IP; 20 attempts / 10 s rate limit; check order is fixed: path → ban → rate limit → IP binding → single connection → token |
| Path traversal on upload | `Path.GetFileName` + separator stripping + control/invalid character removal + reserved-name escaping + `.part` staging + confinement to the download directory (verified with `../../../evil/../` names) |
| Disk exhaustion | 512 MiB per-file limit, disk-space pre-check before accepting, overrun rejected |
| Traffic analysis / replay of controls | Every message is scoped to the authenticated socket; the guard rotates the token on disconnect while keeping the IP binding |

Diagnostics-only escape hatch: `AIRTOUCH_INSECURE_NO_TLS=1` starts a **plaintext `ws://`** listener
and logs a loud warning. Never use it on a real network — it exists so a machine with a broken
Schannel stack can still be probed on loopback.

Configuration overrides: `AIRTOUCH_PORT`, `AIRTOUCH_ADDRESS`, `AIRTOUCH_DATA_DIR`,
`AIRTOUCH_DOWNLOAD_DIR`, `AIRTOUCH_INSECURE_NO_TLS`. Defaults: data dir
`%LOCALAPPDATA%\AirTouch`, downloads `%USERPROFILE%\Downloads`.

---

## 7. Building offline (why this repo looks unusual)

This machine's Schannel cannot acquire TLS credentials for outbound NuGet traffic
(`SEC_E_NO_CREDENTIALS`, "安全包中没有可用的凭证"), so `dotnet restore` never reaches nuget.org.
Everything is therefore vendored:

* `tools/fetch-nuget.mjs` downloads `.nupkg` files with **Node's OpenSSL** stack into `.nuget-local`
  (Fleck 1.2.0, QRCoder 1.6.0, the .NET 8 targeting packs, …). Run it as
  `node tools\fetch-nuget.mjs --out .nuget-local [id@1.2.3 …]`; the output directory is a flag on
  purpose, so a mistyped spec can never create a directory named after a package.
* `NuGet.config` clears all sources, points at `./.nuget-local`, and redirects the global packages
  folder to `./.nuget` (120 MB, git-ignored).
* `tools/dotnet.ps1` sets `DOTNET_CLI_HOME`/`NUGET_PACKAGES` inside the repo so the SDK never has to
  write to `C:\Users\<you>\.dotnet`.

On a normal machine you can delete `NuGet.config` (or add nuget.org back) and restore from the
internet as usual. To re-create the offline feed, run `node tools/fetch-nuget.mjs`.

---

## 8. Troubleshooting

**`.sln` build fails with 0 errors.** Known on this SDK install: the classic solution restore path
dies in `_FilterRestoreGraphProjectInputItems` / `MSB4276` (missing workload auto-import targets).
`server/AirTouch.sln` is kept for Visual Studio, but build with `tools/build.ps1`, which builds each
project directly and always works.

**"无法启用 wss：证书私钥只能以内存临时密钥加载…"** — Schannel refuses to serve TLS from an
in-memory key, and Windows denied writing the certificate's private key into the user's key
container (`%APPDATA%\Microsoft\Crypto`). Run the app in a normal desktop session (or as
administrator), or delete `airtouch-server.pfx` in the data directory and retry. The app fails
loudly instead of silently serving plaintext.

**Phone cannot reach the PC.** Check the firewall rule above, confirm both devices are on the same
subnet (not a guest network), and confirm AP/client isolation is off. The status panel shows the
exact address the QR encodes; if the PC has several adapters, pick the right one in the dropdown.

**Certificate changed.** Regenerating `airtouch-server.pfx` invalidates the phone's pin; use
「重置证书信任」 on the pairing screen and pair again.

**Port already in use.** The server reports it clearly; change the port field and press 重启服务.

**`NU1102: 找不到版本为 (= 8.0.NN) 的包 Microsoft.NETCore.App.Ref`** after a .NET SDK update: a new
SDK raises the net8.0 targeting-pack patch it asks for (8.0.33 → 8.0.34 happened here). Re-vendor the
matching packs:

```powershell
node tools\fetch-nuget.mjs --out .nuget-local      # picks the newest packs in the 8.0. band
```

---

## 9. Verified vs. not verified

**Verified on this machine** (120 + 32 automated checks, reproducible with the commands in §4):
security-policy units; filename/path hardening; protocol parsing and builders; certificate
generation, persistence and renewal; the real `wss://` handshake with fingerprint match (both from
.NET's `ClientWebSocket` and from an independent Node/OpenSSL client speaking raw RFC 6455);
one-time token, replay handling, IP binding, `busy`, rate limiting and the 3-strike ban over the
wire; hostile filenames end to end; byte-exact file transfer both directions; server ping / client
pong keepalive; real `SendInput` mouse movement, clicks, wheel and `SetCursorPos` centring; and the
WPF window starting, listening and rendering its QR code.

**Not verified here — please treat as "reviewed, never compiled":**

1. **The Android app has never been compiled or run** — this machine has no JDK and no Android SDK.
   It was written against the protocol document and reviewed line by line (API existence, resource
   and view-binding references, JSON field names, thread usage), but the first `assembleDebug` on a
   real machine is the moment of truth. The review's residual risks are listed in `android/README.md`.
2. **No real phone ↔ PC session.** Every end-to-end check used automated clients on this machine
   (loopback, and a genuine second source address for the IP-lock test). Camera scanning, the
   gyroscope, and touch gestures need hands-on testing.
3. **`.sln`-based builds** (see §8).

---

## 10. Protocol

`docs/PROTOCOL.md` is authoritative: message schemas, the six required message types plus the
additive ones (`hello`/`welcome`/`file_end`/`file_ack`/`error`/`ping`/`pong`), chunk size, close
codes (`1008` policy, `1013` busy, `1001` going away) and error codes (`bad_path`, `banned`,
`rate_limited`, `ip_locked`, `busy`, `token_invalid`, `token_expired`).

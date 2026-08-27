# Deployment

- **Client**: hosted on Vercel (user's own account), root directory
  `client`. Auto-builds and deploys on every push to `master` (repo's
  actual default branch — see "Real gotchas" below). Gives preview URLs
  on branches/PRs too. The `VITE_SERVER_URL` env var (set in the Vercel
  project's Environments -> Production page) points the built client at
  the game server's WebSocket endpoint. **Must be `wss://`, not `ws://`**
  (see below) — currently `wss://<deployed-domain>/ws` (see a teammate
  for the live value; kept out of this public doc). If unset,
  it falls back to `ws://localhost:6154/ws` for local dev.
- **TLS/domain — required, not optional.** Vercel always serves the
  client over HTTPS, and browsers block a plain `ws://` connection from
  an HTTPS page ("mixed content") — the client silently sits on
  "Connecting..." forever if the server URL is `ws://`. Current setup:
  a domain with a Let's Encrypt cert, with **nginx running on a separate
  machine** (not the VM itself) reverse-proxying
  `wss://<deployed-domain>/ws` -> the VM's internal IP on
  `:6154`. Nginx's WebSocket proxying needs `proxy_http_version 1.1` +
  `Upgrade`/`Connection` headers (or the equivalent "Websockets Support"
  toggle if using a panel like Nginx Proxy Manager) or the upgrade
  silently fails even though the port/routing is otherwise correct.
- **Server**: runs directly as a Java process (no Docker — the VM is a
  Windows 11 QEMU guest without nested virtualization passed through, so
  WSL2/Docker Desktop can't start there) on a friend's VM, listening on
  `:6154` (`/ws`).
  `.github/workflows/deploy-server.yml` auto-deploys on every push to
  `master` that touches `server/**` (or the workflow file itself): it
  SSHes into the VM (PowerShell as the remote shell) and runs
  `git pull`, rebuilds (`gradlew.bat installDist`), stops whatever's
  listening on port 6154 (`Get-NetTCPConnection` + `Stop-Process` — not
  `Get-CimInstance`, see below), and starts the freshly-built one via a
  **Scheduled Task** (not `Start-Process` — see "Fixed bug" below).

## Fixed bug: deployed server didn't stay running (fixed 2026-08-24)

The deploy workflow used to report success but the server wasn't
actually left running — `Get-NetTCPConnection -LocalPort 6154` on the
VM came back empty shortly after a "successful" deploy. Root cause:
`Start-Process -WindowStyle Hidden`-launched children get nested into
the same Windows Job Object as the SSH session that spawned them by
default, and sshd kills that whole job when the SSH session closes —
so the server process died the moment the deploy step's SSH connection
ended, even though `Start-Process` itself reported no error. Fixed by
launching via a Scheduled Task (`schtasks /Create` + `/Run`) instead,
which runs fully outside that job and survives the session closing —
confirmed working: the server stayed up and had live established
connections on port 6154 well after the deploying SSH session ended.

A second, unrelated issue surfaced while root-causing this: several
early fix attempts (quoting, routing through `cmd.exe /c`) were chasing
a red herring — the real problem was `$ErrorActionPreference = 'Stop'`
converting a completely benign `schtasks` stderr warning ("Task may not
run because /ST is earlier than current time" — irrelevant, since the
script immediately forces it with `/Run` anyway) into a terminating
PowerShell exception, aborting the script and masking the fact the task
creation was actually succeeding (exit code 0) the whole time. Worth
remembering generally: when a native command's failure message doesn't
match what the command should even be capable of failing at, suspect
`$ErrorActionPreference = 'Stop'` masking the real (possibly successful)
output rather than the command itself — scope it to `'Continue'` around
just that call and log the real output/exit code before assuming the
command itself is broken.

**Manual start commands** (only needed if the automated pipeline is
unavailable/being debugged) — run on the VM directly (RDP/console, or
an interactive SSH session kept open):
```
cd <path-to-repo-clone-on-VM>\server
.\gradlew.bat run
```
This runs in the foreground of that terminal and stays up as long as
the window/session stays open — leave it running rather than closing
the terminal. Confirm with `Get-NetTCPConnection -LocalPort 6154` from
another window.

## One-time manual setup (not automatable from here)

1. **Friend's VM** (Windows 11 Pro):
   - Install a Java 21 JDK (e.g. `winget install EclipseAdoptium.Temurin.21.JDK`).
   - Install Git for Windows (`winget install Git.Git`).
   - Forward/open **TCP port 6154** (game server) and **TCP port 22**
     (SSH, for the deploy workflow).
2. **Enable OpenSSH Server** (elevated PowerShell):
   ```
   Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
   Start-Service sshd
   Set-Service -Name sshd -StartupType 'Automatic'
   ```
   Set PowerShell as the default shell for incoming SSH sessions (the
   deploy workflow's script is PowerShell, not cmd/bash):
   ```
   New-ItemProperty -Path "HKLM:\SOFTWARE\OpenSSH" -Name DefaultShell `
     -Value "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" -PropertyType String -Force
   ```
3. **Deploy SSH key** (lets GitHub Actions log into the VM): generate a
   dedicated keypair, e.g. `ssh-keygen -t ed25519 -f C:\Users\<user>\.ssh\brutaltank_deploy_key -N ""`.
   A **non-admin** local account is simpler if available — a plain
   account's `authorized_keys` file lives at the normal
   `~/.ssh/authorized_keys`. **If the deploy user is an Administrator**
   (our actual case), Windows' `sshd` ignores that
   per-user file entirely and requires the key appended to
   `C:\ProgramData\ssh\administrators_authorized_keys` instead, with a
   locked-down ACL (only `Administrators` + `SYSTEM`):
   ```
   Get-Content <path-to-deploy-key>.pub | Add-Content -Encoding ASCII C:\ProgramData\ssh\administrators_authorized_keys
   icacls C:\ProgramData\ssh\administrators_authorized_keys /inheritance:r
   icacls C:\ProgramData\ssh\administrators_authorized_keys /grant "Administrators:F"
   icacls C:\ProgramData\ssh\administrators_authorized_keys /grant "SYSTEM:F"
   Restart-Service sshd
   ```
   Skipping this (or leaving the ACL looser than that) produces
   `ssh: unable to authenticate, attempted methods [none publickey], no
   supported methods remain` from GitHub Actions, even though the port
   is reachable and the key itself is correct.
4. **Repo deploy key** (lets the VM `git pull` this private repo,
   separate from the key above): generate a second keypair on the VM,
   add its public half as a read-only GitHub Deploy Key (repo → Settings
   → Deploy keys), and reference it via an SSH host alias so `git pull`
   uses it automatically. `git clone` the repo somewhere on the VM —
   that path is `VM_DEPLOY_PATH` below.
5. **GitHub repo secrets** (Settings -> Secrets and variables -> Actions):
   - `VM_HOST` - the VM's public IP/hostname
   - `VM_USER` - the deploy SSH user from step 3
   - `VM_SSH_KEY` - the deploy private key from step 3
   - `VM_DEPLOY_PATH` - absolute path to the repo clone on the VM
   Set these via the GitHub web UI (Settings -> Secrets and variables ->
   Actions -> New repository secret) — the **Secret** field takes only
   the raw value, not a CLI command; a common mistake is pasting a full
   `gh secret set ...` command into that box instead of just the value
   the command's `--body` would carry.
6. **Router port forwarding** (separate from the VM's own Windows
   Firewall rules in step 1 — both are required, confirming one does
   not confirm the other): forward external TCP 22 and TCP 6154 to the
   VM's internal LAN IP. A same-LAN SSH/connectivity test can give a
   false positive here (router "NAT hairpin" quietly loops your own
   public IP back internally even when true external forwarding isn't
   set up) — verify from a genuinely separate network, or with an
   independent external checker (e.g. `canyouseeme.org`), not from a
   device on the same WiFi/LAN as the VM.
7. **TLS/domain for the client to actually connect** (see "TLS/domain —
   required, not optional" above) — a domain with a valid cert reverse-
   proxying `wss://` to the VM's `:6154`. Confirm the exact WebSocket
   path (`/ws`, from `BrutalTankServer.java`'s `LOG.info` line) matches
   in both the proxy config and Vercel's `VITE_SERVER_URL`.
8. **Vercel**: import this repo under your own account (GitHub App
   access must be explicitly granted per-repo if using "Only select
   repositories," via github.com/settings/installations -> Vercel ->
   Configure), set root directory to `client`, and add the
   `VITE_SERVER_URL` env var (the `wss://` URL from step 7) under
   **Settings -> Environments -> Production** — not a top-level
   "Environment Variables" page, which moved into per-environment pages
   in Vercel's current UI. Redeploy after any env var change; it does
   not apply retroactively to already-built deployments.

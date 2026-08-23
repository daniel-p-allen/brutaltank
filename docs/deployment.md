# Deployment

- **Client**: hosted on Vercel (user's own account), root directory
  `client`. Auto-builds and deploys on every push to `main` (and gives
  preview URLs on branches/PRs). The `VITE_SERVER_URL` env var (set in
  the Vercel project settings) points the built client at the game
  server's WebSocket endpoint, e.g. `ws://<vm-ip>:6154/ws`. If unset, it
  falls back to `ws://localhost:6154/ws` for local dev.
- **Server**: runs directly as a Java process (no Docker — the VM is a
  Windows 11 QEMU guest without nested virtualization passed through, so
  WSL2/Docker Desktop can't start there) on a friend's VM, listening on
  `:6154` (`/ws`). No TLS/domain for now — plain `ws://` to the VM's IP,
  since this is a small private-game workload.
  `.github/workflows/deploy-server.yml` auto-deploys on every push to
  `main` that touches `server/**`: it SSHes into the VM (PowerShell as
  the remote shell) and runs `git pull`, rebuilds
  (`gradlew.bat installDist`), stops any previously-running server
  process (matched by command line, since it's not container-isolated),
  and starts the freshly-built one detached.

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
   Use a **non-admin** local account as the deploy user if possible — a
   plain account's `authorized_keys` file lives at the normal
   `~/.ssh/authorized_keys`. An admin account instead requires the key
   in `C:\ProgramData\ssh\administrators_authorized_keys` (with
   restricted ACLs), which is extra friction best avoided here.
4. **Repo deploy key** (lets the VM `git pull` this private repo,
   separate from the key above): generate a second keypair on the VM,
   add its public half as a read-only GitHub Deploy Key (repo → Settings
   → Deploy keys), and reference it via an SSH host alias so `git pull`
   uses it automatically. `git clone` the repo somewhere on the VM —
   that path is `VM_DEPLOY_PATH` below.
5. **GitHub repo secrets** (Settings -> Secrets and variables -> Actions):
   - `VM_HOST` - the VM's IP/hostname
   - `VM_USER` - the deploy SSH user from step 3
   - `VM_SSH_KEY` - the deploy private key from step 3
   - `VM_DEPLOY_PATH` - absolute path to the repo clone on the VM
6. **Vercel**: import this repo under your own account, set root
   directory to `client`, and add the `VITE_SERVER_URL` env var once the
   VM's address is known.

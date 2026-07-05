---
title: Ubuntu Server 24.04 LTS — Hardening
description: Ubuntu 24.04 LTS hardening — UFW, fail2ban, unattended-upgrades, SSH (no password, key-only), auditd, AppArmor, CIS Benchmark.
sidebar_position: 10
---

## Bu sayfa ne anlatıyor?

Lumix'in K3s cluster'larının altında **Ubuntu Server 24.04 LTS** (Noble Numbat) çalışır. Container security K8s seviyesinde ele alınır; ama **node OS'in kendisi sertleştirilmemişse** alttan boşluk açıktır. Bu sayfa Ubuntu Server'ı sıfırdan **production-grade hardening**'le anlatır: UFW firewall, fail2ban brute-force koruma, unattended-upgrades güvenlik patch otomasyonu, SSH sertleştirme (key-only, root login disable), auditd sistem audit, AppArmor profil zorlaması ve **CIS Benchmark** uyumu. Tüm adımlar **Ansible role'ler** halinde otomatize edilir. Hedef kitle: Linux temellerini bilen, sysadmin/DevOps.

## 1. Bu nedir? (Sıfırdan)

**Hardening**: işletim sistemini "default" güvenliğin ötesine taşımak. Gereksiz servisleri kapatmak, güvenlik mekanizmalarını açmak, log'u zorlamak, brute-force'a karşı korumak, patch sürecini otomatize etmek.

**Ubuntu 24.04 LTS** (Noble Numbat) — 2024 nisan çıkışı, **Nisan 2029'a kadar standart bakım, 2034'e kadar ESM (Pro lisansı ile)**. Lumix bu LTS hedefini 2026 itibariyle taşır.

Hardening araçlar yığını:
- **UFW** (Uncomplicated Firewall): iptables/nftables üzerine yüksek-seviye CLI.
- **fail2ban**: log tarayıp şüpheli IP'leri otomatik bloklar.
- **unattended-upgrades**: güvenlik güncellemelerini otomatik yükler.
- **SSH (OpenSSH)**: key-only, root login disable, MFA opsiyonel.
- **auditd**: kernel-level event audit (Linux Audit Framework).
- **AppArmor**: MAC (Mandatory Access Control) — uygulamaların ne yapabileceğini sınırlayan profiller.
- **CIS Benchmark**: Center for Internet Security tarafından yayınlanan harden checklist; **Ubuntu 24.04 CIS Benchmark v1.0** referans.

### Günlük hayattan analoji

Ev güvenliği: alarm sistemi (auditd), kapı kilidi seviyesi (SSH key-only), bahçe çiti (UFW), 5 kez yanlış şifre giren misafire 24 saat girişe izin yok (fail2ban), düzenli yangın söndürücü bakımı (unattended-upgrades). Her biri tek başına yetersiz; **layer-by-layer defense in depth**.

## 2. Hangi problemi çözüyor?

Default Ubuntu **production'a hazır değil**. Yaygın problemler:
- SSH root login açık → brute force her gün
- UFW kapalı → tüm portlar erişilebilir
- Otomatik güncellemeler kapalı → CVE'ler aylar bekler
- Audit log yok → incident sonrası "ne oldu" cevabı yok
- AppArmor sadece desktop profilleri ile → server uygulamaları unconfined

| Acı | Hardening'siz | Hardening'li |
|---|---|---|
| Brute force SSH | log dolar, sonunda zayıf parola çözülür | fail2ban + key-only login |
| CVE yamalanması | Manuel `apt upgrade` aylar gecikir | unattended-upgrades otomatik |
| Açık port keşfi | İçeriden enum ile risk | UFW default deny |
| Suspicious file access | İz yok | auditd kayıt |
| Compromise edilmiş binary | İstediği gibi syscall | AppArmor MAC |
| Compliance audit | "Konuşalım" | CIS Benchmark report |

### Patlamış üretim hikayesi

Bir takım sıradan Ubuntu 22.04 üzerine K3s kurdu. SSH root login açık, sshd port 22 dünyaya açık. 48 saat sonra `/var/log/auth.log` 200K+ failed login attempt; sonunda zayıf bir test kullanıcısının parolası çözüldü; saldırgan içeride miner kurdu, CPU 100%, müşteri operasyonu durdu. fail2ban + key-only login + 2222 portu birleşince saldırı yüzeyi %99 düşer.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Katmanlı savunma stratejisi

```
┌──────────────────────────────────────────────────┐
│  Network: UFW (iptables/nftables)                │
│  • default deny incoming                         │
│  • allow: 22(SSH), 6443(K3s API),                │
│           80/443(node-port),                     │
│           VXLAN(K3s flannel), wireguard          │
└──────────────────────────────────────────────────┘
                       ▼
┌──────────────────────────────────────────────────┐
│  Authentication: SSH                             │
│  • key-only, root disabled, MFA opsiyonel        │
│  • fail2ban → 5 fail = 24h IP block              │
└──────────────────────────────────────────────────┘
                       ▼
┌──────────────────────────────────────────────────┐
│  Authorization: sudo + AppArmor                  │
│  • NOPASSWD sadece spesifik komut                │
│  • AppArmor enforce mode                         │
└──────────────────────────────────────────────────┘
                       ▼
┌──────────────────────────────────────────────────┐
│  Detection: auditd + journald                    │
│  • execve, openat, setuid kayıt                  │
│  • Promtail → Loki                               │
└──────────────────────────────────────────────────┘
                       ▼
┌──────────────────────────────────────────────────┐
│  Maintenance: unattended-upgrades                │
│  • security pocket otomatik                      │
│  • livepatch (Ubuntu Pro) opsiyonel              │
└──────────────────────────────────────────────────┘
```

### 3.2. UFW basitleştirilmiş firewall

UFW iptables (veya 24.04'te nftables) için yüksek-seviye command:

```bash
ufw default deny incoming
ufw default allow outgoing
ufw limit 22/tcp           # SSH brute force rate-limit
ufw allow 6443/tcp         # K3s API
ufw allow 80,443/tcp       # node-port
ufw allow from 10.0.0.0/8 to any proto vxlan
ufw enable
```

### 3.3. SSH hardening

`/etc/ssh/sshd_config.d/99-lumix.conf`:

```
Port 2222
Protocol 2

PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
ChallengeResponseAuthentication no
UsePAM yes

PubkeyAuthentication yes
AuthorizedKeysFile .ssh/authorized_keys

PermitEmptyPasswords no
MaxAuthTries 3
MaxSessions 5
LoginGraceTime 30

AllowUsers lumix-admin
AllowGroups lumix-ops

ClientAliveInterval 300
ClientAliveCountMax 2

X11Forwarding no
AllowAgentForwarding no
AllowTcpForwarding no
PermitTunnel no

LogLevel VERBOSE
```

Public key dağıtımı **Ansible** ile, Vault'tan public key listesi çekilir.

### 3.4. fail2ban

`/etc/fail2ban/jail.d/lumix.conf`:

```
[DEFAULT]
bantime = 24h
findtime = 10m
maxretry = 5
backend = systemd
banaction = ufw
ignoreip = 127.0.0.1/8 10.0.0.0/8

[sshd]
enabled = true
port = 2222
filter = sshd
logpath = %(sshd_log)s

[recidive]
enabled = true
bantime = 7d
findtime = 1d
maxretry = 3
```

`recidive` jail: tekrarlayan saldırganı uzun süreli ban'lar.

### 3.5. unattended-upgrades

`/etc/apt/apt.conf.d/50unattended-upgrades`:

```
Unattended-Upgrade::Allowed-Origins {
    "${distro_id}:${distro_codename}-security";
    "${distro_id}ESMApps:${distro_codename}-apps-security";
    "${distro_id}ESM:${distro_codename}-infra-security";
};

Unattended-Upgrade::Package-Blacklist {
    "linux-image-";        # kernel manuel kontrol
    "linux-headers-";
};

Unattended-Upgrade::DevRelease "auto";
Unattended-Upgrade::AutoFixInterruptedDpkg "true";
Unattended-Upgrade::MinimalSteps "true";
Unattended-Upgrade::Mail "ops-alerts@lumix.io";
Unattended-Upgrade::MailReport "on-change";
Unattended-Upgrade::Remove-Unused-Kernel-Packages "true";
Unattended-Upgrade::Remove-Unused-Dependencies "true";
Unattended-Upgrade::Automatic-Reboot "false";   # K3s drain ile yapılır
```

K3s node'larının auto-reboot **kapalı**. Reboot disiplini System Upgrade Controller ile yapılır (drain + cordon + reboot).

### 3.6. auditd

`/etc/audit/rules.d/lumix.rules`:

```
# /etc/passwd, /etc/shadow değişiklikleri
-w /etc/passwd -p wa -k passwd_changes
-w /etc/shadow -p wa -k shadow_changes
-w /etc/sudoers -p wa -k sudoers_changes
-w /etc/sudoers.d -p wa -k sudoers_changes

# SSH config
-w /etc/ssh/sshd_config -p wa -k sshd_config
-w /etc/ssh/sshd_config.d -p wa -k sshd_config

# kubernetes config
-w /etc/rancher/k3s -p wa -k k3s_config
-w /var/lib/rancher/k3s/server -p wa -k k3s_server_data

# Kernel module load/unload
-w /sbin/insmod -p x -k modules
-w /sbin/rmmod  -p x -k modules
-w /sbin/modprobe -p x -k modules

# Setuid/setgid execution
-a always,exit -F arch=b64 -S execve -F euid=0 -F auid>=1000 -F auid!=4294967295 -k privileged_exec

# Container runtime invocations
-w /usr/local/bin/k3s -p x -k k3s_exec

# Make audit immutable until reboot
-e 2
```

`-e 2`: audit kuralları kilitlenir; saldırgan reboot'sız değiştiremez.

Log: `/var/log/audit/audit.log` → Promtail → Loki.

### 3.7. AppArmor

Ubuntu 24.04'te default açık. Lumix profilleri:
- containerd profil: K8s container'ları için (k3s embed).
- node-exporter, promtail için strict profiller.
- Custom uygulamalar için profilling.

Profil aktifleştirme:
```bash
sudo aa-enforce /etc/apparmor.d/usr.local.bin.k3s
sudo aa-status
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Baseline imaj

Lumix kendi **Packer / cloud-init image**'ı tutar:
- Ubuntu 24.04 LTS minimal
- Hardening baseline (aşağıdaki Ansible role'ler)
- Pre-installed: `qemu-guest-agent`, `cloud-init`, `chrony`, `jq`
- SSH key: bootstrap public key (sonra Ansible ile rotate)

Tüm yeni node'lar bu imajdan açılır → Ansible playbook kuruluma devam eder.

### 4.2. Ansible hardening role

```
roles/ubuntu-hardening/
├── tasks/
│   ├── main.yml
│   ├── ufw.yml
│   ├── ssh.yml
│   ├── fail2ban.yml
│   ├── unattended.yml
│   ├── auditd.yml
│   ├── apparmor.yml
│   ├── kernel.yml
│   └── filesystem.yml
├── templates/
│   ├── sshd_config.d/99-lumix.conf.j2
│   ├── fail2ban/lumix.conf.j2
│   ├── auditd/lumix.rules.j2
│   ├── 50unattended-upgrades.j2
│   └── sysctl-lumix.conf.j2
└── defaults/main.yml
```

### 4.3. Sysctl hardening

`/etc/sysctl.d/99-lumix.conf`:

```
# Network hardening
net.ipv4.ip_forward = 1                    # K3s gerekli
net.bridge.bridge-nf-call-iptables = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.conf.all.send_redirects = 0
net.ipv4.conf.all.accept_redirects = 0
net.ipv4.conf.all.accept_source_route = 0
net.ipv4.conf.default.accept_source_route = 0
net.ipv4.conf.all.log_martians = 1
net.ipv4.icmp_echo_ignore_broadcasts = 1
net.ipv4.icmp_ignore_bogus_error_responses = 1
net.ipv4.tcp_syncookies = 1
net.ipv4.tcp_rfc1337 = 1

# Kernel
kernel.dmesg_restrict = 1
kernel.kptr_restrict = 2
kernel.unprivileged_bpf_disabled = 1
kernel.yama.ptrace_scope = 2
kernel.randomize_va_space = 2

# Filesystem
fs.protected_hardlinks = 1
fs.protected_symlinks = 1
fs.protected_fifos = 2
fs.protected_regular = 2

# K3s/Container friendly
vm.swappiness = 1
vm.max_map_count = 262144                  # Elasticsearch
fs.inotify.max_user_watches = 524288       # Container watchers
fs.file-max = 2097152
```

### 4.4. CIS Benchmark uyum

Lumix kendi **scoring** scripti: `ansible-cis-ubuntu` role'üne dayanır (örn. `florianutz/ubuntu_2404_cis` veya OpenSCAP). Her node için aylık report:

```
CIS Ubuntu Linux 24.04 Benchmark v1.0.0
Section 1: Initial Setup           PASS 18/18
Section 2: Services                PASS 25/27 (2 N/A)
Section 3: Network Configuration   PASS 12/12
Section 4: Logging and Auditing    PASS  9/9
Section 5: Access, Authentication  PASS 22/22
Section 6: System Maintenance      PASS  8/8
Total: 94/96 (2 N/A: containerd-related exceptions)
```

### 4.5. Erişim modeli

| Hesap | Kullanım |
|---|---|
| `lumix-admin` | Ansible bootstrap kullanıcısı; SSH key login; sudo: `ALL=(ALL) NOPASSWD: ALL` (kısıtlı süre, sonra rotate) |
| `lumix-ops` | İnsan operatörlerin gerçek kullanıcıları; SSH key + sudo |
| `k3s` | systemd service user, login shell yok |
| `root` | Login disabled, sadece `sudo -i` |

SSH bastion: Lumix VPN üzerinden tek bir bastion → müşteri node'larına. Direkt internet'ten SSH yok.

### 4.6. Log forwarding

Tüm önemli log'lar Promtail ile Loki'ye:
- `/var/log/auth.log` (auth attempts)
- `/var/log/audit/audit.log` (auditd)
- `/var/log/fail2ban.log`
- `/var/log/unattended-upgrades/*.log`
- `/var/log/ufw.log`
- `journalctl -u sshd` (systemd unit'leri)

Retention: 90 gün ılık (Loki); sonra RustFS soğuk arşiv.

### 4.7. Time sync

`chrony` ile NTP. K8s sertifika ve audit log timestamp tutarlılığı için kritik.

```
# /etc/chrony/chrony.conf parça
pool tr.pool.ntp.org iburst maxsources 4
pool time.cloudflare.com iburst maxsources 2
makestep 1.0 3
rtcsync
keyfile /etc/chrony/chrony.keys
```

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Debian** | Ubuntu daha geniş ekosistem + LTS + ESM + Canonical Pro support. |
| **CentOS Stream / Rocky / Alma** | RHEL ekosistem; Lumix Ubuntu standardı (ekip bilgisi, container baseline). |
| **Talos Linux** | Minimal OS + K8s tek paket. Avantaj: ufak attack surface. Dezavantaj: standart Ubuntu administration toolchain'i yok; ekip öğrenmeli; Vault Ansible role'leri çalışmaz. Lumix VPS sahibi olarak OS bağımsızlığını korumayı tercih etti. **Tekrar değerlendirme tetikleyici**: minimal OS güçlü çekim noktası olursa. |
| **Fedora CoreOS** | Otomatik update + immutable; avantaj büyük ama Ansible workflow uyumsuz. |
| **NixOS** | Reproducible config; öğrenme eğrisi yüksek. |
| **Windows Server** | Lumix K8s + Linux container target; uygulamasız. |

### Kabul ettiğimiz trade-off'lar

- **Mutable OS**: Talos kadar minimal değil; ek paketler attack surface. Karşılığında: Ansible workflow olgun.
- **Manuel patch disiplini gerekiyor**: unattended-upgrades sadece security pocket; kernel manuel kontrolde.
- **Ubuntu Pro maliyeti (opsiyonel)**: Livepatch ve ESM Pro lisans ister. Lumix internal node'lar için Pro; müşteri node'ları için opsiyonel.

### Tekrar değerlendirme tetikleyicileri

- Müşteri sayısı 500+ olursa OS bakımı zorlaşır → Talos veya Flatcar Linux yeniden değerlendirilebilir.
- Compliance regülasyon (örn. PCI-DSS DC-level) immutable OS isterse → Talos.

## 6. Pratik örnek

### 6.1. Ansible playbook iskeleti

```yaml
- name: Ubuntu 24.04 hardening
  hosts: all
  become: true
  vars:
    ssh_port: 2222
    allowed_ssh_users: ["lumix-admin", "lumix-ops"]
    fail2ban_bantime: 24h
    fail2ban_maxretry: 5
  roles:
    - ubuntu-hardening
    - { role: monitoring-agent, vars: { promtail_enabled: true } }
```

### 6.2. `tasks/ssh.yml`

```yaml
- name: SSH config dosyasını dağıt
  ansible.builtin.template:
    src: sshd_config.d/99-lumix.conf.j2
    dest: /etc/ssh/sshd_config.d/99-lumix.conf
    owner: root
    group: root
    mode: "0644"
    validate: "/usr/sbin/sshd -t -f %s"
  notify: restart sshd

- name: Eski default config'i devre dışı bırak
  ansible.builtin.replace:
    path: /etc/ssh/sshd_config
    regexp: '^#?PermitRootLogin.*$'
    replace: 'PermitRootLogin no'
  notify: restart sshd

- name: lumix-admin kullanıcı oluştur
  ansible.builtin.user:
    name: lumix-admin
    groups: [sudo, lumix-ops]
    shell: /bin/bash
    state: present

- name: Authorized keys dağıt
  ansible.posix.authorized_key:
    user: lumix-admin
    state: present
    key: "{{ item }}"
    exclusive: true
  loop: "{{ lookup('community.hashi_vault.vault_kv2_get', 'secret/lumix/ssh-keys').secret.admin_keys }}"
  no_log: true

- name: UFW SSH portunu aç
  community.general.ufw:
    rule: limit
    port: "{{ ssh_port }}"
    proto: tcp
```

### 6.3. `tasks/ufw.yml`

```yaml
- name: UFW kurulu
  ansible.builtin.apt:
    name: ufw
    state: present

- name: UFW default policy
  community.general.ufw:
    direction: "{{ item.direction }}"
    policy: "{{ item.policy }}"
  loop:
    - { direction: incoming, policy: deny }
    - { direction: outgoing, policy: allow }
    - { direction: routed, policy: allow }

- name: K3s portları
  community.general.ufw:
    rule: allow
    port: "{{ item.port }}"
    proto: "{{ item.proto }}"
    src: "{{ item.src | default(omit) }}"
  loop:
    - { port: 6443, proto: tcp }                # K3s API
    - { port: 10250, proto: tcp, src: 10.0.0.0/8 }   # Kubelet
    - { port: 8472, proto: udp, src: 10.0.0.0/8 }   # Flannel VXLAN
    - { port: "80,443", proto: tcp }            # Ingress
    - { port: 2379, proto: tcp, src: 10.0.0.0/8 }   # etcd peers
    - { port: 2380, proto: tcp, src: 10.0.0.0/8 }

- name: UFW etkinleştir
  community.general.ufw:
    state: enabled
```

### 6.4. `tasks/fail2ban.yml`

```yaml
- name: fail2ban kurulu
  ansible.builtin.apt:
    name: fail2ban
    state: present

- name: jail dosyası
  ansible.builtin.template:
    src: fail2ban/lumix.conf.j2
    dest: /etc/fail2ban/jail.d/lumix.conf
    mode: "0644"
  notify: restart fail2ban

- name: fail2ban aktif
  ansible.builtin.service:
    name: fail2ban
    state: started
    enabled: true
```

### 6.5. CIS scan komutu

```bash
# OpenSCAP
sudo apt install -y libopenscap8 ssg-base ssg-debderived
sudo oscap xccdf eval --profile xccdf_org.ssgproject.content_profile_cis_level1_server \
  --results /var/log/oscap-results.xml \
  --report /var/log/oscap-report.html \
  /usr/share/xml/scap/ssg/content/ssg-ubuntu2404-ds.xml
```

### 6.6. Hızlı sağlık kontrolü

```bash
# UFW status
sudo ufw status verbose

# SSH config validate
sudo sshd -t

# fail2ban jail durumu
sudo fail2ban-client status
sudo fail2ban-client status sshd

# Audit kurallar
sudo auditctl -l | head

# AppArmor
sudo aa-status | head

# Unattended son çalıştırma
sudo journalctl -u unattended-upgrades --since="-3 days"

# Açık port listesi
sudo ss -tulnp
```

## 7. Dikkat edilecek tuzaklar

- **UFW açmadan önce SSH portunu allow etmemek**: bağlantı kesilir, sunucu erişilmez. Ansible role'de **önce allow sonra enable** sırası kritik.
- **fail2ban'ın admin IP'lerini ignore etmemesi**: kendi ofis IP'sini banlar. `ignoreip = 127.0.0.1/8 10.0.0.0/8 <office-cidr>`.
- **SSH port değişikliği DNS güncelleme unutmak**: bastion config'i eski portla. Coordinated change.
- **`PermitRootLogin no` ama sudoers ALL=NOPASSWD**: root login yok ama sudoers ile etkin root erişim. NOPASSWD'i sınırla.
- **Unattended kernel update + K3s drain yok**: node restart sırasında pod'lar kaybolur. Kernel update **manuel sequence** ile.
- **auditd disk dolduğunda paniğe geçmesi**: default `disk_full_action=SUSPEND` → sistem yavaşlar. `/etc/audit/auditd.conf`: `disk_full_action=ROTATE`.
- **AppArmor profil broke etmek**: yanlış profil = uygulama başlamaz. Önce `aa-complain` (sadece log) → test → `aa-enforce`.
- **Logları sadece local tutmak**: saldırgan silebilir. Promtail/syslog forward + immutable storage (Loki + RustFS).
- **NTP/chrony unutmak**: sertifikalar geçerlilik kontrolü için clock skew kritik; saat uyumsuzsa K3s mTLS, audit timestamp bozulur.
- **Bastion'ı tek nokta hata yapmak**: bastion düşerse müşterilere SSH yok. Bastion HA + break-glass account.
- **`sudo` log'larını tutmamak**: kim ne komutla sudo çekti, kayıt yok. `Defaults logfile=/var/log/sudo.log, log_input, log_output`.
- **`/tmp` ayrı partition değil + noexec yok**: tmpdir'den binary çalıştırmaya açık. Mount: `tmpfs /tmp tmpfs defaults,noexec,nosuid,nodev,size=2G`.

## 8. Diğer konularla ilişkisi

- [K3s](./k3s-lightweight-k8s) — Hardened OS üzerine K3s install
- [NetworkPolicy + mTLS](./networkpolicy-mtls) — cluster içi savunma; OS-level firewall ondan önce
- [Ansible Basics](../20-iac-provisioning/ansible-basics) — hardening role nasıl yazılır
- [Customer Onboarding Pipeline](../20-iac-provisioning/customer-onboarding-pipeline) — node bootstrap adımı
- [Observability](../observability-qa) — log forwarding (Loki) ve audit metric
- [Velero Backup](./velero-backup) — node state backup'a etkisi

## 9. Daha derine inmek için

- Ubuntu 24.04 hardening guide: [https://ubuntu.com/security/certifications/docs/disa-stig](https://ubuntu.com/security/certifications/docs/disa-stig)
- CIS Benchmark Ubuntu 24.04 (PDF — CIS üyeliği veya WorkBench)
- "Linux Hardening Guide" — Madaidan
- "How to Configure Linux Auditing" — Red Hat docs (auditd kavramları geçerli)
- Search keyword'leri: *"ubuntu 24.04 cis benchmark"*, *"auditd rules best practices"*, *"apparmor profile creation"*, *"fail2ban systemd backend"*, *"ufw k3s ports"*
- Lumix engineering-notes referansı: `21-kubernetes-helm-infra-devops-engineering-guide.md`

## 10. Sözlük

- **Hardening**: Default OS güvenliğini production seviyesine çıkarma süreci.
- **UFW (Uncomplicated Firewall)**: Ubuntu'nun iptables/nftables CLI wrapper'ı.
- **fail2ban**: Log tarayıp ban listesine IP ekleyen daemon.
- **unattended-upgrades**: Güvenlik patch'lerini otomatik yükleyen servis.
- **auditd**: Linux Audit Framework daemon'u (kernel-level event log).
- **AppArmor**: Path-based MAC; uygulama davranışı sınırlandırma.
- **MAC (Mandatory Access Control)**: SELinux/AppArmor gibi kernel-level erişim kontrol.
- **CIS Benchmark**: Center for Internet Security'nin OS sertleştirme rehberi.
- **OpenSCAP**: CIS/STIG taraması yapan açık kaynak araç.
- **Ubuntu Pro**: Canonical'ın ücretli destek + ESM + Livepatch paketi.
- **Livepatch**: Kernel patch'ini reboot etmeden uygulayan teknoloji.
- **Bastion host**: SSH erişiminin tek toplandığı, sıkı izlenen ara sunucu.
- **CVE (Common Vulnerabilities and Exposures)**: Bilinen güvenlik açığı ID sistemi.
- **chrony**: Modern NTP istemcisi.

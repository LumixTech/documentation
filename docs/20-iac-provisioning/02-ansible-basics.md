---
title: Ansible Temelleri
description: Ansible nedir, agentless, idempotent, inventory + playbook + role, Lumix kullanımı — OS setup (UFW, K3s install) ve customer seed (Keycloak, Kafka, Vault).
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

Lumix'te **Terraform** bulut kaynaklarını (VM, network, DNS) hazırlar; ardından **Ansible** o makinelere **OS-level yapılandırmayı** (kullanıcı, firewall, K3s install, hardening) ve **uygulama seviyesi seed**'i (Keycloak realm, Kafka topic, Vault path, ilk DB tenant) yapar. Bu sayfa Ansible'ı sıfırdan anlatır, **agentless + idempotent** prensiplerini açıklar, **inventory / playbook / role / module / handler / Jinja2** kavramlarını gösterir, Lumix'in **OS setup + customer seed** sorumluluk paylaşımını detaylandırır. Hedef kitle: shell script bilen, ilk kez Ansible kullanan mühendis.

## 1. Bu nedir? (Sıfırdan)

**Ansible**, Python tabanlı, **agentless** (uzak host'a ajan kurmaz) ve **idempotent** (aynı playbook'u N kere çalıştırmak aynı sonuç) bir **configuration management + automation** aracı. SSH üzerinden uzak makinelere bağlanır, Python module'leri çalıştırır.

Temel kavramları:
- **Inventory**: Hangi host'lar var, hangi grupta (YAML veya INI).
- **Playbook**: Yapılacak işler listesi (tasks); host group'a uygulanır.
- **Task**: Tek bir adım (paket kur, dosya kopyala, servis başlat).
- **Module**: Görevi gerçekleştiren Python kod birimi (`apt`, `copy`, `service`, `lineinfile`).
- **Role**: Yeniden kullanılabilir task paketi (defaults, tasks, templates, handlers).
- **Handler**: Bir task değişiklik yaparsa tetiklenen yan task (örn. config değişince servisi restart).
- **Variables**: Inventory, role defaults, playbook vars, ekstra var.
- **Jinja2 template**: Variable substitution + condition + loop ile dinamik dosya.
- **Vault (Ansible Vault)**: Hassas variable'ları şifreleme.
- **Collection**: Module + role + plugin paketi (Ansible Galaxy).

### Günlük hayattan analoji

İnsan asistana checklist veriyorsun: "1. Apartmana git. 2. Salondaki ampul yanmıyorsa değiştir. 3. Mutfak temizse atla, kirliyse temizle." Asistan her madde için bakıyor — gerek varsa yapıyor (idempotent). Aynı checklist'i ertesi gün gönderirsen yalnızca o gün değişen şeyler yapılır.

## 2. Hangi problemi çözüyor?

Terraform sonrası VM hazır ama:
- SSH ile gir
- `apt update && apt install -y ufw fail2ban`
- `ufw allow 22 && ufw enable`
- K3s installer indir, çalıştır
- ...

Bunlar manuel: insan hatası, tekrarlanabilir değil, 3 müşteride sıkıcı.

| Acı | Ansible'sız | Ansible'lı |
|---|---|---|
| 30 sunucuyu aynı şekilde kur | SSH × 30 | Tek `ansible-playbook` |
| Config drift | Eninde sonunda farklılaşır | Periodic playbook re-run düzeltir |
| Yeni mühendis onboarding | "Şu listeyi takip et" | `ansible-playbook bootstrap.yml` |
| Multi-host orkestrasyon (rolling update) | Manuel sıra | `serial: 1`, handler order |
| Secret yönetimi | `.env` dosyaları | Ansible Vault + HashiCorp Vault lookup |
| Customer seed | Manuel SQL/API curl | Playbook + module |

### Patlamış üretim hikayesi

Bir takım K3s'i her sunucuya manuel kurdu. Bir sunucuda `--tls-san` flag'ini unuttu. Production'da bir gün API uzaktan erişilebilir olmadı (sertifika SAN'ı VIP yok). 3 saatlik debug. Ansible playbook olsaydı: flag merkezi template'te tanımlı, tüm sunucular birebir aynı.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Agentless çalışma

Ansible kontrol makinesinden (laptop veya CI runner) SSH ile uzak host'a bağlanır:
1. Python module dosyasını uzak host'a kopyalar (`/tmp/ansible-tmp-xxx/`).
2. Uzak Python ile çalıştırır.
3. Sonucu JSON ile geri alır.
4. Geçici dosyayı siler.

Önemli: uzak host'ta **Python 3** olmalı (Ubuntu 24.04 default ile gelir).

### 3.2. Idempotency

Her module fiili durumla hedef durumu karşılaştırır:
- `apt: name=ufw state=present` → ufw zaten yüklüyse atla.
- `service: name=ssh state=restarted` → her zaman restart eder (idempotent değil — handler ile kontrol).
- `lineinfile: line=...` → satır zaten varsa atla.

Idempotent değil action'lar: `command`, `shell`. Mümkünse module kullan; `command` kullanırken `creates`/`removes` argümanı ile idempotency sağla.

### 3.3. Inventory

YAML inventory:
```yaml
all:
  children:
    k3s_servers:
      hosts:
        cp-1:
          ansible_host: 10.0.0.11
        cp-2:
          ansible_host: 10.0.0.12
    k3s_agents:
      hosts:
        worker-1:
          ansible_host: 10.0.0.21
    keycloak:
      hosts:
        cp-1:
  vars:
    ansible_user: lumix-admin
    ansible_port: 2222
    ansible_ssh_private_key_file: ~/.ssh/id_ed25519_lumix
    ansible_python_interpreter: /usr/bin/python3
```

`group_vars/`, `host_vars/` ile değişkenler.

### 3.4. Playbook yapısı

```yaml
- name: K3s cluster kurulumu
  hosts: k3s_servers
  become: true
  gather_facts: true
  serial: 1   # tek seferde 1 host
  pre_tasks:
    - name: SSH bağlantısı verify
      ansible.builtin.ping:
  roles:
    - { role: ubuntu-hardening }
    - { role: k3s-server, vars: { cluster_init: "{{ inventory_hostname == groups['k3s_servers'][0] }}" } }
  post_tasks:
    - name: API hazır olmasını bekle
      ansible.builtin.uri:
        url: https://localhost:6443/healthz
        validate_certs: false
      retries: 30
      delay: 5
```

### 3.5. Role anatomisi

```
roles/k3s-server/
├── defaults/main.yml     # default değişkenler
├── vars/main.yml         # role-level var
├── tasks/main.yml        # ana task listesi
├── handlers/main.yml     # tetiklenen handler'lar
├── templates/            # Jinja2 template dosyaları
├── files/                # plain (template olmayan) dosyalar
├── meta/main.yml         # dependency, platform
└── README.md
```

### 3.6. Handler

```yaml
# tasks
- name: SSH config dağıt
  template:
    src: sshd_config.j2
    dest: /etc/ssh/sshd_config.d/99-lumix.conf
  notify: restart sshd

# handlers
- name: restart sshd
  service:
    name: ssh
    state: restarted
```

Birden fazla task aynı handler'ı notify ederse handler **bir kez** çalışır (play sonunda).

### 3.7. Jinja2 template

```jinja
# sshd_config.j2
Port {{ ssh_port | default(22) }}
PermitRootLogin no
{% for user in allowed_ssh_users %}
AllowUsers {{ user }}
{% endfor %}
{% if mfa_enabled %}
AuthenticationMethods publickey,keyboard-interactive
{% endif %}
```

### 3.8. Ansible Vault (sırlar)

```bash
ansible-vault encrypt group_vars/all/secrets.yml
ansible-playbook playbook.yml --ask-vault-pass
# veya
ansible-playbook playbook.yml --vault-password-file ~/.vault_pass
```

Lumix tercihi: **HashiCorp Vault** + `community.hashi_vault` lookup plugin. Ansible Vault'a token yazmayı azaltıyoruz; sırlar tek yerden gelsin.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Sorumluluk ayrımı

| Katman | Araç |
|---|---|
| VM provisioning (cloud kaynakları) | Terraform |
| OS hardening + paket kurulum | Ansible |
| K3s install + cluster bootstrap | Ansible |
| Helm app deploy | ArgoCD |
| Customer seed (Keycloak realm, Vault path, Kafka topic, ilk DB tenant) | Ansible |

Ansible **operasyonel** seviyede kalır; uygulamaların manifest'leri ArgoCD'dedir.

### 4.2. Klasör yapısı

```
ansible/
├── ansible.cfg
├── inventories/
│   ├── lumix-internal/
│   │   ├── hosts.yml
│   │   └── group_vars/all.yml
│   ├── omer-okullari/
│   │   ├── hosts.yml          (Terraform output'tan üretilir)
│   │   ├── group_vars/all.yml
│   │   └── host_vars/
│   └── x-vakfi/
├── playbooks/
│   ├── 00-bootstrap.yml       (yeni VM'i lumix-admin'e hazırla)
│   ├── 01-hardening.yml
│   ├── 02-k3s-install.yml
│   ├── 03-rancher-import.yml
│   ├── 04-bootstrap-cluster-addons.yml  (cert-manager, ESO, Velero)
│   ├── 05-customer-seed.yml
│   └── 99-day2-patch.yml
└── roles/
    ├── ubuntu-hardening/
    ├── k3s-server/
    ├── k3s-agent/
    ├── rancher-import/
    ├── customer-seed-keycloak/
    ├── customer-seed-kafka/
    ├── customer-seed-vault/
    └── customer-seed-postgres/
```

### 4.3. `ansible.cfg`

```ini
[defaults]
inventory = ./inventories
roles_path = ./roles
host_key_checking = False
retry_files_enabled = False
forks = 10
stdout_callback = yaml
callbacks_enabled = profile_tasks
interpreter_python = auto_silent
collections_path = ./.ansible/collections

[ssh_connection]
pipelining = True
ssh_args = -o ControlMaster=auto -o ControlPersist=60s
```

### 4.4. Hangi roller? (Lumix standart)

#### `ubuntu-hardening`
UFW, fail2ban, unattended-upgrades, SSH config, auditd, AppArmor, sysctl, chrony. Detay: [Ubuntu Hardening](../infra-devops/10-ubuntu-server-hardening.md).

#### `k3s-server` / `k3s-agent`
K3s binary install, systemd unit, Vault token lookup, cluster join. Detay: [K3s](../infra-devops/02-k3s-lightweight-k8s.md).

#### `customer-seed-keycloak`
Müşteri için Keycloak realm oluştur (eğer Keycloak kullanılıyorsa); admin user, client, realm role'leri.

#### `customer-seed-kafka`
İlk topic'leri yarat (`identity.user.v1`, `academic.attendance.v1`, vs.), schema register et.

#### `customer-seed-vault`
Vault'ta müşteri path'ini aç, ilk secret'ları seed et (DB password, encryption key).

#### `customer-seed-postgres`
İlk tenant kaydı (`tenant_id`), RLS policy aktivasyonu, audit user.

### 4.5. Inventory üretimi (Terraform → Ansible)

Terraform output'undan inventory üretimi:

```bash
terraform output -raw ansible_inventory > inventories/omer-okullari/hosts.ini
```

Veya Ansible dynamic inventory (Terraform state'i okuyan):

```yaml
# inventories/omer-okullari/inventory.tf-output.yaml
plugin: cloud.terraform.terraform_state
backend_type: http
backend_config:
  address: https://gitlab.lumix.io/api/v4/projects/123/terraform/state/omer-okullari
```

### 4.6. Vault lookup

```yaml
- name: K3s node token Vault'tan al
  ansible.builtin.set_fact:
    k3s_node_token: "{{ lookup('community.hashi_vault.vault_kv2_get', 'secret/lumix/k3s/' ~ customer_id).secret.node_token }}"
  no_log: true
```

Vault token CI ortamında `VAULT_TOKEN` env var; lokal'de `vault login`.

### 4.7. Lumix kuralları

- **Roller idempotent**: 5 kere çalıştır, hep aynı sonuç.
- **`command`/`shell` kullanım**: `creates`/`removes` arg ile idempotent yap, veya `changed_when` ile durumu netleştir.
- **`no_log: true`** secret görünür satırda.
- **Tag'ler**: `--tags hardening`, `--tags k3s`, `--tags seed` ile partial run.
- **Test**: `molecule` ile her role'ün CI testi.
- **Ansible version**: 2.16+; collection'lar versiyon kilitli.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Chef / Puppet** | Agent gerektirir; OS-level bağımlılık. Kurulumu kompleks. |
| **SaltStack** | Master-agent; özel port + bootstrap karmaşık. |
| **Bash scripts** | Idempotent değil; secret yönetimi yok; modülerlik zayıf. |
| **Cloud-init only** | Tek seferlik; sonradan değişiklik yok. Bootstrap için OK, ongoing config için yetersiz. |
| **Pyinfra** | Hafif Python alternatif; topluluk küçük. |
| **Nix / NixOS** | Reproducible ama öğrenme eğrisi yüksek. |
| **Crossplane** | K8s-native ama K3s kurmadan kullanamazsın (yumurta-tavuk). |

### Kabul ettiğimiz trade-off'lar

- **Performans (SSH per-task)**: pipelining + SSH ControlMaster ile ~3x hızlanır; yine Salt kadar hızlı değil. Lumix'in scale'inde OK.
- **YAML syntax verbose**: Jinja2 dolaşmalı template'ler okumayı zorlaştırır.
- **Test ekosistemi** (molecule) Chef/Puppet kadar olgun değil.

### Tekrar değerlendirme tetikleyicileri

- Sunucu sayısı 500+ olursa SSH overhead'i problem olabilir → Salt veya Mass-deploy pattern.
- Çok dinamik provisioning ihtiyacı doğarsa → Crossplane (K8s zaten kuruluyken).

## 6. Pratik örnek

### 6.1. Bootstrap playbook

```yaml
- name: New VM bootstrap (initial cloud-init kullanıcı dışında)
  hosts: all
  gather_facts: false
  vars:
    ansible_user: root            # cloud-init default
    ansible_port: 22
  pre_tasks:
    - name: Python kontrol
      ansible.builtin.raw: which python3 || (apt-get update && apt-get install -y python3)
      changed_when: false
  tasks:
    - name: lumix-admin grubu
      ansible.builtin.group:
        name: lumix-ops
        state: present

    - name: lumix-admin kullanıcı
      ansible.builtin.user:
        name: lumix-admin
        groups: [sudo, lumix-ops]
        shell: /bin/bash
        state: present

    - name: Sudo NOPASSWD
      ansible.builtin.copy:
        dest: /etc/sudoers.d/lumix-admin
        content: "lumix-admin ALL=(ALL) NOPASSWD: ALL"
        mode: "0440"
        validate: "/usr/sbin/visudo -cf %s"

    - name: Authorized keys
      ansible.posix.authorized_key:
        user: lumix-admin
        state: present
        key: "{{ item }}"
        exclusive: false
      loop: "{{ lookup('community.hashi_vault.vault_kv2_get', 'secret/lumix/ssh-keys').secret.admin_keys_list }}"
      no_log: true
```

### 6.2. Customer seed — Keycloak realm

```yaml
- name: Keycloak realm seed
  hosts: keycloak
  become: false
  collections:
    - middleware_automation.keycloak
  vars:
    keycloak_url: "https://keycloak.{{ customer_id }}.lumix.io"
    keycloak_admin_user: admin
    keycloak_admin_password: "{{ lookup('community.hashi_vault.vault_kv2_get', 'secret/lumix/' ~ customer_id ~ '/keycloak').secret.admin_password }}"
  tasks:
    - name: Realm oluştur
      community.general.keycloak_realm:
        auth_keycloak_url: "{{ keycloak_url }}"
        auth_username: "{{ keycloak_admin_user }}"
        auth_password: "{{ keycloak_admin_password }}"
        auth_realm: master
        id: "{{ customer_id }}"
        realm: "{{ customer_id }}"
        state: present
        enabled: true
        login_theme: lumix
        ssl_required: external
        access_token_lifespan: 900
        refresh_token_max_reuse: 0

    - name: Lumix client
      community.general.keycloak_client:
        auth_keycloak_url: "{{ keycloak_url }}"
        auth_username: "{{ keycloak_admin_user }}"
        auth_password: "{{ keycloak_admin_password }}"
        auth_realm: master
        realm: "{{ customer_id }}"
        client_id: lumix-web
        protocol: openid-connect
        public_client: false
        redirect_uris:
          - "https://admin.{{ customer_id }}.lumix.io/*"
        state: present
```

### 6.3. Customer seed — Kafka topic

```yaml
- name: Kafka topic seed
  hosts: kafka_bootstrap
  vars:
    bootstrap_servers: "kafka-0.lumix-data:9092"
    topics:
      - { name: "identity.user.v1", partitions: 6, replication_factor: 3, configs: { "min.insync.replicas": "2", "compression.type": "lz4" } }
      - { name: "academic.attendance.v1", partitions: 12, replication_factor: 3, configs: { "min.insync.replicas": "2" } }
      - { name: "audit.events.v1", partitions: 6, replication_factor: 3, configs: { "retention.ms": "31536000000" } }
      - { name: "outbox.dlq.v1", partitions: 3, replication_factor: 3 }
  tasks:
    - name: Topic oluştur
      ansible.builtin.shell: |
        kafka-topics.sh --bootstrap-server {{ bootstrap_servers }} \
          --create --if-not-exists \
          --topic {{ item.name }} \
          --partitions {{ item.partitions }} \
          --replication-factor {{ item.replication_factor }} \
          {% for k, v in item.configs.items() %}--config {{ k }}={{ v }} {% endfor %}
      loop: "{{ topics }}"
      register: result
      changed_when: "'Created topic' in result.stdout"
```

### 6.4. Customer seed — Vault path

```yaml
- name: Vault customer path seed
  hosts: localhost
  connection: local
  vars:
    customer_id: "{{ inventory_customer_id }}"
  tasks:
    - name: KV path oluştur (boş)
      community.hashi_vault.vault_kv2_write:
        url: https://vault.lumix.io
        path: "secret/lumix/{{ customer_id }}/init"
        data:
          customer_id: "{{ customer_id }}"
          provisioned_at: "{{ ansible_date_time.iso8601 }}"
          version: "1.0"

    - name: DB password generate ve write
      community.hashi_vault.vault_kv2_write:
        url: https://vault.lumix.io
        path: "secret/lumix/{{ customer_id }}/{{ item }}/db"
        data:
          username: "{{ item }}_user"
          password: "{{ lookup('community.general.random_string', length=32, special=true) }}"
          jdbcUrl: "jdbc:postgresql://postgres-{{ item }}.lumix-data:5432/{{ item }}"
      loop:
        - identity
        - organization
        - academic
        - finance
        - file
        - audit
        - compliance
      no_log: true
```

### 6.5. Tam akış run

```bash
# Terraform infra
cd terraform/customers/omer-okullari
terraform apply
terraform output -raw ansible_inventory > ../../../ansible/inventories/omer-okullari/hosts.ini

# Ansible bootstrap
cd ../../../ansible
ansible-playbook -i inventories/omer-okullari playbooks/00-bootstrap.yml
ansible-playbook -i inventories/omer-okullari playbooks/01-hardening.yml
ansible-playbook -i inventories/omer-okullari playbooks/02-k3s-install.yml
ansible-playbook -i inventories/omer-okullari playbooks/03-rancher-import.yml
ansible-playbook -i inventories/omer-okullari playbooks/04-bootstrap-cluster-addons.yml

# ArgoCD app sync (manuel)
argocd app create lumix-platform-omer-okullari ...
argocd app sync lumix-platform-omer-okullari

# Customer seed
ansible-playbook -i inventories/omer-okullari playbooks/05-customer-seed.yml
```

### 6.6. Molecule role test (örnek)

```yaml
# roles/ubuntu-hardening/molecule/default/molecule.yml
dependency:
  name: galaxy
driver:
  name: docker
platforms:
  - name: instance
    image: ubuntu:24.04
    pre_build_image: false
provisioner:
  name: ansible
verifier:
  name: ansible
```

```bash
cd roles/ubuntu-hardening
molecule test
```

## 7. Dikkat edilecek tuzaklar

- **`become: true` her task'a tek tek koymak**: play-level `become: true` daha temiz.
- **`shell` modülünü idempotency düşünmeden kullanmak**: aynı playbook 5 kere çalışırsa 5 kere apt update. `creates`/`removes` veya idempotent module.
- **`gather_facts: true`** büyük inventory'de zaman alır. Gerek yoksa kapat.
- **`no_log` unutmak**: token output'ta görünür → CI log'una sızar.
- **`when:` koşulları çakışan**: `when: item.state == 'present' and not skip`. Liste comprehension gibi okunur.
- **Inventory'de IP ve user mismatch**: bootstrap'te root, sonra lumix-admin. `pre_tasks` ile değişim akışı düşün.
- **Vault lookup expensive**: her task'ta tekrar fetch. `set_fact` ile bir kez al, sonra reuse.
- **Aynı role'ün farklı versiyon dependency'leri**: collection version kilidi (`requirements.yml`).
- **`serial` olmadan tüm cluster'a aynı anda K3s upgrade**: cluster çöker. `serial: 1`.
- **Handler order önemli**: `meta: flush_handlers` ile play ortasında zorla tetikle.
- **`copy` vs `template` karıştırmak**: dinamik içerik için template; static için copy.
- **`with_items` (deprecated) vs `loop`**: yeni kod hep `loop`.
- **Local state asla**: idempotency state olmamalı (her zaman gerçek host kontrolü).

## 8. Diğer konularla ilişkisi

- [Terraform Basics](./01-terraform-basics.md) — Ansible öncesi infra
- [Customer Onboarding Pipeline](./03-customer-onboarding-pipeline.md) — uçtan uca akış
- [K3s](../infra-devops/02-k3s-lightweight-k8s.md) — K3s install role
- [Ubuntu Hardening](../infra-devops/10-ubuntu-server-hardening.md) — hardening role
- [Rancher Multi-Cluster](../infra-devops/04-rancher-multi-cluster.md) — Rancher import role
- [Vault](../security-compliance) — secret lookup
- [GitLab CI Pipelines](../21-ci-cd/02-gitlab-ci-pipelines.md) — Ansible pipeline

## 9. Daha derine inmek için

- Resmi doc: [https://docs.ansible.com/](https://docs.ansible.com/)
- "Ansible for DevOps" — Jeff Geerling (klasik)
- Ansible Galaxy collections
- Molecule test framework
- Search keyword'leri: *"ansible idempotent shell creates"*, *"ansible vault vs hashicorp vault"*, *"ansible handler flush_handlers"*, *"ansible dynamic inventory terraform"*, *"ansible molecule role test"*

## 10. Sözlük

- **Ansible**: Agentless, idempotent config management aracı (Red Hat).
- **Inventory**: Host listesi ve grup tanımları.
- **Playbook**: Host group'a uygulanan task listesi (YAML).
- **Task**: Tek bir adım/modül çağrısı.
- **Module**: Ansible'ın görevi gerçekleştiren Python birimi (`apt`, `copy`, `service`).
- **Role**: Yeniden kullanılabilir task paketi (tasks, templates, handlers, defaults).
- **Handler**: Bir task değişiklik yapınca tetiklenen yan task.
- **Jinja2**: Ansible'ın template motoru.
- **Ansible Vault**: Hassas variable'ları şifreleme.
- **Collection**: Module + role + plugin paketi (Ansible Galaxy).
- **Galaxy**: Ansible'ın paket repository'si.
- **Idempotency**: Aynı işlemi N kere uygulamak aynı sonucu verir.
- **`become`**: Sudo gibi privilege escalation.
- **`gather_facts`**: Uzak host hakkında bilgi toplama (OS, IP, disk).
- **Pipelining**: SSH command'ları tek bağlantıda gönderme optimizasyonu.
- **Molecule**: Ansible role'leri için test framework.
- **Dynamic inventory**: Inventory'i runtime'da dış kaynaktan (Terraform, AWS, vb.) üretmek.

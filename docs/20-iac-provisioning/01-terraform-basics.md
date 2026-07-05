---
title: Terraform Temelleri
description: Terraform nedir, declarative IaC, provider/resource/state, plan/apply, Lumix kullanımı — VPS provisioning (Hetzner, AWS, DigitalOcean), DNS, network.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Lumix yeni müşteri kurulumunda **VPS provisioning, DNS, network** katmanını **Terraform** ile yönetir. Bu sayfa Terraform'u sıfırdan anlatır, **declarative IaC** felsefesini, **provider / resource / state** modelini, `plan/apply` lifecycle'ını ve Lumix'in **çoklu sağlayıcı adapter pattern**'ini (Hetzner, AWS, DigitalOcean, on-prem) detaylandırır. Modüler yapı, state yönetimi (remote backend), workspace, drift detection da ele alınır. Hedef kitle: bash/Ansible bilen, IaC kavramına yeni giren mühendis.

## 1. Bu nedir? (Sıfırdan)

**Terraform**, HashiCorp tarafından geliştirilen, **infrastructure as code** (IaC) aracı. Bulut/sunucu kaynaklarını (VM, network, DNS, DB, IAM…) **HCL** (HashiCorp Configuration Language) ile **declarative** olarak tanımlamayı sağlar.

- **Declarative**: "şu kadar VM olsun" dersin; Terraform mevcut state ile fark hesaplar ve **fark kadar değişiklik** uygular.
- **Provider**: Her bulut/araç için ayrı plugin (aws, azurerm, google, hetznercloud, digitalocean, cloudflare).
- **Resource**: Bir provider'ın yönettiği nesne (`hcloud_server`, `cloudflare_record`).
- **Data source**: Mevcut kaynağı **okumak** (kontroldışı, sadece referans).
- **State**: Hangi resource gerçek dünyada hangi ID'ye sahip — `terraform.tfstate` dosyasında.
- **Module**: Tekrar kullanılabilir Terraform paket.
- **Workspace**: Aynı kodun farklı state'leri (dev/staging/prod).

### Günlük hayattan analoji

İnşaat planı (HCL) çizersin: "2. katta 3 oda, biri yatak odası, biri ofis…". Müteahhit (Terraform) plana bakar, mevcut yapıyı (state) plan ile karşılaştırır, sadece fark olan yerleri inşa eder. Plan değişirse: tüm yapı tekrar yıkılmaz; sadece etkilenen odalar yeniden düzenlenir.

## 2. Hangi problemi çözüyor?

Manuel olarak müşteri kurulumu:
- Bulut paneli üzerinden VM aç, IP not al
- DNS A record manuel ekle
- Firewall kuralı manuel
- Tekrar yapıldığında insan hatası garantili

| Acı | Terraform'suz | Terraform'lu |
|---|---|---|
| Yeni müşteri provisioning | Manuel panel klik | `terraform apply` |
| Drift (kaynak ayarı manuel değişti) | İz bırakmadan kaybolur | `terraform plan` farkı gösterir |
| Sağlayıcı değiştirmek | Tüm prosedür baştan | Provider değişimi + module re-use |
| Audit trail | Email/log dağınık | Git history |
| Reproducible env | Manuel checklist | `terraform apply` ile aynı |
| Cost takip | Manuel sayım | Spec'ten otomatik |

### Patlamış üretim hikayesi

Manuel kurulumla 14 müşteri açıldı; biri test, biri prod, biri yedek. Her müşteri için "TF kuralları yarın yazarız" denildi. Bir gün biri "üretim VM'ine SSH key eklemeyi unuttuk" fark etti — hangi müşterilerde unutuldu, hangilerinde yok? Manuel envanter günler aldı. Terraform olsaydı: tek değişiklik tek `apply`, hepsi tutarlı.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Lifecycle

```
1. terraform init       → provider'ları indir, backend'i hazırla
2. terraform fmt        → formatla
3. terraform validate   → syntax check
4. terraform plan       → mevcut state ile karşılaştır, diff göster
5. terraform apply      → diff'i uygula, state güncelle
6. (gerekirse) terraform destroy
```

### 3.2. State

`terraform.tfstate` JSON dosyası; içinde resource → real-world ID eşlemesi.
- **Local state**: dev için. Tek kullanıcı.
- **Remote state**: ekip için. S3, Consul, Terraform Cloud, GitLab managed state.
- **Locking**: aynı anda iki kişi apply etmesin (S3 + DynamoDB, GitLab built-in lock).

Lumix kararı: **GitLab-managed Terraform state** (her project için built-in). Veya S3-compatible (RustFS) + DynamoDB-compatible (RustFS lock yok → Consul lock).

### 3.3. Resource graph

Terraform resource'ları arasındaki bağımlılıkları DAG olarak çözer. Örnek:
- Önce `hcloud_network` oluşturulur.
- Sonra `hcloud_server` (network'e bağlı).
- Sonra `cloudflare_record` (server IP'sini kullanır).

Bağımlılıklar **explicit** (`depends_on`) veya **implicit** (output referansı).

### 3.4. Provider mantığı

Provider, bulutun API'sini Terraform resource'larına çeviren plugin. Her resource için CRUD:
- Create: yeni resource oluştur.
- Read: gerçek durumu çek (drift detection için).
- Update: değişiklikleri uygula (mümkünse in-place).
- Delete: kaldır.

Bazı resource değişikliği "force replace" (yeni kaynak + eski silinir).

### 3.5. Module

```
modules/
  vps-cluster/
    main.tf              # K3s server + agent VM tanımı
    variables.tf         # inputs (node_count, region, ssh_key)
    outputs.tf           # IP'ler, kubeconfig path
    versions.tf          # required_providers
```

Kullanım:
```hcl
module "omer-okullari" {
  source = "./modules/vps-cluster"
  customer_id = "omer-okullari"
  region = "eu-central"
  node_count_servers = 3
  node_count_agents = 2
}
```

### 3.6. Workspace

```
terraform workspace new omer-okullari
terraform workspace select omer-okullari
terraform apply   # ayrı state file
```

Lumix kararı: **workspace yerine ayrı state dosyaları + ayrı GitLab projeleri** (her müşteri için clarity). Workspace küçük setup'larda kullanılır.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Provider seti

Her müşteri kurulum aşamasında provider seçimi yapılır:

| Provider | Kullanım |
|---|---|
| `hetznercloud/hcloud` | Avrupa müşterileri (UCloud, Hetzner) |
| `digitalocean/digitalocean` | Çevik müşteriler, küçük tier |
| `hashicorp/aws` | AWS tercih eden büyük müşteriler |
| `cloudflare/cloudflare` | DNS (tüm müşteriler için ortak) |
| `hashicorp/vault` | Vault'a node token yazmak |
| `community-terraform-providers/proxmox` | On-prem Proxmox VM (bazı müşteriler kendi sunucularını verir) |

### 4.2. Klasör yapısı

```
infra/
├── modules/
│   ├── vps-cluster-hetzner/       # Hetzner-spesifik VPS module
│   ├── vps-cluster-aws/           # AWS EC2 module
│   ├── vps-cluster-digitalocean/  # DO Droplet module
│   ├── vps-cluster-proxmox/       # On-prem
│   ├── dns-cloudflare/            # DNS records module
│   └── common/                    # ortak label, tag, output convention
├── customers/
│   ├── omer-okullari/
│   │   ├── main.tf                # provider seçer, module çağırır
│   │   ├── backend.tf             # state backend
│   │   ├── terraform.tfvars       # değerler (secret'lar Vault'tan)
│   │   └── outputs.tf
│   ├── x-vakfi/
│   └── y-okul/
└── shared/
    └── cloudflare-zones/          # Lumix'in lumix.io zone'u
```

### 4.3. Sağlayıcı adapter pattern

Her müşteri klasörü provider'a göre uygun module'ü çağırır:

```hcl
# customers/omer-okullari/main.tf
module "cluster" {
  source = "../../modules/vps-cluster-hetzner"
  customer_id = "omer-okullari"
  tier = "m"
  ssh_keys = data.vault_generic_secret.ssh_keys.data["public_keys"]
  region = "fsn1"
}

module "dns" {
  source = "../../modules/dns-cloudflare"
  customer_id = "omer-okullari"
  subdomain = "omer-okullari"
  zone = "lumix.io"
  node_ips = module.cluster.public_ips
}
```

Aynı `vps-cluster-hetzner` modülü provider değişirse `vps-cluster-aws` ile değiştirilir; modüller **aynı output shape**'ini sunar (`public_ips`, `kubeconfig_path`).

### 4.4. State backend (GitLab)

```hcl
# customers/omer-okullari/backend.tf
terraform {
  backend "http" {
    address        = "https://gitlab.lumix.io/api/v4/projects/123/terraform/state/omer-okullari"
    lock_address   = "https://gitlab.lumix.io/api/v4/projects/123/terraform/state/omer-okullari/lock"
    unlock_address = "https://gitlab.lumix.io/api/v4/projects/123/terraform/state/omer-okullari/lock"
    lock_method    = "POST"
    unlock_method  = "DELETE"
    retry_wait_min = 5
  }
}
```

GitLab token CI variable olarak gelir; lokal çalıştırma için GitLab user token.

### 4.5. Secret yönetimi

- API token'lar (Hetzner, AWS, Cloudflare): **Vault'ta**, `vault_generic_secret` data source ile çekilir veya CI env var.
- SSH public key'ler: Vault'ta liste.
- `terraform.tfvars` Git'e **gider ama secret içermez** (only references).

### 4.6. Drift detection

CI'da haftalık `terraform plan -detailed-exitcode`:
- Exit 0: değişiklik yok.
- Exit 2: drift var → uyarı, manuel inceleme.
- Exit 1: hata.

Drift kaynakları:
- Birisi panel'den manuel değiştirmiş.
- Provider yeni feature default'u değişmiş.
- IP rotation (Hetzner bazen IP değiştirir).

### 4.7. Hangi sınırlar?

Terraform Lumix'te **sadece infra-level**: VM provisioning + DNS + network + firewall. **OS-level config Ansible'a bırakılır** (bkz. [Ansible Basics](./02-ansible-basics.md)). K8s manifest deploy Helm + ArgoCD'ye bırakılır.

Bu ayrım önemli: Terraform'un içine OS provisioning (cloud-init script vb.) gömmek mümkün ama bakım zorlaşır.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **OpenTofu** (Terraform fork, MPL) | Lisans korkusuysa fork'a geçilebilir; ama Lumix HCP olmadan Terraform'u kullanıyor; OSS lisansla şu an OK. **Tekrar değerlendirme aday** (Terraform BSL/Open lisans değişimi olursa). |
| **Pulumi** | TypeScript/Go/Python ile IaC. Code-flexibility avantaj ama HCL'in declarative netliği daha güvenli ops için. |
| **AWS CloudFormation** | AWS-only. Lumix multi-provider hedefler. |
| **Crossplane** | K8s-native IaC; K8s zaten yokken bootstrap için yumurta-tavuk problemi. |
| **Ansible (sadece)** | Imperative yapı drift detection için zayıf. Lumix: Terraform infra, Ansible OS. |
| **Bicep / ARM** | Azure-only. |

### Kabul ettiğimiz trade-off'lar

- **HCL öğrenme**: Java/JS ekibi için yeni syntax; ama declarative netliği değer.
- **State file kritikliği**: bozulursa felaket. Remote backend + state backup zorunlu.
- **Provider API rate limit**: bazı bulutlarda 50 req/dakika sınırı; çok kaynaklı `apply` yavaş.

### Tekrar değerlendirme tetikleyicileri

- Terraform Business Source License (BSL) değişimi bizim kullanımı etkilerse → OpenTofu.
- Developer-driven IaC (typed dilde refactor) ekipte ihtiyaç doğurursa → Pulumi.

## 6. Pratik örnek

### 6.1. Hetzner VPS cluster module

```hcl
# modules/vps-cluster-hetzner/variables.tf
variable "customer_id" {
  type = string
}
variable "tier" {
  type = string
  validation {
    condition     = contains(["xs", "s", "m", "l"], var.tier)
    error_message = "tier must be xs|s|m|l"
  }
}
variable "region" {
  type    = string
  default = "fsn1"
}
variable "ssh_keys" {
  type = list(string)
}

locals {
  topology = {
    xs = { servers = 1, agents = 0, server_type = "cx21", agent_type = null }
    s  = { servers = 1, agents = 1, server_type = "cx21", agent_type = "cx21" }
    m  = { servers = 3, agents = 2, server_type = "cx31", agent_type = "cx41" }
    l  = { servers = 3, agents = 5, server_type = "cx41", agent_type = "cpx51" }
  }
  cfg = local.topology[var.tier]
}
```

```hcl
# modules/vps-cluster-hetzner/main.tf
terraform {
  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.45"
    }
  }
}

resource "hcloud_ssh_key" "admin" {
  for_each   = toset(var.ssh_keys)
  name       = "lumix-${var.customer_id}-${substr(sha1(each.value), 0, 6)}"
  public_key = each.value
}

resource "hcloud_network" "main" {
  name     = "lumix-${var.customer_id}"
  ip_range = "10.10.0.0/16"
  labels = {
    "lumix.io/customer"    = var.customer_id
    "lumix.io/managed-by"  = "terraform"
  }
}

resource "hcloud_network_subnet" "private" {
  network_id   = hcloud_network.main.id
  type         = "cloud"
  network_zone = "eu-central"
  ip_range     = "10.10.1.0/24"
}

resource "hcloud_firewall" "main" {
  name = "lumix-${var.customer_id}"
  rule {
    direction = "in"
    protocol  = "tcp"
    port      = "2222"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
  rule {
    direction = "in"
    protocol  = "tcp"
    port      = "80"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
  rule {
    direction = "in"
    protocol  = "tcp"
    port      = "443"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
  rule {
    direction = "in"
    protocol  = "tcp"
    port      = "6443"
    source_ips = ["10.0.0.0/8"]
  }
}

resource "hcloud_server" "control_plane" {
  count       = local.cfg.servers
  name        = "lumix-${var.customer_id}-cp-${count.index + 1}"
  image       = "ubuntu-24.04"
  server_type = local.cfg.server_type
  location    = var.region
  ssh_keys    = [for k in hcloud_ssh_key.admin : k.id]
  firewall_ids = [hcloud_firewall.main.id]

  labels = {
    "lumix.io/customer"   = var.customer_id
    "lumix.io/role"       = "control-plane"
    "lumix.io/tier"       = var.tier
  }

  user_data = templatefile("${path.module}/cloud-init.yaml", {
    hostname = "lumix-${var.customer_id}-cp-${count.index + 1}"
  })

  network {
    network_id = hcloud_network.main.id
  }

  depends_on = [hcloud_network_subnet.private]
}

resource "hcloud_server" "agent" {
  count       = local.cfg.agents
  name        = "lumix-${var.customer_id}-worker-${count.index + 1}"
  image       = "ubuntu-24.04"
  server_type = local.cfg.agent_type
  location    = var.region
  ssh_keys    = [for k in hcloud_ssh_key.admin : k.id]
  firewall_ids = [hcloud_firewall.main.id]

  labels = {
    "lumix.io/customer" = var.customer_id
    "lumix.io/role"     = "worker"
    "lumix.io/tier"     = var.tier
  }

  network {
    network_id = hcloud_network.main.id
  }
}
```

```hcl
# modules/vps-cluster-hetzner/outputs.tf
output "control_plane_ips" {
  value = hcloud_server.control_plane[*].ipv4_address
}
output "agent_ips" {
  value = hcloud_server.agent[*].ipv4_address
}
output "all_ips" {
  value = concat(hcloud_server.control_plane[*].ipv4_address, hcloud_server.agent[*].ipv4_address)
}
output "network_id" {
  value = hcloud_network.main.id
}
output "ansible_inventory" {
  value = templatefile("${path.module}/inventory.tpl", {
    cp_ips    = hcloud_server.control_plane[*].ipv4_address
    agent_ips = hcloud_server.agent[*].ipv4_address
  })
}
```

### 6.2. Cloudflare DNS module

```hcl
# modules/dns-cloudflare/main.tf
terraform {
  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 4.30"
    }
  }
}

data "cloudflare_zone" "lumix" {
  name = var.zone
}

resource "cloudflare_record" "api" {
  zone_id = data.cloudflare_zone.lumix.id
  name    = "api.${var.subdomain}"
  value   = var.node_ips[0]
  type    = "A"
  ttl     = 300
  proxied = false
}

resource "cloudflare_record" "admin" {
  zone_id = data.cloudflare_zone.lumix.id
  name    = "admin.${var.subdomain}"
  value   = var.node_ips[0]
  type    = "A"
  ttl     = 300
  proxied = false
}

resource "cloudflare_record" "k8s_api" {
  zone_id = data.cloudflare_zone.lumix.id
  name    = "k8s-api.${var.subdomain}"
  value   = var.node_ips[0]
  type    = "A"
  ttl     = 300
  proxied = false
}
```

### 6.3. Customer entry: `customers/omer-okullari/main.tf`

```hcl
terraform {
  required_version = ">= 1.5.0"
  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.45"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 4.30"
    }
    vault = {
      source  = "hashicorp/vault"
      version = "~> 4.0"
    }
  }
}

provider "hcloud" {
  token = data.vault_generic_secret.hetzner.data["api_token"]
}
provider "cloudflare" {
  api_token = data.vault_generic_secret.cloudflare.data["api_token"]
}

data "vault_generic_secret" "hetzner"    { path = "secret/lumix/providers/hetzner" }
data "vault_generic_secret" "cloudflare" { path = "secret/lumix/providers/cloudflare" }
data "vault_generic_secret" "ssh_keys"   { path = "secret/lumix/ssh-keys" }

module "cluster" {
  source      = "../../modules/vps-cluster-hetzner"
  customer_id = "omer-okullari"
  tier        = "m"
  region      = "fsn1"
  ssh_keys    = data.vault_generic_secret.ssh_keys.data["admin_keys_list"]
}

module "dns" {
  source       = "../../modules/dns-cloudflare"
  customer_id  = "omer-okullari"
  subdomain    = "omer-okullari"
  zone         = "lumix.io"
  node_ips     = module.cluster.all_ips
}
```

### 6.4. CI'da Terraform pipeline

```yaml
# .gitlab-ci.yml parça
stages: [validate, plan, apply]

variables:
  TF_ROOT: customers/${CUSTOMER_ID}
  TF_ADDRESS: ${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/terraform/state/${CUSTOMER_ID}

before_script:
  - cd $TF_ROOT
  - terraform init
       -backend-config="address=${TF_ADDRESS}"
       -backend-config="lock_address=${TF_ADDRESS}/lock"
       -backend-config="unlock_address=${TF_ADDRESS}/lock"
       -backend-config="username=gitlab-ci-token"
       -backend-config="password=${CI_JOB_TOKEN}"

terraform-validate:
  stage: validate
  script:
    - terraform fmt -check
    - terraform validate

terraform-plan:
  stage: plan
  script:
    - terraform plan -out=plan.cache -detailed-exitcode
  artifacts:
    paths: [customers/${CUSTOMER_ID}/plan.cache]
    expire_in: 1 day

terraform-apply:
  stage: apply
  when: manual
  script:
    - terraform apply -input=false plan.cache
```

### 6.5. Hızlı komutlar

```bash
# Lokal dev
cd customers/omer-okullari
terraform init
terraform fmt -recursive
terraform validate
terraform plan
terraform apply
terraform output ansible_inventory > ../../ansible/inventory/omer-okullari/hosts.ini

# Drift detection
terraform plan -detailed-exitcode

# Specific resource taint (next apply yeniden oluşturur)
terraform taint module.cluster.hcloud_server.control_plane[0]

# State içeriği görmek
terraform state list
terraform state show 'module.cluster.hcloud_server.control_plane[0]'
```

## 7. Dikkat edilecek tuzaklar

- **State'i Git'e koymak**: secret sızıntı + lock yok. Remote backend zorunlu.
- **`-target` ile selective apply**: state drift'e davetiye. Sadece acil müdahale, sonra düzgün apply.
- **`terraform destroy` prod'da**: kazara komut → 30 dakika sonra müşteri yok. **Manuel onay + protection** (CI'da `when: manual` ve `environment: production`).
- **Provider versiyon kilidi olmayan kod**: `~> 1.45` zorunlu; sonraki `init` patch alır ama major almaz.
- **Variable validation yok**: tier="xxl" diye yanlış input → cryptic hata. `validation` blok ekle.
- **`for_each` yerine `count` + index değişimi**: list'in ortasına eleman ekleyince state'in tüm index'leri kayar → tüm resource'lar replace. `for_each` (map ile) tercih.
- **Lokal `provider` block + `variable` token**: token tfstate'e sızar. `data` source veya env var (TF_VAR_...) tercih.
- **Sensitive output mark etmek**: `output { sensitive = true }` zorunlu (kubeconfig vb.).
- **Multiple workspace + tek backend yanlış kullanımı**: state karışıklığı. Lumix workspace'i ayrı project ile değiştirdi.
- **`apply` sırasında network bağlantısı kopması**: state inconsistent. `terraform refresh` sonra yeniden plan.
- **Terraform module'ünü yeniden kullanım için tasarlamamak**: provider-spesifik logic gizli kalır; ayırt et (HCL + locals'ta tier matrix).
- **Cloud-init script çok büyük**: state file şişer. Script kısa tut, gerçek setup Ansible'a.
- **Manuel panel değişikliği yapıp Git'i hatırlamamak**: drift birikir. CI'da haftalık plan + Slack alert.

## 8. Diğer konularla ilişkisi

- [Ansible Basics](./02-ansible-basics.md) — Terraform sonrası OS config
- [Customer Onboarding Pipeline](./03-customer-onboarding-pipeline.md) — uçtan uca akış
- [K3s](../infra-devops/02-k3s-lightweight-k8s.md) — Terraform VPS hazırlar, K3s Ansible kurar
- [Ubuntu Hardening](../infra-devops/10-ubuntu-server-hardening.md) — Terraform cloud-init ile başlatır, Ansible tamamlar
- [License Management](./04-license-management.md) — yeni cluster + license bağlama
- [Vault](../security-compliance) — provider token'ları, SSH key listesi
- [GitLab CI Pipelines](../21-ci-cd/02-gitlab-ci-pipelines.md) — Terraform pipeline

## 9. Daha derine inmek için

- Resmi doc: [https://developer.hashicorp.com/terraform/docs](https://developer.hashicorp.com/terraform/docs)
- "Terraform: Up & Running" — Yevgeniy Brikman
- HashiCorp Learn: [https://developer.hashicorp.com/terraform/tutorials](https://developer.hashicorp.com/terraform/tutorials)
- Provider'lar: registry.terraform.io
- "OpenTofu vs Terraform" tartışmaları
- Search keyword'leri: *"terraform remote state gitlab"*, *"terraform module for_each"*, *"terraform drift detection"*, *"terraform plan exit code 2"*, *"hetzner terraform provider"*
- Lumix engineering-notes referansı: `22-terraform-iac-engineering-training-series.md`

## 10. Sözlük

- **IaC (Infrastructure as Code)**: Altyapıyı kod ile tanımlama; reproducible.
- **Terraform**: HashiCorp'un declarative IaC aracı.
- **HCL (HashiCorp Configuration Language)**: Terraform'un syntax dili.
- **Provider**: Bir bulut/araç için Terraform plugin (aws, hcloud, cloudflare).
- **Resource**: Provider'ın yönettiği bir gerçek dünya nesnesi.
- **Data source**: Mevcut kaynağı okumak için kullanılan referans (read-only).
- **State (`.tfstate`)**: Resource → gerçek ID eşlemesi tutan dosya.
- **Remote backend**: State'i merkezi yerde tutmak (S3, GitLab, Consul).
- **Lock**: Aynı state üzerinde aynı anda apply yapılmasını önleyen mekanizma.
- **Module**: Tekrar kullanılabilir Terraform kod paketi.
- **Workspace**: Aynı kodun farklı state'leri (alternatif).
- **`plan`**: Mevcut state ile istenen state arasındaki diff.
- **`apply`**: Plan'ı uygulamak.
- **`refresh`**: State'i gerçek dünyaya göre güncellemek (drift detection).
- **`taint`**: Bir resource'u "next apply'da yeniden oluştur" olarak işaretlemek.
- **`for_each` vs `count`**: Çoklu resource üretme; for_each map için stable identity sağlar.
- **OpenTofu**: Terraform'un MPL fork'u (lisans değişimi sonrası).

---
title: "Terraform ve Infrastructure as Code Eğitim Serisi"
description: Infrastructure as Code, Terraform temelleri, provider, variable, output, workflow, module boundary ve production trade-off'ları için mühendislik eğitim notu.
sidebar_position: 9
---

## Eğitim Rolü

Bu eğitim serisi, Infrastructure as Code ve Terraform konularını mühendislik disipliniyle ele almak için tasarlanmıştır.

Anlatım rolü:

```text
Distinguished Engineering Professor
```

Odak noktası:

- Infrastructure as Code felsefesi,
- Terraform temel bileşenleri,
- state yönetimi,
- modüler altyapı mimarisi,
- production-ready Terraform workflow,
- reproducibility,
- control,
- scalability,
- trade-off analizi.

## Müfredat

### Modül 1: IaC Felsefesi ve Terraform Temelleri

Kapsam:

- Infrastructure as Code mantığı,
- declarative infrastructure yaklaşımı,
- Terraform workflow,
- providers,
- variables,
- outputs,
- resource lifecycle,
- plan/apply mantığı.

### Modül 2: State Yönetimi

Kapsam:

- Terraform state nedir?
- local state riskleri,
- remote state,
- state locking,
- consistency problemleri,
- drift detection,
- sensitive data in state.

### Modül 3: Modüler Mimari

Kapsam:

- VPC modülü,
- database modülü,
- cluster modülü,
- module boundaries,
- dependency design,
- output contracts,
- state paylaşımı,
- blast radius yönetimi.

### Modül 4: Advanced Workflow

Kapsam:

- workspaces,
- environments,
- dev/staging/prod ayrımı,
- CI/CD ile Terraform,
- production-ready infrastructure,
- Policy as Code,
- reviewable infrastructure changes.

## Modül 1: IaC Felsefesi ve Terraform Temelleri

## Giriş

Infrastructure as Code, altyapıyı elle yapılan operasyonlardan çıkarıp kodla yönetilebilir bir mühendislik varlığına dönüştürür.

Terraform'un amacı yalnızca cloud üzerinde kaynak oluşturmak değildir. Asıl amaç şudur:

- altyapıyı tekrar üretilebilir hale getirmek,
- değişiklikleri versiyonlamak,
- değişiklikleri uygulamadan önce görebilmek,
- production ortamında kontrolü artırmak,
- insan hatasını azaltmak,
- ortamlar arası tutarlılığı korumak.

Terraform'a yüzeysel bakılırsa şu şekilde anlaşılır:

```text
Terraform cloud'da resource açar.
```

Bu tanım eksiktir.

Daha doğru tanım şudur:

```text
Terraform, altyapının hedef durumunu kodla tanımlar, mevcut gerçek altyapıyla karşılaştırır ve aradaki farkı kontrollü şekilde uygular.
```

## Temel Kavramlar

### Infrastructure as Code

Infrastructure as Code; sunucu, network, database, cluster ve security group gibi altyapı bileşenlerinin manuel işlem yerine kodla tanımlanmasıdır.

Amaç sadece otomasyon değildir.

Asıl amaç:

- reproducibility,
- auditability,
- version control,
- change review,
- predictability,
- scalability.

Bir altyapı kodla tanımlandığında şu sorulara cevap verebilir hale geliriz:

- Bu altyapıyı başka bir ortamda tekrar kurabilir miyiz?
- Hangi değişiklik ne zaman yapılmış görebilir miyiz?
- Değişiklikleri production'a uygulamadan önce inceleyebilir miyiz?
- Ortamlar arasındaki farkları azaltabilir miyiz?

### Declarative Yaklaşım

Terraform declarative çalışır.

Imperative yaklaşımda sisteme adım adım ne yapacağını söylersin:

```bash
create vpc
create subnet
attach route table
create database
configure security group
```

Declarative yaklaşımda ise hedef durumu tarif edersin:

```hcl
resource "aws_vpc" "main" {
  cidr_block = "10.0.0.0/16"
}
```

Bu kodun anlamı:

```text
Benim altyapımda 10.0.0.0/16 CIDR bloğuna sahip bir VPC olmalı.
```

Terraform sonra mevcut gerçek dünya ile kodda tarif edilen hedef durumu karşılaştırır.

Bu nedenle Terraform'da asıl mühendislik sorusu şudur:

```text
Bu resource'u nasıl oluştururum?
```

değil:

```text
Bu resource'un yaşam döngüsünü, bağımlılıklarını ve değişim etkisini nasıl güvenilir şekilde modelleyebilirim?
```

### Provider

Provider, Terraform ile dış sistem API'si arasında çalışan adaptördür.

Terraform AWS, Azure, GCP, Kubernetes veya GitHub gibi sistemleri doğrudan bilmez. Bunlarla konuşmak için provider kullanır.

Örnek AWS provider:

```hcl
provider "aws" {
  region = "eu-central-1"
}
```

Provider'ın görevi:

- Cloud API ile konuşmak,
- Terraform resource tanımlarını gerçek API çağrılarına çevirmek,
- resource lifecycle yönetimini sağlamak,
- Terraform plan/apply sürecine bilgi sağlamak.

Daha esnek kullanım:

```hcl
provider "aws" {
  region = var.aws_region
}
```

Bu tasarım sayesinde aynı Terraform kodu farklı region'larda kullanılabilir.

Trade-off:

| Avantaj | Risk |
| --- | --- |
| Daha esnek yapı | Yanlış variable değeriyle yanlış region'da kaynak açılabilir |
| Farklı region'lara kolay deployment | Production kaynakları yanlışlıkla başka ortam ayarlarıyla oluşturulabilir |
| Ortam bazlı yeniden kullanım | Daha sıkı validation ve naming convention gerekir |

### Variables

Variables, Terraform kodunu parametrelenebilir hale getirir.

Örnek:

```hcl
variable "environment" {
  type        = string
  description = "Deployment environment: dev, staging, prod"
}
```

Kullanım:

```hcl
resource "aws_s3_bucket" "logs" {
  bucket = "visium-${var.environment}-logs"
}
```

Bu yapı aynı kodun farklı ortamlar için kullanılmasını sağlar:

```bash
terraform apply -var="environment=dev"
terraform apply -var="environment=prod"
```

Ancak her şeyi variable yapmak doğru değildir.

Kötü tasarım örneği:

```hcl
variable "vpc_cidr" {}
variable "subnet_1_cidr" {}
variable "subnet_2_cidr" {}
variable "db_instance_type" {}
variable "cluster_node_count" {}
variable "enable_nat_gateway" {}
variable "enable_logs" {}
variable "enable_backup" {}
variable "backup_retention_days" {}
variable "publicly_accessible" {}
```

Bu yapı ilk bakışta esnek görünür. Fakat aslında modülü fazla dışarı açar.

İyi variable tasarımında şu soru sorulmalıdır:

```text
Kullanıcı gerçekten bu kararı vermeli mi, yoksa bu karar modülün içinde güvenli default olarak mı korunmalı?
```

Variable türleri:

| Variable Türü | Örnek | Risk |
| --- | --- | --- |
| Güvenli parametre | `environment`, `region`, `instance_size` | Düşük / orta |
| Mimari karar | `enable_nat_gateway`, `multi_az` | Orta / yüksek |
| Güvenlik sınırı | `publicly_accessible`, `allow_0_0_0_0` | Çok yüksek |

Production database modülünde `publicly_accessible` gibi güvenlik açısından kritik bir kararın dışarı açılması risklidir.

Çünkü bir gün yanlışlıkla production database internete açık hale getirilebilir.

Production-ready Terraform modüllerinde amaç her şeyi variable yapmak değil, hangi kararların dışarı açılacağını bilinçli seçmektir.

### Outputs

Output, Terraform'da sadece ekrana bilgi yazdırmak için kullanılmaz.

Output aynı zamanda modüller arası contract görevi görür.

Örnek VPC output'ları:

```hcl
output "vpc_id" {
  value = aws_vpc.main.id
}

output "private_subnet_ids" {
  value = aws_subnet.private[*].id
}
```

Cluster modülü bu output'ları kullanabilir:

```hcl
module "eks_cluster" {
  source             = "./modules/eks"
  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
}
```

Buradaki mimari fikir şudur:

```text
VPC modülü, subnet'leri nasıl oluşturduğunu cluster modülüne anlatmaz.
Sadece cluster modülünün ihtiyaç duyduğu bilgileri verir.
```

Bu, yazılım mühendisliğindeki encapsulation prensibinin altyapıdaki karşılığıdır.

Kötü output tasarımı:

```hcl
output "everything" {
  value = aws_vpc.main
}
```

Sonuç:

- Diğer modüller iç implementasyona bağımlı hale gelir,
- refactor zorlaşır,
- modüller arası coupling artar,
- küçük değişikliklerin etkisi büyür.

İyi output tasarımı:

```hcl
output "vpc_id" {
  value = aws_vpc.main.id
}

output "private_subnet_ids" {
  value = aws_subnet.private[*].id
}

output "database_subnet_group_name" {
  value = aws_db_subnet_group.main.name
}
```

Bu yapı sadece dış dünyanın gerçekten ihtiyaç duyduğu contract'ı sunar.

## Terraform Workflow

Terraform'un temel workflow'u üç ana adımdan oluşur:

```bash
terraform init
terraform plan
terraform apply
```

Bunlar sadece komut değildir. Her biri mühendislik kontrol noktasıdır.

### terraform init

`terraform init`, Terraform projesini çalıştırılabilir hale getirir.

Yaptıkları:

- Provider'ları indirir,
- backend yapılandırmasını hazırlar,
- modülleri çözer,
- Terraform çalışma dizinini başlatır.

Mühendislik sorusu:

```text
Bu Terraform projesi çalıştırılabilir hale getirildi mi?
```

### terraform plan

`terraform plan`, kod ile mevcut altyapı arasındaki farkı gösterir.

Örnek çıktı:

```text
+ create aws_vpc.main
~ update aws_security_group.app
- destroy aws_instance.old
```

Plan aşaması şu soruyu cevaplar:

```text
Bunu uygularsam ne değişecek?
```

Production ortamında plan okumadan apply yapmak ciddi bir mühendislik hatasıdır. Çünkü plan, değişiklikten önceki son güvenlik kontrolüdür.

Plan sayesinde:

- oluşturulacak resource'lar görülür,
- güncellenecek resource'lar görülür,
- silinecek resource'lar görülür,
- beklenmeyen değişiklikler fark edilir,
- blast radius analiz edilebilir.

### terraform apply

`terraform apply`, planlanan değişiklikleri gerçek altyapıya uygular.

Ancak Terraform kodu gerçek altyapıyı tek başına temsil etmez.

Arada kritik bir unsur vardır:

```text
Terraform state
```

State, Terraform'un hangi resource'u yönettiğini, bu resource'un gerçek dünyadaki ID'sini ve önceki durumunu tuttuğu hafızadır.

State olmadan Terraform güvenilir şekilde çalışamaz.

Bu nedenle state yönetimi production Terraform'un kalbidir.

## Problem

Yeni başlayan ekiplerde sık görülen hata şudur:

```text
Terraform dosyaları yazılır, ancak mimari sınırlar düşünülmez.
```

Örneğin tüm kaynaklar tek bir root module içinde tanımlanır:

```text
main.tf
├── aws_vpc
├── aws_subnet
├── aws_db_instance
├── aws_eks_cluster
└── aws_security_group
```

İlk bakışta basit görünür. Fakat production ortamında ciddi problemler yaratır:

- blast radius büyür,
- VPC değişikliği DB ve cluster üzerinde risk oluşturabilir,
- modüller arası sınır olmadığı için bağımlılıklar belirsizleşir,
- reusability azalır,
- environment ayrımı zorlaşır,
- plan çıktısı karmaşıklaşır,
- ownership belirsizleşir.

Terraform'da başarı sadece resource yazmak değildir.

Başarı, altyapı bileşenlerini doğru sınırlarla modellemektir.

## Yaklaşım / Tasarım

Production seviyesinde Terraform tasarımı şu prensiplere dayanmalıdır.

### Küçük ve Anlamlı Modüller

Örnek modül ayrımı:

```text
modules/
  vpc/
  database/
  eks/
  security/
```

Root module:

```text
environments/
  dev/
  staging/
  prod/
```

Bu yapı sayesinde:

- VPC lifecycle ayrı yönetilir,
- database lifecycle ayrı yönetilir,
- cluster lifecycle ayrı yönetilir,
- değişikliklerin etkisi sınırlanır,
- modüller tekrar kullanılabilir hale gelir.

### Output Contract Kullanımı

Modüller birbirinin iç detaylarına bağımlı olmamalıdır.

VPC modülü şunları dışarı verir:

```hcl
output "vpc_id" {
  value = aws_vpc.main.id
}

output "private_subnet_ids" {
  value = aws_subnet.private[*].id
}
```

Database veya cluster modülü bu değerleri input olarak alır.

Bu modelde VPC modülünün iç yapısı değişse bile dış contract bozulmadığı sürece diğer modüller etkilenmez.

### Güvenli Default Yaklaşımı

Her yapılandırma kullanıcıya bırakılmamalıdır.

Özellikle güvenlik sınırları modül içinde korunmalıdır.

Kötü yaklaşım:

```hcl
variable "publicly_accessible" {
  type    = bool
  default = false
}
```

Daha güvenli yaklaşım:

```hcl
resource "aws_db_instance" "main" {
  publicly_accessible = false
}
```

Eğer istisna gerekiyorsa bu ayrı ve bilinçli bir tasarım kararı olmalıdır.

Örneğin:

- sadece non-production ortamda izin verilebilir,
- validation eklenebilir,
- Policy as Code ile engellenebilir,
- code review zorunlu tutulabilir.

## Teknik Terimler Sözlüğü

- Infrastructure as Code: Altyapı bileşenlerinin manuel işlemler yerine kodla tanımlanması ve yönetilmesi yaklaşımıdır.
- Terraform: HashiCorp tarafından geliştirilen declarative Infrastructure as Code aracıdır.
- Provider: Terraform'un AWS, Azure, GCP, Kubernetes gibi dış sistemlerle konuşmasını sağlayan adaptördür.
- Resource: Terraform tarafından yönetilen altyapı nesnesidir. Örnek: VPC, subnet, security group, database, Kubernetes cluster.
- Variable: Terraform kodunu parametrelenebilir hale getiren input değeridir.
- Output: Bir Terraform modülünün dış dünyaya sunduğu sonuç değeridir; modüller arası contract görevi görür.
- State: Terraform'un yönettiği resource'ların gerçek dünya karşılıklarını tuttuğu hafıza dosyasıdır.
- Plan: Kod ile mevcut altyapı arasındaki farkı gösteren Terraform çıktısıdır.
- Apply: Planlanan değişikliklerin gerçek altyapıya uygulanmasıdır.
- Drift: Kodda tanımlanan altyapı ile gerçek ortamdaki altyapı arasında fark oluşmasıdır.
- Blast Radius: Bir değişikliğin sistemde etkileyebileceği alanın büyüklüğüdür.
- Module: Terraform kodunu yeniden kullanılabilir ve sınırlandırılmış parçalara ayıran yapıdır.
- Contract: Bir modülün dış dünyaya sunduğu input/output arayüzüdür.

## Sonuç

Terraform ile altyapı kurmak görece kolaydır.

Zor olan, altyapının yaşam döngüsünü güvenli, tekrar üretilebilir ve kontrollü şekilde modellemektir.

Modül 1'in ana fikri:

```text
Terraform, sadece resource oluşturan bir araç değildir.
Terraform, altyapı kararlarını kodla ifade eden, değişiklikleri gözlemlenebilir hale getiren ve production ortamlarında kontrollü değişim sağlayan bir mühendislik disiplinidir.
```

Bu modülde öğrenilmesi gereken en kritik noktalar:

- Terraform declarative çalışır,
- provider dış sistemlerle bağlantı kurar,
- variables kontrollü parametreleme sağlar,
- outputs modüller arası contract oluşturur,
- plan, değişiklik öncesi mühendislik denetimidir,
- state, Terraform'un gerçek dünya hafızasıdır,
- her şeyi variable yapmak iyi tasarım değildir,
- modül sınırları blast radius yönetimi için kritiktir.

## Araştırma Keywordleri

- `infrastructure as code principles`
- `terraform declarative infrastructure`
- `terraform provider architecture`
- `terraform variables best practices`
- `terraform outputs module contract`
- `terraform plan apply workflow`
- `terraform module design best practices`
- `terraform production best practices`
- `terraform blast radius`
- `terraform secure module design`
- `terraform input validation`
- `terraform state management basics`

## Sokratik Sınama

### Soru 1

Bir Terraform projesinde VPC, database ve Kubernetes cluster aynı root module içinde tanımlanmış olsun.

```text
main.tf
├── aws_vpc
├── aws_subnet
├── aws_db_instance
├── aws_eks_cluster
└── aws_security_group
```

Bu tasarım production ortamında hangi mühendislik problemlerini doğurur?

Özellikle şunları analiz et:

- Değişiklik blast radius'u nasıl etkilenir?
- VPC'de yapılacak küçük bir değişiklik DB veya cluster için nasıl risk yaratabilir?
- Bu yapıda tekrar üretilebilirlik gerçekten sağlanmış olur mu?
- Yoksa sadece "kodla karışıklık" mı üretilmiş olur?

### Soru 2

Bir Terraform database modülünde şu variable tanımlı olsun:

```hcl
variable "publicly_accessible" {
  type    = bool
  default = false
}
```

Bu variable production database modülünde dışarıya açılmış.

Bu iyi bir tasarım mıdır?

Tartış:

- Güvenlik açısından kritik kararlar kullanıcıya variable olarak bırakılmalı mı?
- Yoksa modül içinde sert kurallarla mı korunmalı?
- Esneklik ile güvenlik arasındaki trade-off nedir?
- Hangi durumlarda istisna kabul edilebilir?

## Eğitimin Devam Kuralı

Bu eğitim serisinde bir sonraki modüle geçmeden önce kullanıcı Sokratik Sınama bölümündeki sorulara cevap vermelidir.

Cevap eksik veya yanlışsa doğrudan doğru cevap verilmez. Bunun yerine mantıksal ipuçları verilerek kullanıcının doğru sonuca ulaşması sağlanır.

## Modül 2'ye Geçiş Notu

Bir sonraki modül:

```text
State Yönetimi
```

Bu modülde ele alınacak ana fikir:

```text
Terraform state, Terraform'un kalbidir.
Ancak aynı zamanda production altyapıda ciddi riskler taşıyan bir mayın tarlasıdır.
```

İşlenecek konular:

- Local state neden risklidir?
- Remote state neden gerekir?
- State locking neyi çözer?
- Aynı anda iki kişi apply yaparsa ne olur?
- State içinde sensitive data neden tehlikelidir?
- Drift nasıl oluşur?
- State ayrımı modüler mimariyi nasıl etkiler?

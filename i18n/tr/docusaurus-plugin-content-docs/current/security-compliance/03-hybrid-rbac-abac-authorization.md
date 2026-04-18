---
title: Hibrit Yetkilendirme Modeli - RBAC + ABAC
description: role_permission, user_permission, common_permission ve allow/deny öncelik kuralı ile yetkilendirme modeli.
sidebar_position: 3
---

## Giriş

Sadece rol tabanlı model (RBAC), kurumsal ürünlerde istisnaları yönetmekte zorlanır. Bu nedenle RBAC ve ABAC birlikte ele alınmalıdır.

## Neden Önemli

RBAC tek başına kullanıldığında:

- role explosion oluşur,
- endpoint koduna dağılmış istisnalar artar,
- kararların audit edilebilirliği azalır.

## Temel Kavramlar

- `role_permission`: rolle gelen yetki.
- `user_permission`: kullanıcıya özel override yetkisi.
- `common_permission`: ortak temel yetki seti.
- `allow` / `deny`: karar etkisi.
- ABAC attribute'ları: `tenant_id`, sahiplik, departman, risk bağlamı vb.

## Gerçek Ürün Geliştirmede Problem

Gerçek senaryolarda aynı role sahip kullanıcılar farklı tenant veya bağlam nedeniyle farklı karar almalıdır. Sadece role bakmak yanlış pozitif/negatif sonuç üretir.

## Yaklaşım / Tasarım

Önerilen değerlendirme sırası:

1. Kimlik ve bağlam çözümle.
2. `common_permission` yükle.
3. `role_permission` setini birleştir.
4. `user_permission` override uygula.
5. ABAC koşullarını doğrula.
6. `allow`/`deny` precedence kuralını uygula.

Önerilen öncelik:

- Açık `deny`, açık `allow`'u ezer.
- `user_permission`, tanımlı alanlarda `role_permission` üzerine çıkabilir.
- ABAC başarısızsa geçici allow kararı düşer.

Veri sınırı için: [Tenant-based Row-Level Security (RLS)](../database-architecture/tenant-based-rls-policy-design).

### Scope-First `canDo` Deseni (`school -> class -> student`)

Eğitim alanındaki hiyerarşik yetki için efektif scope şu sırayla çözülür:

1. kullanıcıya bağlı okul,
2. kullanıcıya bağlı sınıf,
3. kullanıcıya bağlı öğrenci.

Short-circuit kuralı:

- okul ataması varsa burada dur ve okul scope'u kullan,
- okul scope'u yoksa sınıf scope'una bak,
- sınıf da yoksa öğrenci scope'una düş.

Bu sayede endpoint bazında tekrar tekrar yetki kodu yazmak yerine tek ve deterministik bir scope çözümleme yolu kullanılır.

```java
@Component
@RequiredArgsConstructor
public class ScopeResolver {
  private final AssignmentRepository assignmentRepository;

  public Scope resolveScope(Long userId) {
    Set<Long> schoolIds = assignmentRepository.findSchoolIdsByUserId(userId);
    if (!schoolIds.isEmpty()) {
      return Scope.forSchools(schoolIds); // short-circuit: en geniş scope
    }

    Set<Long> classIds = assignmentRepository.findClassIdsByUserId(userId);
    if (!classIds.isEmpty()) {
      return Scope.forClasses(classIds);
    }

    Set<Long> studentIds = assignmentRepository.findStudentIdsByUserId(userId);
    return Scope.forStudents(studentIds);
  }
}
```

### `getStudents` için `@PreAuthorize` Entegrasyonu

Bu senaryoda sadece `hasAuthority(...)` kullanımı çoğu zaman yetersiz kalır; çünkü authority string'leri statik, scope ise dinamik ve kullanıcıya özeldir. Pratik yaklaşım, custom bean metodu kullanmaktır:

```java
@Component("authz")
@RequiredArgsConstructor
public class AuthorizationFacade {
  private final ScopeResolver scopeResolver;
  private final StudentPolicy studentPolicy;

  public boolean canReadStudents(Authentication authentication, StudentQuery query) {
    Long userId = ((PrincipalUser) authentication.getPrincipal()).getUserId();
    Scope scope = scopeResolver.resolveScope(userId);
    return studentPolicy.canList(scope, query);
  }

  public boolean canDo(Authentication authentication, String action, Long targetStudentId) {
    Long userId = ((PrincipalUser) authentication.getPrincipal()).getUserId();
    Scope scope = scopeResolver.resolveScope(userId);
    return studentPolicy.canActOnStudent(scope, action, targetStudentId);
  }
}

@GetMapping("/students")
@PreAuthorize("@authz.canReadStudents(authentication, #query)")
public Page<StudentDto> getStudents(StudentQuery query) {
  return studentService.getStudents(query);
}
```

### Interceptor ile Scope Filter Enjeksiyonu (Pagination Uyumlu)

Request interceptor, repository/service katmanına gitmeden önce scope'u filtreye işler:

- request başına scope bir kez çözülür,
- `school_id`, `class_id` veya `student_id IN (...)` filtresi otomatik eklenir,
- pagination aynı path'te çalışır ama sadece yetkili veriler döner.

```java
@Component
@RequiredArgsConstructor
public class ScopeFilterInterceptor implements HandlerInterceptor {
  private final ScopeResolver scopeResolver;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    Long userId = Security.currentUserId();
    Scope scope = scopeResolver.resolveScope(userId);
    ScopedFilter scopedFilter = ScopedFilter.fromRequest(request).apply(scope);
    request.setAttribute("scopedFilter", scopedFilter);
    return true;
  }
}
```

Bu desen kod tekrarını azaltır ve yetkisiz okul/sınıf/öğrenci erişimini tutarlı biçimde engeller.

## Sektör Standardı / Best Practice

- Politika dilini küçük ve deterministik tut.
- Permission ataması ile policy evaluation katmanını ayır.
- Deny kararlarını da logla.
- Endpoint bazlı tekrar kontrol yerine merkezi scope çözümleme ve yeniden kullanılabilir policy metotlarını tercih et.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
authorize(user, action, resource, context):
  permissions = common_permission(action)
  permissions += role_permission(user.roles, action)
  permissions += user_permission(user.id, action)

  if has_explicit_deny(permissions, resource):
    return deny

  if not has_any_allow(permissions, resource):
    return deny

  if not abac_match(user, resource, context):
    return deny

  return allow

resolve_scope(user_id):
  schools = assigned_schools(user_id)
  if schools not empty:
    return scope(type='school', ids=schools)

  classes = assigned_classes(user_id)
  if classes not empty:
    return scope(type='class', ids=classes)

  students = assigned_students(user_id)
  return scope(type='student', ids=students)

can_do(user, action, target_student):
  scope = resolve_scope(user.id)
  if scope.type == 'school':
    return target_student.school_id in scope.ids
  if scope.type == 'class':
    return target_student.class_id in scope.ids
  return target_student.id in scope.ids
```

## Bizim Notlarımız / Takım Kararları

- Model hibrit olacak: `user_permission` + `role_permission` + `common_permission`.
- `allow`/`deny` precedence açık ve dokümante olmalı.
- Yetki tasarımı `role -> permission -> endpoint/use-case` hattında yapılmalı.
- Okul/sınıf/öğrenci hiyerarşisinde merkezi `canDo` + scope resolver ve interceptor tabanlı filter enjeksiyonu tercih edilmeli.

## Sözlük

- RBAC: Role-based access control.
- ABAC: Attribute-based access control.
- Precedence: Çakışmada karar öncelik kuralı.
- `scope`: request için çözülen veri görünürlük sınırı (`school`, `class`, `student`).

## Araştırma Keywordleri

- `hybrid rbac abac authorization`
- `allow deny precedence model`
- `user permission override strategy`
- `spring preauthorize custom bean method`
- `request interceptor scope filter injection`

## Sonuç

Hibrit model, hem yönetilebilirlik hem esneklik sağlar. RBAC temel omurgayı kurar, ABAC gerçek bağlamı devreye alır.

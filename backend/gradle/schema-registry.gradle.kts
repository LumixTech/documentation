// =====================================================================
// Apicurio Registry — .proto → registry senkron görevleri (Gradle).
// Root build.gradle.kts'ten apply(from = "gradle/schema-registry.gradle.kts") ile yüklenir.
//
// Apicurio'nun resmî Gradle plugin'i YOK → REST API v3'ü doğrudan java.net.http ile
// çağırıyoruz (curl/OS bağımlılığı yok, Windows + Linux CI'da aynı çalışır).
//
// Görevler:
//   schemaRegister    → tüm .proto'ları register/yeni-versiyon (BACKWARD sunucuda denetlenir)
//   schemaValidate    → dryRun=true ile aynısını dener, YAZMAZ (uyumluluk ön-kontrolü)
//   schemaSmokeTest   → dummy proto register + pull + doğrula + temizle (kurulum testi)
//
// Yapılandırma (öncelik: -P > ENV > varsayılan):
//   -PapicurioUrl=... | APICURIO_URL   (varsayılan http://localhost:8080)
//   -PschemaGroup=... | SCHEMA_GROUP   (varsayılan default)
//
// Örnek:  ./gradlew schemaValidate -PapicurioUrl=http://schema.lumix.local:8080
// =====================================================================
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

val registryUrl: String = ((project.findProperty("apicurioUrl") as String?)
    ?: System.getenv("APICURIO_URL") ?: "http://localhost:8080").trimEnd('/')
val schemaGroup: String = (project.findProperty("schemaGroup") as String?)
    ?: System.getenv("SCHEMA_GROUP") ?: "default"

val apiBase = "$registryUrl/apis/registry/v3"
// Apicurio'da protobuf ŞEMA metni için content-type (serialize mesaj değil, .proto kaynağı).
val protoContentType = "application/x-protobuf"

val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

fun jsonEscape(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (c in s) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.toString()
}

// Sadece elle yazılan .proto'lar (üretilen/vendored google importları hariç).
fun discoverProtos(): List<File> =
    fileTree(rootDir) {
        include("**/src/main/proto/**/*.proto")
        exclude("**/build/**")
        exclude("**/google/**")
    }.files.sortedBy { it.invariantSeparatorsPath }

// Konvansiyon: artifactId = dosya adı (uzantısız). Event proto'ları
// <service>.<aggregate>.<event>.v1.proto adıyla → artifactId = identity.user.created.v1
fun artifactIdOf(f: File): String = f.name.removeSuffix(".proto")

fun createArtifactBody(artifactId: String, protoText: String): String =
    """{"artifactId":"${jsonEscape(artifactId)}","artifactType":"PROTOBUF",""" +
        """"firstVersion":{"content":{"content":"${jsonEscape(protoText)}","contentType":"$protoContentType"}}}"""

fun send(req: HttpRequest): HttpResponse<String> =
    try {
        httpClient.send(req, HttpResponse.BodyHandlers.ofString())
    } catch (e: java.io.IOException) {
        throw GradleException(
            "Apicurio Registry'e ulaşılamadı ($registryUrl). Ayakta mı? " +
                "Lokal: cd infra/apicurio && docker compose up -d. Sebep: ${e.message}",
            e,
        )
    }

// Grup yoksa oluştur (default hariç). 409 = zaten var → sorun değil.
fun ensureGroup() {
    if (schemaGroup == "default") return
    val req = HttpRequest.newBuilder()
        .uri(URI.create("$apiBase/groups"))
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("""{"groupId":"${jsonEscape(schemaGroup)}"}"""))
        .build()
    val resp = send(req)
    if (resp.statusCode() !in intArrayOf(200, 201, 204, 409)) {
        throw GradleException("Grup oluşturulamadı '$schemaGroup': HTTP ${resp.statusCode()} — ${resp.body()}")
    }
}

// Bir artifact'ı register eder / yeni versiyon ekler. dryRun=true ise sadece dener (yazmaz).
// Dönüş: (statusCode, body). BACKWARD ihlali → 409.
fun putArtifact(artifactId: String, protoText: String, dryRun: Boolean): HttpResponse<String> {
    val q = StringBuilder("?ifExists=CREATE_VERSION")
    if (dryRun) q.append("&dryRun=true")
    val req = HttpRequest.newBuilder()
        .uri(URI.create("$apiBase/groups/$schemaGroup/artifacts$q"))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(createArtifactBody(artifactId, protoText)))
        .build()
    return send(req)
}

fun processAll(dryRun: Boolean) {
    val protos = discoverProtos()
    if (protos.isEmpty()) {
        logger.lifecycle("Hiç .proto bulunamadı (**/src/main/proto). Atlanıyor.")
        return
    }
    ensureGroup()
    val mode = if (dryRun) "VALIDATE (dryRun)" else "REGISTER"
    logger.lifecycle("$mode → $apiBase  (grup: $schemaGroup)  ${protos.size} şema")
    val failures = mutableListOf<String>()
    for (f in protos) {
        val id = artifactIdOf(f)
        val resp = putArtifact(id, f.readText(Charsets.UTF_8), dryRun)
        when (resp.statusCode()) {
            200, 201, 204 -> logger.lifecycle("  ✓ $id")
            409 -> {
                logger.error("  ✗ $id — UYUMSUZ (BACKWARD ihlali)\n    ${resp.body()}")
                failures += id
            }
            else -> {
                logger.error("  ✗ $id — HTTP ${resp.statusCode()}\n    ${resp.body()}")
                failures += id
            }
        }
    }
    if (failures.isNotEmpty()) {
        throw GradleException("Şema $mode başarısız: ${failures.joinToString(", ")}")
    }
    logger.lifecycle("$mode tamam — ${protos.size} şema OK.")
}

tasks.register("schemaRegister") {
    group = "schema registry"
    description = "Tüm .proto'ları Apicurio'ya register eder / yeni versiyon ekler (BACKWARD sunucuda denetlenir)."
    doLast { processAll(dryRun = false) }
}

tasks.register("schemaValidate") {
    group = "schema registry"
    description = "Tüm .proto'ları dryRun ile dener; uyumsuzlukta başarısız olur, hiçbir şey yazmaz (CI/lokal ön-kontrol)."
    doLast { processAll(dryRun = true) }
}

tasks.register("schemaSmokeTest") {
    group = "schema registry"
    description = "Dummy proto register + pull + doğrula + temizle. Registry kurulumunu uçtan uca test eder."
    doLast {
        val id = "lumix.smoke.ping.v1"
        val proto = """
            syntax = "proto3";
            package lumix.smoke.v1;
            message Ping { string message = 1; }
        """.trimIndent()

        ensureGroup()
        logger.lifecycle("SMOKE → register '$id' ...")
        val reg = putArtifact(id, proto, dryRun = false)
        if (reg.statusCode() !in intArrayOf(200, 201, 204)) {
            throw GradleException("SMOKE register başarısız: HTTP ${reg.statusCode()} — ${reg.body()}")
        }

        logger.lifecycle("SMOKE → pull (branch=latest) ...")
        val getReq = HttpRequest.newBuilder()
            .uri(URI.create("$apiBase/groups/$schemaGroup/artifacts/$id/versions/branch=latest/content"))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val pulled = send(getReq)
        if (pulled.statusCode() != 200) {
            throw GradleException("SMOKE pull başarısız: HTTP ${pulled.statusCode()} — ${pulled.body()}")
        }
        if (!pulled.body().contains("message Ping")) {
            throw GradleException("SMOKE doğrulama başarısız: çekilen içerik beklenen 'message Ping' içermiyor:\n${pulled.body()}")
        }

        // Temizlik — smoke artifact'ı sil (compose'da artifact silme açık).
        val delReq = HttpRequest.newBuilder()
            .uri(URI.create("$apiBase/groups/$schemaGroup/artifacts/$id"))
            .timeout(Duration.ofSeconds(20))
            .DELETE()
            .build()
        val del = send(delReq)
        if (del.statusCode() !in intArrayOf(200, 204, 404)) {
            logger.warn("SMOKE temizlik uyarısı: '$id' silinemedi (HTTP ${del.statusCode()}). Elle silebilirsiniz.")
        }

        logger.lifecycle("SMOKE OK — register + pull başarılı ($apiBase, grup: $schemaGroup).")
    }
}

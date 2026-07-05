// adapter-grpc — inbound gRPC adapter. .proto → kod üretimi protobuf-gradle-plugin ile.
// Proto dosyaları: src/main/proto/  → build/generated/source/proto altında Java üretilir.
// NOT (03-grpc §4.8): net.devh starter'ı kullanılıyor; ileride resmi "Spring gRPC"e
// (org.springframework.grpc) geçiş planlı — sürüm hizalaması ilk gerçek build'de doğrulanmalı.
import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.protobuf)
}

dependencies {
    api(project(":service-template:application"))

    implementation(libs.grpc.server.spring.boot.starter)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.protobuf.java)
    // Üretilen gRPC kodundaki @javax.annotation.Generated için (derleme zamanı).
    compileOnly(libs.javax.annotation.api)

    testImplementation(libs.spring.boot.starter.test)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
            }
        }
    }
}

// Üretilen kaynaklar Spotless/Checkstyle dışında (root'ta targetExclude ile ayrıca korunur).

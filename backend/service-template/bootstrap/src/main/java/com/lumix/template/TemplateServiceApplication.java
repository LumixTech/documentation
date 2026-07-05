package com.lumix.template;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * service-template giriş noktası. Bileşenler {@code com.lumix.template.*} altındaki tüm
 * adapter/application paketlerinden component-scan ile toplanır.
 *
 * <p>Yeni servis türetirken: paket adını (com.lumix.&lt;service&gt;) ve sınıf adını değiştir.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TemplateServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TemplateServiceApplication.class, args);
    }
}

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    kotlin("jvm") version "1.3.11"
}

application {
    mainClassName = "com.vooft.moneytransfer.MoneyTransferKt"
}

dependencies {
    implementation("io.projectreactor.netty:reactor-netty:0.8.4.RELEASE")
    implementation("io.projectreactor.addons:reactor-extra:3.2.2.RELEASE")
    implementation("org.apache.logging.log4j:log4j-api:2.11.1")
    implementation("org.apache.logging.log4j:log4j-core:2.11.1")
    implementation("org.apache.logging.log4j:log4j-slf4j-impl:2.11.1")
    testCompile("io.projectreactor:reactor-test:3.2.5.RELEASE")
    testCompile("org.junit.jupiter:junit-jupiter-api:5.3.2")
    testCompile("org.junit.jupiter:junit-jupiter-params:5.3.2")
    testCompile("org.hamcrest:hamcrest-all:1.3")
    compile(kotlin("stdlib"))
}

repositories {
    jcenter()
}

tasks {
    withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
    }
}

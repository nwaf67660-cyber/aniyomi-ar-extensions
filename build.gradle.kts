// Top-level build file
buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.tachiyomi.org/repository/maven-public/")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.1.4")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.10")
    }
}

plugins {
    id("com.gradle.enterprise") version "3.15" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.tachiyomi.org/repository/maven-public/")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

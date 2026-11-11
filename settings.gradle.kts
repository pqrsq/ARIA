pluginManagement {
    repositories {
        // آینه‌های ایران (اولویت اول)
        maven { url = uri("https://maven.myket.ir") }
        maven { url = uri("https://maven.devneeds.ir") }
        maven { url = uri("https://gradle.iranrepo.ir") }
        maven { url = uri("https://gradle.jamko.ir") }
        maven { url = uri("https://en-mirror.ir") }

        // آینه‌های چین (جایگزین)
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }

        // منابع رسمی (به عنوان آخرین راه‌حل)
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // آینه‌های ایران
        maven { url = uri("https://maven.myket.ir") }
        maven { url = uri("https://maven.devneeds.ir") }
        maven { url = uri("https://gradle.iranrepo.ir") }
        maven { url = uri("https://gradle.jamko.ir") }
        maven { url = uri("https://en-mirror.ir") }

        // آینه‌های چین
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }

        // منابع رسمی
        google()
        mavenCentral()
    }
}

rootProject.name = "ARIA"
include(":app")

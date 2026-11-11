pluginManagement {
    repositories {
        // آدرس مستقیم Google Maven برای دانلود پلاگین‌های اندروید
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
        
        // منابع رسمی دیگر
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // آدرس مستقیم Google Maven برای دانلود کتابخانه‌ها
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
        
        // منابع رسمی دیگر
        google()
        mavenCentral()
    }
}

rootProject.name = "ARIA"
include(":app")

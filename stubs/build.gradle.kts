// Compile-time stubs for @SystemApi classes that are not in the public android.jar. The app depends
// on this module as compileOnly, so the stubs are never packaged: at runtime the real framework
// classes are used. It is an Android library so the stubs can see android.jar.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.volume_plus_plus.stubs"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

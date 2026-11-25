plugins { id("com.android.application") }
android {
    namespace = "kz.asyltas.android"
    compileSdk = 35
    defaultConfig { applicationId = "kz.asyltas.android"; minSdk = 24; targetSdk = 35; versionCode = 6; versionName = "4.0"; testInstrumentationRunner = "kz.asyltas.android.PortalTestRunner" }
    testBuildType = "release"
    sourceSets.getByName("main").java.srcDir("../../shared/src")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
}

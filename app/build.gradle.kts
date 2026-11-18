plugins { alias(libs.plugins.android.application); alias(libs.plugins.compose.compiler); alias(libs.plugins.ksp) }
android {
 namespace = "io.github.kannoncoding.wealthward"
 compileSdk = 37
 defaultConfig {
  applicationId = "io.github.kannoncoding.wealthward"
  minSdk = 26
  targetSdk = 37
  versionCode = 5
  versionName = "0.5.0"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
 }
 buildTypes { debug { applicationIdSuffix = ".dev"; versionNameSuffix = "-dev" }; release { isMinifyEnabled = false } }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 sourceSets.getByName("androidTest").assets.srcDir("schemas")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
 implementation(platform(libs.compose.bom)); implementation(libs.compose.material3); implementation(libs.compose.ui)
 implementation(libs.activity.compose); implementation(libs.lifecycle.compose)
 // Align app/test runtime with Room migration serialization 1.8.1; consistent resolution otherwise selects older core.
 implementation(libs.serialization.json); implementation(libs.room.runtime); implementation(libs.room.ktx); ksp(libs.room.compiler); implementation(libs.coroutines)
 testImplementation(libs.junit)
 androidTestImplementation(libs.androidx.test.runner); androidTestImplementation(libs.androidx.test.junit)
 androidTestImplementation(libs.room.testing); androidTestImplementation(libs.coroutines)
 androidTestImplementation(platform(libs.compose.bom))
 androidTestImplementation(libs.compose.ui.test.junit4)
 androidTestImplementation(libs.espresso.core)
 debugImplementation(libs.compose.ui.test.manifest)
}


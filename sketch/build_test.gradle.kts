import org.gradle.internal.impldep.com.amazonaws.PredefinedClientConfigurations.defaultConfig

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // config espresso test runner
    }

    // runner 和 espresso-web 里都包含了 这两个文件，冲突了
//    packagingOptions {
//        exclude "META-INF/maven/com.google.guava/guava/pom.xml"
//        exclude "META-INF/maven/com.google.guava/guava/pom.properties"
//    }
}

dependencies {
    testImplementation("junit:junit:${("JUNIT_VERSION")}")
    androidTestImplementation("com.android.support.test:runner:${("ANDROIDX_TEST_RUNNER")}")
    androidTestImplementation("com.android.support.test:rules:${("ANDROIDX_TEST_RULES")}")
    androidTestImplementation("androidx.test.espresso:espresso-core:${("ANDROIDX_TEST_ESPRESSO")}")
}
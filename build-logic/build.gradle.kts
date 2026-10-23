plugins { `java-gradle-plugin` }

repositories {
    google()
    mavenCentral()
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:9.2.1") { isTransitive = false }
    compileOnly("org.ow2.asm:asm:9.9")
}

gradlePlugin {
    plugins {
        create("media3ControllerIdentity") {
            id = "pictureshow.media3-controller-identity"
            implementationClass = "app.pictureshow.buildlogic.Media3ControllerIdentityPlugin"
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

plugins {
    id("com.android.application") version "8.8.0" apply false
    kotlin("android") version "1.9.22" apply false
    kotlin("jvm") version "1.9.22" apply false
    kotlin("plugin.serialization") version "1.9.22" apply false
}
val tripBuildRoot=providers.gradleProperty("tripBuildRoot").orNull
subprojects { layout.buildDirectory.set(if(tripBuildRoot!=null) file("$tripBuildRoot/${project.name}") else rootProject.file("../work/build/${project.name}")) }

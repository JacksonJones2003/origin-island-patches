group = "app.originisland"

patches {
    about {
        name = "Origin Island Patches"
        description = "Patches that integrate apps with the OriginOS music card"
        source = "git@github.com:JacksonJones2003/origin-island-patches.git"
        author = "JacksonJones2003"
        contact = "na"
        website = "https://github.com/JacksonJones2003/origin-island-patches"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}

package dev.cupthread.feedback

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ensures that the repository root includes a standard MIT license file,
 * that README.md stays aligned with the declared license, and that the published
 * Maven POM declares MIT license metadata for consumers.
 *
 * Prevents regression of issue #4 ("legal: add the MIT license file declared by the README").
 */
class LicenseTest {

    private fun findRepoRoot(): File {
        var current: File? = File(".").canonicalFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").exists() && File(current, "feedback").isDirectory) {
                return current
            }
            current = current.parentFile
        }
        error("Unable to locate repository root from ${File(".").canonicalPath}")
    }

    @Test
    fun licenseFileExistsAtRepoRoot() {
        val root = findRepoRoot()
        val licenseFile = File(root, "LICENSE")
        assertTrue("LICENSE file must exist at repository root (${licenseFile.canonicalPath})", licenseFile.exists())
        assertTrue("LICENSE must be a regular file", licenseFile.isFile)
    }

    @Test
    fun licenseFileContainsMitLicenseTextAndCopyright() {
        val root = findRepoRoot()
        val licenseFile = File(root, "LICENSE")
        val content = licenseFile.readText()

        assertTrue("LICENSE must mention MIT License", content.contains("MIT License"))
        assertTrue("LICENSE must contain CupThread copyright", content.contains("Copyright (c) 2026 CupThread"))
        assertTrue(
            "LICENSE must include standard MIT grant notice",
            content.contains("Permission is hereby granted, free of charge, to any person obtaining a copy")
        )
        assertTrue(
            "LICENSE must include standard disclaimer",
            content.contains("THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND")
        )
    }

    @Test
    fun readmeDeclaresMatchingMitLicense() {
        val root = findRepoRoot()
        val readmeFile = File(root, "README.md")
        assertTrue("README.md must exist", readmeFile.exists())
        val content = readmeFile.readText()
        assertTrue("README.md must declare MIT license", content.contains("## License\nMIT"))
    }

    @Test
    fun mavenPublishingDeclaresMitLicense() {
        val root = findRepoRoot()
        val buildFile = File(root, "feedback/build.gradle.kts")
        assertTrue("feedback/build.gradle.kts must exist", buildFile.exists())
        val content = buildFile.readText()
        assertTrue("feedback POM must configure licenses block", content.contains("licenses {"))
        assertTrue("feedback POM must configure MIT License", content.contains("name = \"MIT License\"") || content.contains("name.set(\"MIT License\")"))
        assertTrue("feedback POM must link to OSI MIT URL", content.contains("https://opensource.org/licenses/MIT"))
    }
}

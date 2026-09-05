plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.kover)
}

dependencies {
    kover(project(":app"))
    kover(project(":data"))
    kover(project(":domain"))
    kover(project(":player"))
}

// Ceilings for the accepted lint backlog, keyed by baseline file. `verifyLintBaseline`
// fails if a baseline grows past its ceiling, so new warnings must be fixed rather than
// baselined. When you burn issues down, lower the matching number here in the same commit.
val lintBaselineCeilings = mapOf(
    "app/lint-baseline.xml" to 1560,
    "data/lint-baseline.xml" to 18,
    "player/lint-baseline.xml" to 9
)

tasks.register("verifyLintBaseline") {
    group = "verification"
    description =
        "Verifies the committed lint baselines are present, non-empty, and no larger than their recorded ceilings."
    doLast {
        val baselinePaths = lintBaselineCeilings.keys.toList()
        val issuePattern = Regex("""<issue(?:\s|>)""")
        val issueIdPattern = Regex("""<issue\b[^>]*\bid=\"([^\"]+)\"""")

        baselinePaths.forEach { path ->
            val baseline = rootProject.file(path)
            check(baseline.isFile) {
                "Lint baseline not found: $path"
            }

            val content = baseline.readText()
            val issueCount = issuePattern.findAll(content).count()
            check(issueCount > 0) {
                "Lint baseline is empty; remove it and require a clean lint run: $path"
            }

            val issueTypeCount = issueIdPattern.findAll(content)
                .map { it.groupValues[1] }
                .toSet()
                .size
            check(issueTypeCount > 0) {
                "Lint baseline contains no issue identifiers: $path"
            }
            println("$path contains $issueCount issue records across $issueTypeCount issue types.")

            // Baselines are intentionally committed and owned. This check prevents a later
            // change from silently deleting the accepted backlog to make CI green.
            check("by=\"lint " in content) {
                "Lint baseline must retain the generated marker for reviewability: $path"
            }

            // Ratchet: the backlog may shrink freely, but growing it requires an explicit,
            // reviewable bump of the ceiling above.
            val ceiling = lintBaselineCeilings.getValue(path)
            check(issueCount <= ceiling) {
                "Lint baseline grew from at most $ceiling to $issueCount issues: $path. " +
                    "Fix the new warnings instead of baselining them, or raise the ceiling in " +
                    "build.gradle.kts with justification."
            }
            if (issueCount < ceiling) {
                println(
                    "$path is ${ceiling - issueCount} issues below its ceiling of $ceiling; " +
                        "lower lintBaselineCeilings in build.gradle.kts to lock the improvement in."
                )
            }
        }
    }
}

kover {
    currentProject {
        createVariant("ci") {}
    }
    reports {
        variant("ci") {
            xml {
                onCheck = false
                xmlFile = layout.buildDirectory.file("reports/kover/report.xml")
            }
            html {
                onCheck = false
                htmlDir = layout.buildDirectory.dir("reports/kover/html")
            }
        }
        filters {
            excludes {
                classes(
                    "*.BuildConfig",
                    "*.Manifest",
                    "*.Manifest*",
                    "*.R",
                    "*.R$*",
                    "*.ComposableSingletons*",
                    "dagger.hilt.internal.*",
                    "hilt_aggregated_deps.*",
                    "*Hilt*",
                    "*_Factory",
                    "*_Factory$*",
                    "*_MembersInjector",
                    "*_HiltModules*"
                )
            }
        }
    }
}

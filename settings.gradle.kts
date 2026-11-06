pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://gitlab.com/api/v4/projects/38224197/packages/maven")
    }
    versionCatalogs {
        create("libs") {
            from(files("upstream/bilimiao2/gradle-libs.versions.toml"))
        }
    }
}
rootProject.name = "BiliVdl"
include(":app")

// bilimiao2 playback / API / danmaku / download libraries
include(":bilimiao-compose")
project(":bilimiao-compose").projectDir = file("upstream/bilimiao2/bilimiao-compose")
include(":bilimiao-comm")
project(":bilimiao-comm").projectDir = file("upstream/bilimiao2/bilimiao-comm")
include(":bilimiao-download")
project(":bilimiao-download").projectDir = file("upstream/bilimiao2/bilimiao-download")
include(":bilimiao-grpc")
project(":bilimiao-grpc").projectDir = file("upstream/bilimiao2/bilimiao-grpc")
include(":bilimiao-grpc:proto")
project(":bilimiao-grpc:proto").projectDir = file("upstream/bilimiao2/bilimiao-grpc/proto")
include(":bilimiao-grpc:generator")
project(":bilimiao-grpc:generator").projectDir = file("upstream/bilimiao2/bilimiao-grpc/generator")
include(":danmaku-engine")
project(":danmaku-engine").projectDir = file("upstream/bilimiao2/danmaku-engine")

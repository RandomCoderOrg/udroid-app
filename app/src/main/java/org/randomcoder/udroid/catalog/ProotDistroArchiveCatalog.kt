package org.randomcoder.udroid.catalog

/**
 * Rootfs archives published by termux/proot-distro before its OCI transition.
 *
 * These are deliberately pinned to an upstream release and checksum. Current
 * proot-distro can install arbitrary OCI images, but uDroid must not present an
 * untested registry result as a trusted one-tap installation.
 */
object ProotDistroArchiveCatalog {
    fun forArchitecture(architecture: String): List<DistroVariant> =
        recipes.mapNotNull { recipe ->
            val artifact = recipe.artifacts[architecture] ?: return@mapNotNull null
            DistroVariant(
                suite = recipe.suite,
                variant = "base",
                internalName = "proot-${recipe.distribution.id}-${recipe.suite}",
                friendlyName = recipe.releaseName,
                architecture = architecture,
                downloadUrl = artifact.url,
                sha256 = artifact.sha256,
                distribution = recipe.distribution,
                provider = DistroProvider.PROOT_DISTRO,
                releaseLabel = recipe.releaseName,
                archiveStripComponents = 1,
            )
        }

    private data class Recipe(
        val distribution: LinuxDistribution,
        val suite: String,
        val releaseName: String,
        val artifacts: Map<String, Artifact>,
    )

    private data class Artifact(
        val url: String,
        val sha256: String,
    )

    private val recipes =
        listOf(
            Recipe(
                distribution = LinuxDistribution.DEBIAN,
                suite = "trixie",
                releaseName = "Debian 13 (Trixie)",
                artifacts =
                    mapOf(
                        "aarch64" to
                            Artifact(
                                "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz",
                                "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918",
                            ),
                        "armhf" to
                            Artifact(
                                "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-arm-pd-v4.29.0.tar.xz",
                                "99bcba87d8d1c66c0de06259ac0a270eb0a20f8b4af39beb0705d28846d78b90",
                            ),
                        "amd64" to
                            Artifact(
                                "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-x86_64-pd-v4.29.0.tar.xz",
                                "4b8f33b80a10d734ff935e5934588572f860c0c38a68bf91db59af0580370716",
                            ),
                    ),
            ),
            Recipe(
                distribution = LinuxDistribution.ARCH,
                suite = "rolling",
                releaseName = "Arch Linux",
                artifacts =
                    mapOf(
                        "aarch64" to
                            Artifact(
                                "https://github.com/termux/proot-distro/releases/download/v4.29.0/archlinux-aarch64-pd-v4.29.0.tar.xz",
                                "08d74365213e647c558e561b0a2a7afb6fa3dfe345a1994c62ccac5af1a1cdc6",
                            ),
                        "armhf" to
                            Artifact(
                                "https://github.com/termux/proot-distro/releases/download/v4.29.0/archlinux-arm-pd-v4.29.0.tar.xz",
                                "df17fd1058a103ed64811900498c9432abd303eee3eb27cbacab041a14011fba",
                            ),
                        "amd64" to
                            Artifact(
                                "https://github.com/termux/proot-distro/releases/download/v4.29.0/archlinux-x86_64-pd-v4.29.0.tar.xz",
                                "8249202836643a4a4f922004c34faa2c3f7d9fec0464ee23b087ad325f1610d9",
                            ),
                    ),
            ),
            Recipe(
                distribution = LinuxDistribution.ALPINE,
                suite = "3.22",
                releaseName = "Alpine Linux 3.22",
                artifacts =
                    mapOf(
                        "aarch64" to
                            Artifact(
                                "https://github.com/termux/proot-distro/releases/download/v4.30.1/alpine-aarch64-pd-v4.30.1.tar.xz",
                                "bb23e51cd5b5ae56bf946a34992876902de1bb2ecc0f639d59c702c6371adc62",
                            ),
                        "armhf" to
                            Artifact(
                                "https://github.com/termux/proot-distro/releases/download/v4.30.1/alpine-arm-pd-v4.30.1.tar.xz",
                                "ca1039d26481b63a412cd39d699c7f559c40ed5c532573c720e00218b5af0fd4",
                            ),
                        "amd64" to
                            Artifact(
                                "https://github.com/termux/proot-distro/releases/download/v4.30.1/alpine-x86_64-pd-v4.30.1.tar.xz",
                                "0890920f83becc1c3529ca53fc71d7516a01d3de4139fbe936c8c60c6c32f8d1",
                            ),
                    ),
            ),
            Recipe(
                distribution = LinuxDistribution.VOID,
                suite = "rolling",
                releaseName = "Void Linux",
                artifacts =
                    mapOf(
                        "aarch64" to
                            Artifact(
                                "https://github.com/termux/proot-distro/releases/download/v4.29.0/void-aarch64-pd-v4.29.0.tar.xz",
                                "7a7c449b3efe504749e40f556d13812010bccc930a820a56973a0f5fc2f16997",
                            ),
                        "armhf" to
                            Artifact(
                                "https://github.com/termux/proot-distro/releases/download/v4.29.0/void-arm-pd-v4.29.0.tar.xz",
                                "5cb87c0ca8ee91047f3634789314920be6d914ce4f196157cb3949706ce18d03",
                            ),
                        "amd64" to
                            Artifact(
                                "https://github.com/termux/proot-distro/releases/download/v4.29.0/void-x86_64-pd-v4.29.0.tar.xz",
                                "2853b9433b9051aa2512e7376a71736196fb3241eb90ba11110c6e867854c666",
                            ),
                    ),
            ),
        )
}

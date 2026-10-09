"""Android framework jars for grpc-java's Robolectric tests.

Robolectric runs tests against an "android-all-instrumented" jar per Android
SDK level, which it normally downloads from Maven Central while tests run.
Bazel tests run offline, so this repository rule downloads, up front and with
checksums, the jars that our tests use. It also writes the
robolectric-deps.properties file that tells Robolectric where they are.
"""

# SDK level -> (android-all-instrumented version, sha256).
#
# The "-iN" suffix must match the Robolectric version in MODULE.bazel. Versions
# and checksums for other SDK levels or Robolectric releases are in
# bazel/robolectric.bzl of the matching release of
# https://github.com/robolectric/robolectric-bazel.
#
# Tests use SDK 23 unless they ask for others with @Config(sdk = ...). A
# missing level fails tests with "no artifacts found for
# org.robolectric:android-all-instrumented:...".
_ANDROID_ALL_INSTRUMENTED = {
    23: ("6.0.1_r3-robolectric-r1-i7", "bc9148d548ce6875f0df9aadb0535ef2e3a5a8cf385f47a5c6c4060a7aab4e75"),
    24: ("7.0.0_r1-robolectric-r1-i7", "57dc8d57a5d69adacf3aa4306aefbf147bd1621da347e20f3cf1f41c88a5dd49"),
    28: ("9-robolectric-4913185-2-i7", "dbb1c88987b54cef3132695a1b4d2f4851ba06286a6fa7d15772f98fd1f0cdf7"),
    29: ("10-robolectric-5803371-i7", "700fb3bb88ff346969b955241b86d2f55d395488bf5df1e8eddfbd003980c78a"),
    30: ("11-robolectric-6757853-i7", "7f4accae465a47e75e7591877b98e71fb982a908cffc4ba4b3ca0319cb6a0a10"),
    33: ("13-robolectric-9030017-i7", "1c3fd182c03d47626686ca3b39c4ec5bdb0898b1056f4eb8f96f32c6209a5c04"),
}

_URLS = [
    "https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented/{version}/android-all-instrumented-{version}.jar",
    "https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/{version}/android-all-instrumented-{version}.jar",
]

_BUILD = """\
exports_files(["robolectric-deps.properties"])

filegroup(
    name = "android_all",
    srcs = ["robolectric-deps.properties"] + {jars},
    visibility = ["//visibility:public"],
)
"""

def _robolectric_sdks_repo_impl(rctx):
    jars = []
    properties = []
    downloads = []
    for version, sha256 in _ANDROID_ALL_INSTRUMENTED.values():
        jar = "android-all-instrumented-%s.jar" % version
        downloads.append(rctx.download(
            url = [url.format(version = version) for url in _URLS],
            output = jar,
            sha256 = sha256,
            block = False,
        ))
        jars.append(jar)

        # Robolectric looks jars up by Maven coordinates, and resolves paths
        # relative to the properties file. ':' would end the key unescaped.
        properties.append("org.robolectric\\:android-all-instrumented\\:%s=%s" % (version, jar))
    for download in downloads:
        download.wait()
    rctx.file("robolectric-deps.properties", "\n".join(properties) + "\n", executable = False)
    rctx.file("BUILD.bazel", _BUILD.format(jars = repr(jars)), executable = False)

robolectric_sdks_repo = repository_rule(
    implementation = _robolectric_sdks_repo_impl,
    doc = "Downloads android-all-instrumented jars and writes robolectric-deps.properties for them.",
)
